// SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
// SPDX-License-Identifier: MIT
module cloud_lighting_cache;

import "rtx/pass/cloud_parameters.slang";
import "rtx/utility/cloud_density.slang";
#include "rtx/pass/clouds/cloud_lighting_cache.h"
#include "rtx/pass/cloud_noise.h"

// The directional tables contain beam optical depth. The local atlas
// contains vertical optical depth toward the top and bottom of the layer.
public struct CloudLightingCache
{
  public Sampler3D<float> sun;
  public Sampler3D<float> moon;
  public Sampler3D<float2> local;
  public Sampler3D<float4> neural;
  public Sampler3D<float4> direct;
};

[ForceInline]
public uint cloudLightingResolution(CloudArgs args)
{
  return CLOUD_LIGHTING_RESOLUTION_PER_STEP
    * clamp(args.lightSteps, uint(CLOUD_LIGHTING_MIN_QUALITY), uint(CLOUD_LIGHTING_MAX_QUALITY));
}

[ForceInline]
public bool cloudLightingHasFarCascade(CloudArgs args)
{
  return args.lightingNearDistance > 0 && args.lightMaxDistance > args.lightingNearDistance;
}

[ForceInline]
public uint cloudLightingCascadeResolution(CloudArgs args, uint cascade)
{
  return cloudLightingResolution(args) >> cascade;
}

[ForceInline]
float3 cloudSecondHorizontalAxis(CloudArgs args)
{
  return args.up.z > 0.5f ? float3(0, 1, 0) : float3(0, 0, 1);
}

public float2 cloudLightHorizontal(CloudArgs args, float3 position)
{
  return float2(position.x, dot(position, cloudSecondHorizontalAxis(args)));
}

public float cloudLightGridCoverage(float2 uv)
{
  const float2 edge = abs(uv - 0.5f);
  return 1.0f - smoothstep(0.375f, 0.5f, max(edge.x, edge.y));
}

[ForceInline]
public float cloudLightingRadius(CloudArgs args)
{
  return max(args.lightMaxDistance, 4.0f * args.shapeScale);
}

public uint cloudLocalLightHeightSamples(uint layer)
{
  return layer == 0 ? CLOUD_LOCAL_LIGHT_HEIGHT_SAMPLES : CLOUD_LOCAL_LIGHT_HIGH_HEIGHT_SAMPLES;
}

public uint cloudLocalLightHeightOffset(uint layer)
{
  return layer == 0 ? 0 : CLOUD_LOCAL_LIGHT_HEIGHT_SAMPLES;
}

public float cloudLocalLightRadius(CloudArgs args, uint layer)
{
  return max(layer == 0 ? args.maxDistance : args.highMaxDistance, cloudLightingRadius(args));
}

public float3 cloudLocalLightCoordinates(CloudArgs args, float3 position, float altitude, uint layer)
{
  const float diameter = 2.0f * cloudLocalLightRadius(args, layer);
  const float edge = 0.5f / float(CLOUD_LOCAL_LIGHT_RESOLUTION);
  const float2 uv = clamp(cloudLightHorizontal(args, position - args.groundReference) / diameter + 0.5f,
    edge, 1.0f - edge);
  const float layerBase = layer == 0 ? args.baseHeight : args.highBaseHeight;
  const float thickness = layer == 0 ? args.thickness : args.highThickness;
  const float samples = float(cloudLocalLightHeightSamples(layer));
  // Keep interpolation within the selected shell at the top and bottom.
  const float slice = clamp((altitude - layerBase) / thickness * samples, 0.5f, samples - 0.5f);
  return float3(uv.x, (cloudLocalLightHeightOffset(layer) + slice) / CLOUD_LOCAL_LIGHT_PACKED_HEIGHT, uv.y);
}

public float3 cloudLocalLightPosition(CloudArgs args, float2 uv, float altitude, uint layer)
{
  const float2 horizontal = (uv - 0.5f) * (2.0f * cloudLocalLightRadius(args, layer));
  return cloudLightPositionAtAltitude(args, horizontal, altitude);
}

public float3 cloudLightPositionAtAltitude(CloudArgs args, float2 horizontal, float altitude)
{
  const float radius = args.planetRadius + altitude;
  const float horizontalSquared = dot(horizontal, horizontal);
  const float offset = horizontalSquared / (radius + sqrt(max(0.0f, radius * radius - horizontalSquared)));
  return args.groundReference + float3(horizontal.x, 0, 0) + horizontal.y * cloudSecondHorizontalAxis(args)
    + args.up * (altitude - offset);
}

