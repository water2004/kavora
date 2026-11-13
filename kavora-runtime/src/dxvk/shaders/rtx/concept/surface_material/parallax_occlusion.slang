/*
* Copyright (c) 2023-2026, NVIDIA CORPORATION. All rights reserved.
*
* Permission is hereby granted, free of charge, to any person obtaining a
* copy of this software and associated documentation files (the "Software"),
* to deal in the Software without restriction, including without limitation
* the rights to use, copy, modify, merge, publish, distribute, sublicense,
* and/or sell copies of the Software, and to permit persons to whom the
* Software is furnished to do so, subject to the following conditions:
*
* The above copyright notice and this permission notice shall be included in
* all copies or substantial portions of the Software.
*
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
* IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
* FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.  IN NO EVENT SHALL
* THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
* LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
* FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
* DEALINGS IN THE SOFTWARE.
*/
module parallax_occlusion;

import "rtx/utility/atlas_coordinates.slang";

#include "rtx/concept/surface/surface_shared.h"
#include "rtx/utility/atlas_mip_bounds.h"

public struct ParallaxSettings
{
  public uint mode;
  public float mipBias;
  public uint maxIterations;
};

public struct ParallaxSurface
{
  public float2 uv;
  public float2 gradientX;
  public float2 gradientY;
  public float4 atlasBounds;
};

// Parallax Occlusion Mapping Helper Functions
// Define a `box` as a region in tangent space that corresponds to a pixel in a mipmap level, with min_z = 0 and max_z equal to the max height in that region.

// get the smaller corner of the box that contains 'pos'
float2 pomGetBoxCorner(float2 pos, float2 boxSize)
{
  return floor(pos / boxSize) * boxSize;
}

// size in tangent space of a box at a given mip level
float2 pomGetBoxSize(uint level, uint2 fullBoxSize)
{
  uint2 levelSize =  max(fullBoxSize >> level, 1u);
  return 1.f / levelSize;
}

float pomGetBoxHeight(Texture2D texture, SamplerState sampler, float2 boxCenter, float lod, AtlasSampleRegion region)
{
  boxCenter = region.wrap(boxCenter);
  return texture.SampleLevel(sampler, boxCenter, lod).r;
}

float4 pomGetPatchCorners(Texture2D texture, SamplerState sampler, float2 boxCenter, float2 boxStep, float height, float lod, AtlasSampleRegion region)
{
  // Read the 3x3 patch around the current box
  const float box00 = pomGetBoxHeight(texture, sampler, boxCenter + boxStep * float2(-1.f, -1.f), lod, region);
  const float box01 = pomGetBoxHeight(texture, sampler, boxCenter + boxStep * float2(-1.f,  0.f), lod, region);
  const float box02 = pomGetBoxHeight(texture, sampler, boxCenter + boxStep * float2(-1.f,  1.f), lod, region);
  const float box10 = pomGetBoxHeight(texture, sampler, boxCenter + boxStep * float2( 0.f, -1.f), lod, region);
  // const float box11 = height;
  const float box12 = pomGetBoxHeight(texture, sampler, boxCenter + boxStep * float2( 0.f,  1.f), lod, region);
  const float box20 = pomGetBoxHeight(texture, sampler, boxCenter + boxStep * float2( 1.f, -1.f), lod, region);
  const float box21 = pomGetBoxHeight(texture, sampler, boxCenter + boxStep * float2( 1.f,  0.f), lod, region);
  const float box22 = pomGetBoxHeight(texture, sampler, boxCenter + boxStep * float2( 1.f,  1.f), lod, region);

  // Set each corner of the box to the min of the surrounding boxes.  This is needed because the mip values are maximum heights,
  // and we want each box to share corners.
  // returns a float4 representing the height of each corner, as 00, 01, 10, 11.
  return float4(
    min(min(box00, box01), min(box10, height)),
    min(min(box01, box02), min(height, box12)),
    min(min(box10, height), min(box20, box21)),
    min(min(height, box12), min(box21, box22))
  );
}

// cast a ray in tangent space.  return an intersection point in tangent point (or an exit point if z > 1.0f)
float3 pomTraceRay(
  Texture2D texture,
  SamplerState sampler,
  float3 origin,
  float3 direction,
  float2 textureGradientX,
  float2 textureGradientY,
  inout uint iterations,
  float neutralHeight,
  float4 atlasBounds,
  ParallaxSettings settings)
{
  // Potential future improvements for outwards rays:
  // 1) calculate starting height the same way the box intersection code does instead of just sampling at a pixel.
  // 2) allow rays to escape the surface, only do intersection on entering the surface.
  uint fullWidth;
  uint fullHeight;
  uint numMipLevels;
  texture.GetDimensions(0, fullWidth, fullHeight, numMipLevels);
  const bool hasAtlasBounds = all(atlasBounds.zw > atlasBounds.xy);
  float maximumAtlasMip = 0.f;
  if (hasAtlasBounds) {
    maximumAtlasMip = AtlasSampling::maximumMip(atlasBounds.x, atlasBounds.y,
      atlasBounds.z, atlasBounds.w, fullWidth, fullHeight);
    numMipLevels = min(numMipLevels, uint(maximumAtlasMip) + 1);
  }
  const uint2 fullBoxSize = uint2(fullWidth, fullHeight);
  const bool isOutwardsRay = direction.z > 0.f;
  const float2 ddx_tex = textureGradientX * fullBoxSize;
  const float2 ddy_tex = textureGradientY * fullBoxSize;
  const float dd_max_sq = max(dot(ddx_tex, ddx_tex), dot(ddy_tex, ddy_tex));
  uint minMipLevel = max(0, min(numMipLevels - 1, 0.5f * log2(dd_max_sq) + settings.mipBias));

  origin.xy = origin.xy + (direction.xy * (1.f - neutralHeight)) / direction.z;

  // Starting halfway down the quadtree seems to generally lead to fewer iterations to resolve, especially for heights close to 1.
  // Outwards rays start at 0, since they will just descend to 0 if they start anywhere else.
  int level = isOutwardsRay ? 0 : max((numMipLevels - 1) / 2, minMipLevel);
  float3 curPos = origin;

  const float2 forwardStep = float2(direction.x >= 0.0f ? 1.f : -1.f, direction.y >= 0.0f ? 1.f : -1.f);
  const float2 forwardHalfStep = forwardStep * 0.5f;
  iterations = 0;
  float prevHeight = 1.f;
  while (level >= minMipLevel && iterations < settings.maxIterations && (!isOutwardsRay || curPos.z <= 1.f))
  {
    iterations++;
    const float2 boxSize = pomGetBoxSize(level, fullBoxSize);
    const float2 boxCenter = pomGetBoxCorner(curPos.xy, boxSize) + 0.5f * boxSize;
    float sampleLod = max(0.f, float(level) - settings.mipBias);
    if (hasAtlasBounds) {
      sampleLod = min(sampleLod, maximumAtlasMip);
    }
    const AtlasSampleRegion region = atlasSampleRegionCreate(atlasBounds, float2(fullBoxSize), ceil(sampleLod));
    const float height = pomGetBoxHeight(texture, sampler, boxCenter, sampleLod, region);
    if (level != minMipLevel && curPos.z <=  height) {
      // For mip levels above the minimum, if the curPos is below the max height of the box, just descend a level.
      level = level - 1;
      continue;
    }
    // The axis aligned planes where the ray will exit the box.
    const float2 boxFarCorner = boxCenter + forwardHalfStep * boxSize;

    // The distance to intersect with the x, y, and z planes.
    // origin.x + distance.x * m = bounds.x
    // m = (bounds.x - origin.x) / distance.x
    const float3 intersectDist = (float3(boxFarCorner, height) - origin) / direction;
    const float boundsIntersect = min(intersectDist.x, intersectDist.y);

    // The actual point where the ray exits the box.
    const float3 boxExit = origin + boundsIntersect * direction;

    if (!isOutwardsRay && intersectDist.z <= boundsIntersect)
    {
      // Hit the top of a box with an Inward ray.
      if (level == 0)
      {
        // When a single texel stretches across multiple on screen pixels, we intersect against a virtual mesh
        // constructed from the height map.
        // The virtual mesh needs to be water tight, and needs to be fully contained by the boxes of each mip
        // level - meaning the max height in a pixel cannot be higher than the actual value of the pixel.  We also
        // want this surface to be smooth, rather than being a series of boxes. Constructing this vitual surface
        // as a quad where each vertex is at a corner of the box, and has a height equal to the minimum of the four
        // surrounding pixels will meet those requirements, but does cut off a single pixel spike, and makes a
        // single pixel pit have a flat bottom.

        // NOTE: I tried improving this algorithm by colliding against a series of 4 triangles, where each triangle
        // is made of the center pixel value combined with 2 box corners from the above approach.  This unfortunately
        // causes smooth slops to turn jagged. It may be an improvement to use that approach only when the current
        // pixel is the largest or smallest of all the neighbors, but that seemed too complex to be worth pursuing.

        // Calculate the point where the ray entered this box:
        const float2 boxNearCorner = boxCenter - forwardHalfStep * boxSize;
        const float2 boxEntranceDist = (boxNearCorner - origin.xy) / direction.xy;
        const float3 boxEntrance = origin + max(0.0f, max(boxEntranceDist.x, boxEntranceDist.y)) * direction;

        const float2 forwardBoxStep = boxSize * forwardStep;
        const float4 corners = pomGetPatchCorners(texture, sampler, boxCenter, forwardBoxStep, height, sampleLod, region);
        iterations += 8; // 8 texture lookups for the patch corners, count it as 8 iterations.

        // Normalize the entrance and exit coords to a 0-1 space for lerping.
        const float2 normalEntranceCoord = (boxEntrance.xy - boxNearCorner.xy) / forwardBoxStep;
        const float2 normalExitCoord = (boxExit.xy - boxNearCorner.xy) / forwardBoxStep;

        // We expect one coord of the normalized entrance and exit coords to be 0.  Lerping like this becomes a linear interpolation
        // across the two corners the coord sits between.
        const float entranceHeight = lerp(lerp(corners[0], corners[2], normalEntranceCoord.x), lerp(corners[1], corners[3], normalEntranceCoord.x), normalEntranceCoord.y);
        const float exitHeight = lerp(corners[2], lerp(corners[1], corners[3], normalExitCoord.x), normalExitCoord.y);

        // intersect the ray against that line segment. (2d line intersection works here)
        const float frac = (boxEntrance.z - entranceHeight) / ((exitHeight - entranceHeight) - (boxExit.z - boxEntrance.z));
        const float3 interceptPos = boxEntrance + (boxExit - boxEntrance) * frac;

        if (entranceHeight > boxEntrance.z || (frac <= 1 && interceptPos.z <= height && frac >= 0)) {
          // `entranceHeight > boxEntrance.z` return if ray is already under the surface at the near side of the box.
          // `frac <= 1` reject all intersections past the far side of the box.
          // `interceptPos.z <= maxGeoHeight` reject intersections that happen above the highest point of the surface in the box.
          // `frac > 0` reject intersections that happened before the near side of the box.
          return interceptPos;
        }
        // didn't hit the virtual surface, move to next box
      }
      else if (level == minMipLevel)
      {
        // Hit the top of the box at the min mip level, but far enough away that the min mip level is > 0.
        // Use a very simple linear interpolation between prev cell height at entrance, and current cell height at exit to get a collision.
        const float frac = (curPos.z - prevHeight) / ((height - prevHeight) - (boxExit.z - curPos.z));
        return curPos + (boxExit - curPos) * frac;
      }
      else if (level > minMipLevel)
      {
        // Proceed to z intersection and reduce mip.
        curPos = origin + intersectDist.z * direction;
        level = level - 1;
        continue;
      }
    }
    // intersected with sides of box
    int nextLevel = min( numMipLevels - 1, level + 1);
    // (note, ideally we would just use something like std::nextafterf on boundsIntersect)
    const float3 nextPos = boxExit + direction * min(boxSize.x, boxSize.y) * 0.01f;

    // Maybe don't move up level
    float2 nextBoxSize = pomGetBoxSize(nextLevel, fullBoxSize);
    if (any(pomGetBoxCorner(curPos.xy, nextBoxSize) != pomGetBoxCorner(nextPos.xy, nextBoxSize)))
    {
      level = nextLevel;
    }
    curPos = nextPos;
    prevHeight = height;
  }

  // Outwards rays can go past the top of the POM surface, so limit it to there.
  if (curPos.z > 1)
  {
    float distToOne = (1.0f - origin.z) / direction.z;
    return origin + distToOne * direction;
  }

  return curPos;
}