public struct CloudLightGrid
{
  public float3 origin;
  public float3 horizontal;
  public float3 vertical;
  public float3 direction;
  public float3 extent;

  public float3 cellSize(uint resolution)
  {
    return extent / float3(resolution, resolution / CLOUD_LIGHTING_LIGHT_HEIGHT_DIVISOR, resolution - 1);
  }

  public float3 coordinates(float3 position)
  {
    const float3 offset = position - origin;
    return float3(dot(offset, horizontal), dot(offset, vertical), dot(offset, direction)) / extent;
  }

  public float3 position(float3 coordinate)
  {
    const float3 offset = coordinate * extent;
    return origin + horizontal * offset.x + vertical * offset.y + direction * offset.z;
  }
};

public CloudLightGrid cloudLightGrid(CloudArgs args, uint light, uint cascade = 0)
{
  CloudLightGrid grid = {};
  const float3 direction = args.lights[light].directionToLight;
  grid.direction = dot(direction, direction) > 1e-8f ? normalize(direction) : args.up;
  const float3 perpendicular = cross(args.up, grid.direction);
  grid.horizontal = dot(perpendicular, perpendicular) > 1e-8f
    ? normalize(perpendicular) : float3(1, 0, 0);
  grid.vertical = cross(grid.direction, grid.horizontal);

  const float radius = cascade == 0 && cloudLightingHasFarCascade(args)
    ? args.lightingNearDistance : cloudLightingRadius(args);
  // Include surface shadow rays as well as the visible shell. Curvature is
  // bounded by the corners of the horizontal box, not by a flat cloud plane.
  const float lower = min(0.0f, args.baseHeight) - radius * radius / args.planetRadius;
  const float upper = args.baseHeight + args.thickness;
  const float middle = 0.5f * (lower + upper);
  const float halfHeight = 0.5f * (upper - lower);
  const float3 second = cloudSecondHorizontalAxis(args);
  const float3 center = args.up * middle;
  const float3 halfExtent = float3(
    radius * (abs(grid.horizontal.x) + abs(dot(grid.horizontal, second)))
      + halfHeight * abs(dot(grid.horizontal, args.up)),
    radius * (abs(grid.vertical.x) + abs(dot(grid.vertical, second)))
      + halfHeight * abs(dot(grid.vertical, args.up)),
    radius * (abs(grid.direction.x) + abs(dot(grid.direction, second)))
      + halfHeight * abs(dot(grid.direction, args.up)));
  const float3 projectedCenter = float3(dot(center, grid.horizontal), dot(center, grid.vertical),
    dot(center, grid.direction));
  // Anchor the prefix samples to the advected density field. Otherwise even
  // a metre of camera movement resamples an entire distant shadow column.
  // Reserve one cell for snapping so the requested volume remains enclosed.
  const uint resolution = cloudLightingCascadeResolution(args, cascade);
  const float3 cells = float3(resolution, resolution / CLOUD_LIGHTING_LIGHT_HEIGHT_DIVISOR, resolution - 1);
  const float3 spacing = (2.0f * halfExtent + float3(0, 0, args.lightMaxDistance)) / (cells - 1);
  const float3 anchor = args.groundReference - args.windOffset;
  const float3 projectedAnchor = float3(dot(anchor, grid.horizontal), dot(anchor, grid.vertical), dot(anchor, grid.direction));
  const float3 corner = projectedCenter - halfExtent;
  const float3 aligned = corner - frac((corner + projectedAnchor) / spacing) * spacing;
  grid.origin = args.groundReference + grid.horizontal * aligned.x + grid.vertical * aligned.y
    + grid.direction * aligned.z;
  grid.extent = spacing * cells;
  return grid;
}

[ForceInline]
float cloudLightPrefixSample(Sampler3D<float> prefix, int2 column, float coordinate, uint resolution, uint offset)
{
  const float node = saturate(coordinate) * float(resolution - 1);
  const uint lower = uint(node);
  const uint upper = min(lower + 1, resolution - 1);
  // Integer loads cannot touch undefined padding or an adjacent cascade,
  // including zero-weight texels that a hardware linear filter may access.
  const float a = prefix.Load(int4(column, int(offset + lower), 0));
  const float b = prefix.Load(int4(column, int(offset + upper), 0));
  return lerp(a, b, frac(node));
}