[ForceInline]
public float pomSampleHeight(Texture2D texture, SamplerState sampler, float2 texcoord, float4 atlasBounds)
{
  uint width;
  uint height;
  texture.GetDimensions(width, height);
  texcoord = wrapAtlasCoordinates(texcoord, atlasBounds, float2(width, height));
  return 1.0f - texture.SampleLevel(sampler, texcoord, 0).r;
}

float2 pomGetStep(float3 direction, float layerSize, float totalHeight)
{
  // convert tangent space direction to texcoord
  return direction.xy / direction.z * totalHeight * layerSize;
}

float pomGetNumSamples(float3 direction, ParallaxSettings settings)
{
  const float minSamples = settings.maxIterations / 4.f;
  const float maxSamples = settings.maxIterations;

  // more samples when viewing from oblique angle
  return lerp(maxSamples, minSamples, abs(direction.z));
}

// Returns UV and normalized height, with 1 at the top of the relief.
[ForceInline]
public float3 traceParallax(Texture2D texture, SamplerState sampler, float16_t displaceIn, float16_t displaceOut,
  ParallaxSurface surface, float3 viewDir, ParallaxSettings settings, inout uint iterations)
{
  float totalHeight = displaceIn + displaceOut;
  float neutralHeight = displaceIn / totalHeight;
  const float4 bounds = surface.atlasBounds;
  totalHeight *= AtlasSampling::displacementScale(bounds.x, bounds.y, bounds.z, bounds.w);
  // TODO if the ray origin is below the displaceOut height, bad results happen.
  // need to account for that case.
  float3 direction = -float3(viewDir.xy, viewDir.z / totalHeight);
  if (settings.mode == uint(DisplacementMode::OffsetParallax)) {
    const float2 offset = viewDir.xy * totalHeight;
    const float2 uv = surface.uv;
    const float baseDepth = 1.0f - neutralHeight;
    const float firstDepth = pomSampleHeight(texture, sampler, uv, bounds);
    const float secondDepth = pomSampleHeight(texture, sampler, uv + offset * (baseDepth - firstDepth), bounds);
    const float depth = 0.5f * (firstDepth + secondDepth);
    iterations = 2;
    return float3(uv + offset * (baseDepth - depth), 1.0f - depth);
  } else if (settings.mode == uint(DisplacementMode::RaymarchPOM)) {
    float numLayers = pomGetNumSamples(viewDir, settings);
    float layerSize = rcp(numLayers);
    float2 step = pomGetStep(viewDir, layerSize, totalHeight);

    float2 currTexcoord = surface.uv;

    currTexcoord = currTexcoord + (step * numLayers * (1.f - neutralHeight));
    float currHeight = pomSampleHeight(texture, sampler, currTexcoord, surface.atlasBounds);

    // raymarch to find intersection
    float currDepth = 0.0f;
    float prevHeight = 0.0f;
    iterations = 0;
    while (currDepth < currHeight)
    {
      iterations++;
      currTexcoord -= step;
      prevHeight = currHeight;
      currHeight = pomSampleHeight(texture, sampler, currTexcoord, surface.atlasBounds);
      currDepth += layerSize;
    }

    // interpolate last two samples to reduce sample aliasing
    float depthNminus1 = prevHeight - currDepth + layerSize;
    float depthN  = currHeight - currDepth;
    float weight = saturate(depthN / (depthN - depthNminus1));
    return float3(currTexcoord + lerp(float2(0.0f), step, weight), 1.0f - (currDepth - weight * layerSize));
  } else if (settings.mode == uint(DisplacementMode::QuadtreePOM)) {

    return pomTraceRay(
        texture, sampler,
        float3(surface.uv, 1.0f),
        direction,
        surface.gradientX,
        surface.gradientY,
        iterations, neutralHeight, surface.atlasBounds, settings);
  }
  return float3(surface.uv, 1.f);
}