public bool cloudCachedLightDepthInGrid(CloudArgs args, CloudLightingCache cache, uint light,
  CloudLightGrid grid, float3 begin, float3 end, out float depth, uint cascade)
{
  depth = 0;
  if (all(begin == end))
  {
    return true;
  }
  const float3 from = grid.coordinates(begin);
  const float3 to = grid.coordinates(end);
  if (any(from.xy < 0) || any(from.xy > 1) || any(to.xy < 0) || any(to.xy > 1)
    || min(from.z, to.z) < 0 || max(from.z, to.z) > 1)
  {
    return false;
  }
  const uint resolution = cloudLightingCascadeResolution(args, cascade);
  const uint depthOffset = cascade == 0 ? 0 : cloudLightingResolution(args);
  const float2 size = float2(resolution, resolution / CLOUD_LIGHTING_LIGHT_HEIGHT_DIVISOR);
  const float2 pixel = from.xy * size - 0.5f;
  const int2 lower = int2(floor(pixel));
  const float2 fraction = frac(pixel);
  float4 opticalDepth = 0;
  const float4 weights = float4((1.0f - fraction.x) * (1.0f - fraction.y),
    fraction.x * (1.0f - fraction.y), (1.0f - fraction.x) * fraction.y, fraction.x * fraction.y);
  float minimum = 3.402823466e+38f;
  [unroll]
  for (uint tap = 0; tap < 4; tap++)
  {
    const int2 offset = int2(tap & 1u, tap >> 1u);
    const int2 column = clamp(lower + offset, int2(0), int2(size) - 1);
    float a;
    float b;
    if (light == 0)
    {
      a = from.z >= 1 ? 0 : cloudLightPrefixSample(cache.sun, column, from.z, resolution, depthOffset);
      b = to.z >= 1 ? 0 : cloudLightPrefixSample(cache.sun, column, to.z, resolution, depthOffset);
    }
    else
    {
      a = from.z >= 1 ? 0 : cloudLightPrefixSample(cache.moon, column, from.z, resolution, depthOffset);
      b = to.z >= 1 ? 0 : cloudLightPrefixSample(cache.moon, column, to.z, resolution, depthOffset);
    }
    opticalDepth[tap] = max(0.0f, from.z < to.z ? a - b : b - a);
    if (weights[tap] > 0)
    {
      minimum = min(minimum, opticalDepth[tap]);
    }
  }
  // Interpolate transmitted energy. Exponentiating a filtered optical depth
  // would darken unresolved clear paths (Jensen's inequality). The shift keeps
  // this finite even when every complete prefix is deeply opaque.
  const float transmission = dot(weights, exp(minimum - opticalDepth));
  depth = max(0.0f, minimum - log(max(transmission, 1e-30f)));
  return true;
}

public bool cloudCachedLightDepth(CloudArgs args, CloudLightingCache cache, uint light,
  float3 begin, float3 end, out float depth, uint cascade = 0)
{
  return cloudCachedLightDepthInGrid(args, cache, light, cloudLightGrid(args, light, cascade),
    begin, end, depth, cascade);
}

public bool cloudLightCascadeContainsInGrid(CloudLightGrid grid, float3 begin, float3 end)
{
  const float3 from = grid.coordinates(begin);
  const float3 to = grid.coordinates(end);
  return all(from >= 0) && all(from <= 1) && all(to >= 0) && all(to <= 1);
}

public bool cloudLightCascadeContains(CloudArgs args, uint light, uint cascade, float3 begin, float3 end)
{
  return cloudLightCascadeContainsInGrid(cloudLightGrid(args, light, cascade), begin, end);
}

public float cloudLightingFarWeight(CloudArgs args, float3 position)
{
  if (!cloudLightingHasFarCascade(args))
  {
    return 0;
  }
  const float3 offset = position - args.groundReference;
  const float distance = max(abs(offset.x), abs(dot(offset, cloudSecondHorizontalAxis(args))));
  return smoothstep(0.75f * args.lightingNearDistance, args.lightingNearDistance, distance);
}

public bool cloudLightHasEnergy(CloudLight light)
{
  if (any(light.irradiance > 0))
  {
    return true;
  }
  if (light.hasRadianceProfile != 0)
  {
    [unroll]
    for (uint sample = 0; sample < 4; sample++)
    {
      if (any(light.radianceProfile[sample].rgb > 0))
      {
        return true;
      }
    }
  }
  return false;
}

public int cloudCachedLightIndex(CloudArgs args, float3 direction)
{
  [unroll]
  for (uint light = 0; light < 2; light++)
  {
    // The small celestial disk shares a prefiltered central-axis far shadow.
    // Its actual sampled direction is retained by the local correction. Large
    // area lights use the bounded uncached quadrature throughout their cone.
    const float radius = args.lights[light].angularRadius;
    if (radius <= 0.02f && cloudLightHasEnergy(args.lights[light])
      && dot(direction, args.lights[light].directionToLight) >= cos(radius + 1e-5f) - 2e-7f)
    {
      return int(light);
    }
  }
  return -1;
}

float cloudCacheExtinction(CloudArgs args, CloudDensityArgs density, float3 position,
  Sampler2DArray<float4> weather, Sampler3D<float4> base,
  Sampler3D<float4> detail)
{
  return args.extinction * cloudDensity(density, position, weather, base, detail, 0);
}

void buildCloudLightCascade(CloudArgs args, CloudDensityArgs density, uint2 column, uint light, uint cascade,
  Sampler2DArray<float4> weather, Sampler3D<float4> base, Sampler3D<float4> detail, RWTexture3D<float> output)
{
  const uint resolution = cloudLightingCascadeResolution(args, cascade);
  const uint offset = cascade == 0 ? 0 : cloudLightingResolution(args);
  const uint2 extent = uint2(resolution, resolution / CLOUD_LIGHTING_LIGHT_HEIGHT_DIVISOR);
  if (any(column >= extent))
  {
    return;
  }
  const CloudLightGrid grid = cloudLightGrid(args, light, cascade);
  const float2 horizontal = (float2(column) + 0.5f) / float2(extent);
  const float span = grid.extent.z / float(resolution - 1);
  // The prefix grid controls storage, not the shadow quadrature spacing.
  // Resolve density along the light ray without an isotropic cell-sized mip,
  // which would pull neighbouring off-ray clouds into otherwise clear gaps.
  const float nominalSupport = 0.5f * args.shapeScale / float(max(args.lightSteps, 1u));
  const uint subcells = min(max(1u, args.lightSteps / 2), max(1u, uint(ceil(span / (2.0f * nominalSupport)))));
  const float subspan = span / float(subcells);
  const float support = 0.5f * subspan;
  float depth = 0;
  output[uint3(column, offset + resolution - 1)] = 0;
  for (uint node = resolution - 1; node > 0; node--)
  {
    const float lower = float(node - 1);
    const float3 middle = grid.position(float3(horizontal, (lower + 0.5f) / float(resolution - 1)));
    const float3 radialMiddle = normalize(middle - args.groundReference + args.up * args.planetRadius);
    const float altitude = cloudAltitude(density, middle);
    const float heightExtent = 0.6f * span * abs(dot(grid.direction, radialMiddle))
      + span * span / args.planetRadius;
    // All Gaussian supports in this cache cell fit within this radial bound.
    if (altitude + heightExtent >= args.baseHeight && altitude - heightExtent <= args.baseHeight + args.thickness)
    {
      for (uint subcell = 0; subcell < subcells; subcell++)
      {
        const float aNode = lower + (float(subcell) + 0.21132486541f) / float(subcells);
        const float bNode = lower + (float(subcell) + 0.78867513459f) / float(subcells);
        const float3 a = grid.position(float3(horizontal, aNode / float(resolution - 1)));
        const float3 b = grid.position(float3(horizontal, bNode / float(resolution - 1)));
        const float extinctionA = cloudCacheExtinction(args, density, a, weather, base, detail);
        const float extinctionB = cloudCacheExtinction(args, density, b, weather, base, detail);
        depth += support * (extinctionA + extinctionB);
      }
    }
    output[uint3(column, offset + node - 1)] = depth;
  }
}

public void buildCloudLightPrefix(CloudArgs args, CloudDensityArgs density, uint2 column, uint light,
  Sampler2DArray<float4> weather, Sampler3D<float4> base, Sampler3D<float4> detail, RWTexture3D<float> output)
{
  buildCloudLightCascade(args, density, column, light, 0, weather, base, detail, output);
  if (cloudLightingHasFarCascade(args))
  {
    buildCloudLightCascade(args, density, column, light, 1, weather, base, detail, output);
  }
}