[ForceInline]
public float parallaxVisibility(Texture2D texture, SamplerState sampler, float16_t displaceIn, float16_t displaceOut,
  float2 texcoord, float3 rayDirection, float4 atlasBounds, ParallaxSettings settings)
{
  // Don't actually care about neutral height here - this function just checks if a ray from a texcoord successfully exits
  // the geometry described by the heightmap.  Doesn't (currently) matter how high that geometry is relative to the original surface.
  float totalHeight = displaceIn + displaceOut;
  // NOTE: pomTraceRay could be used here, but testing found it was more expensive and had worse artifacts when
  // doing visibility testing.
  float2 currTexcoord = texcoord;
  float currHeight = pomSampleHeight(texture, sampler, currTexcoord, atlasBounds);
  float currDepth = currHeight;
  float numLayers = pomGetNumSamples(rayDirection, settings);
  float layerSize = rcp(numLayers);
  float2 step = pomGetStep(rayDirection, layerSize, totalHeight);
  if (all(atlasBounds.zw > atlasBounds.xy)) {
    step *= atlasBounds.zw - atlasBounds.xy;
  }

  float visibility = 1.0f;
  while (currDepth > 0.0f)
  {
    currTexcoord += step;
    currHeight = pomSampleHeight(texture, sampler, currTexcoord, atlasBounds);
    currDepth -= layerSize;

    if (currHeight < currDepth)
    {
      visibility -= (currDepth - currHeight) / layerSize;
      // Further occluders can only reduce visibility. The clamped result is already zero.
      if (visibility <= 0.0f)
      {
        return 0.0f;
      }
    }
  }

  return saturate(visibility);
}
