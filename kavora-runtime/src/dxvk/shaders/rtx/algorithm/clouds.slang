/* SPDX-License-Identifier: MIT */
module clouds;

import "rtx/pass/cloud_parameters.slang";
import "rtx/utility/cloud_density.slang";
import "rtx/algorithm/cloud_scattering.slang";
import "rtx/algorithm/cloud_lighting_cache.slang";
import "rtx/algorithm/cloud_direct_light.slang";
import "rtx/algorithm/cloud_neural_scattering.slang";
#include "rtx/pass/cloud_noise.h"
#include "rtx/utility/cloud_noise.slangh"

static const float kFloatMax = 3.402823466e+38f;

[ForceInline]
public CloudDensityArgs cloudDensityParameters(CloudArgs args)
{
  CloudDensityArgs density;
  density.groundReference = args.groundReference;
  density.up = args.up;
  density.planetRadius = args.planetRadius;
  density.windOffset = args.windOffset;
  density.shapeScale = args.shapeScale;
  density.baseHeight = args.baseHeight;
  density.thickness = args.thickness;
  density.coverage = args.coverage;
  density.weather = args.weather;
  density.thunder = args.thunder;
  density.night = args.night;
  density.highBaseHeight = args.highBaseHeight;
  density.highThickness = args.highThickness;
  density.highCoverage = args.highCoverage;
  return density;
}

float2 cloudSphereRoots(CloudArgs args, float3 origin, float3 direction, float altitude)
{
  const float3 p = origin - args.groundReference;
  const float h = dot(p, args.up);
  const float3 horizontal = p - h * args.up;
  const float b = dot(horizontal, direction) + (args.planetRadius + h) * dot(direction, args.up);
  const float c = dot(horizontal, horizontal) + (h - altitude) * (2.0f * args.planetRadius + h + altitude);
  const float discriminant = b * b - c;
  if (discriminant < 0)
  {
    return -1;
  }
  const float root = sqrt(discriminant);
  const float q = -b - (b >= 0 ? root : -root);
  if (abs(q) < 1e-6f)
  {
    return -b;
  }
  const float other = c / q;
  return float2(min(q, other), max(q, other));
}

[ForceInline]
public bool cloudLayerEnabled(CloudArgs args, uint layer)
{
  return args.enable != 0 && (layer == 0 ? args.extinction > 0 && args.thickness > 0
    : args.highExtinction > 0 && args.highThickness > 0 && args.highCoverage > 0);
}

[ForceInline]
public float cloudLayerBase(CloudArgs args, uint layer)
{
  return layer == 0 ? args.baseHeight : args.highBaseHeight;
}

[ForceInline]
public float cloudLayerTop(CloudArgs args, uint layer)
{
  return cloudLayerBase(args, layer) + (layer == 0 ? args.thickness : args.highThickness);
}

[ForceInline]
public float cloudLayerMaxDistance(CloudArgs args, uint layer)
{
  return layer == 0 ? args.maxDistance : args.highMaxDistance;
}

[ForceInline]
public float cloudMaxViewDistance(CloudArgs args)
{
  return max(args.maxDistance, args.highMaxDistance);
}

// A grazing ray can cross a shell twice with clear air between the two intervals.
public float2 cloudLayerInterval(CloudArgs args, float3 origin, float3 direction, float distance,
  uint layer, uint segment = 0)
{
  if (!cloudLayerEnabled(args, layer))
  {
    return 0;
  }
  const float2 outer = cloudSphereRoots(args, origin, direction, cloudLayerTop(args, layer));
  const float begin = max(0.0f, outer.x);
  const float end = min(distance, outer.y);
  if (end <= begin)
  {
    return 0;
  }
  const float2 inner = cloudSphereRoots(args, origin, direction, cloudLayerBase(args, layer));
  if (inner.y <= begin || inner.x >= end)
  {
    return segment == 0 ? float2(begin, end) : float2(0);
  }
  if (inner.x <= begin)
  {
    const float entry = max(begin, inner.y);
    return segment == 0 && end > entry ? float2(entry, end) : float2(0);
  }
  const float2 interval = segment == 0 ? float2(begin, inner.x) : float2(inner.y, end);
  return interval.y > interval.x ? interval : float2(0);
}

[ForceInline]
uint cloudLayerAt(CloudArgs args, float3 position)
{
  return args.highExtinction > 0 && args.highThickness > 0
    && cloudAltitude(cloudDensityParameters(args), position) >= args.highBaseHeight ? 1u : 0u;
}

public float cloudExtinction(CloudArgs args, float3 position, Sampler2D<float4> weatherSampler,
  Sampler3D<float4> baseSampler, Sampler3D<float4> detailSampler, float footprint = 0, float heightFootprint = 0)
{
  const uint layer = cloudLayerAt(args, position);
  const float extinction = layer == 0 ? args.extinction : args.highExtinction;
  return extinction * cloudDensity(cloudDensityParameters(args), position, weatherSampler,
    baseSampler, detailSampler, footprint, layer, heightFootprint);
}

[ForceInline]
public float cloudHeightFootprint(CloudArgs args, float3 position, float3 direction, float span,
  float pixelFootprint = 0, uint layer = 0)
{
  const float3 radial = position - args.groundReference + args.planetRadius * args.up;
  const float radialLength = length(radial);
  const float cosine = dot(direction, radial) / radialLength;
  const float sineSquared = saturate(1.0f - cosine * cosine);
  const float rayFootprint = abs(cosine) * span + sineSquared * span * span / (4.0f * radialLength);
  // The high layer is a finite horizontal sheet. Its shell already clips the
  // central ray. Radial pixel-cone filtering would spill mass outside that
  // interval. Keep pixel filtering in XZ and integrate only the cell in height.
  return layer == 1 ? rayFootprint : max(rayFootprint, pixelFootprint * sqrt(sineSquared));
}

[ForceInline]
public float cloudDensityFootprint(CloudArgs args, float3 direction, float span, float pixelFootprint, uint layer)
{
  // The high field is a horizontal map. A vertical ray changes height but
  // traverses no map texels; its horizontal footprint is only the pixel cone.
  const float3 horizontal = direction - args.up * dot(direction, args.up);
  return max(layer == 0 ? span : span * length(horizontal), pixelFootprint);
}

[ForceInline]
public float cloudViewExtinction(CloudArgs args, float3 position, float distance,
  Sampler2D<float4> weatherSampler, Sampler3D<float4> baseSampler, Sampler3D<float4> detailSampler, float footprint = 0,
  float heightFootprint = 0)
{
  const float maxDistance = cloudLayerMaxDistance(args, cloudLayerAt(args, position));
  const float visibility = 1.0f - smoothstep(0.5f, 1.0f, distance / maxDistance);
  return visibility * cloudExtinction(args, position, weatherSampler, baseSampler, detailSampler, footprint, heightFootprint);
}

public static const float kOpaqueCloudTransmittance = 1e-8f;

// Clipped prefixes share the full ray's cells and density samples.
public struct CloudRayGrid
{
  public float2 interval;
  public uint cells;
  public float linearStep;
  public float quadraticStep;

  public uint cellAt(float distance)
  {
    const float offset = max(0.0f, distance - interval.x);
    const float denominator = linearStep
      + sqrt(linearStep * linearStep + 4.0f * quadraticStep * offset);
    const float coordinate = denominator > 0 ? 2.0f * offset / denominator : 0;
    uint cell = min(uint(floor(coordinate)), max(cells, 1u) - 1);
    if (cell > 0 && distance < boundary(cell))
    {
      --cell;
    }
    if (distance >= boundary(cell + 1) && cell + 1 < cells)
    {
      ++cell;
    }
    return cell;
  }

  public float boundary(uint index)
  {
    const float coordinate = float(index);
    return index >= cells ? interval.y
      : min(interval.y, interval.x + coordinate * (linearStep + quadraticStep * coordinate));
  }

  public float sampleAt(uint index, float offset = 0.5f)
  {
    return lerp(boundary(index), boundary(index + 1), offset);
  }

  public float spanAt(uint index)
  {
    return boundary(index + 1) - boundary(index);
  }
};

public CloudRayGrid createCloudRayGrid(float2 interval, float spacing, float angularFootprint, float maxCells)
{
  CloudRayGrid grid = {};
  grid.interval = interval;
  const float length = max(0.0f, interval.y - interval.x);
  const float nearSpacing = max(spacing, angularFootprint * max(interval.x, 0.0f));
  const float budget = clamp(length / max(nearSpacing, 1e-5f), 1.0f, max(maxCells, 1.0f));
  grid.cells = uint(ceil(budget));
  // Measure the polynomial in cell coordinates. Only the terminal cell is
  // clipped, so crossing an integer budget leaves the other samples continuous.
  const float inverseBudget = rcp(budget);
  const float linearLength = min(length, budget * nearSpacing);
  grid.linearStep = linearLength * inverseBudget;
  grid.quadraticStep = (length - linearLength) * inverseBudget * inverseBudget;
  return grid;
}

public CloudRayGrid createCloudViewGrid(CloudArgs args, float2 interval, float3 direction,
  float angularFootprint, uint layer)
{
  const float up = abs(dot(direction, args.up));
  const float budget = float(layer == 0 ? args.steps : args.highSteps) * lerp(1.0f, 0.5f, up);
  if (layer == 0)
  {
    const float spacing = min(1.25f * args.shapeScale / max(budget, 1.0f),
      0.125f * args.thickness / max(up, 1e-4f));
    return createCloudRayGrid(interval, spacing, angularFootprint, budget);
  }

  // The high layer has a 2D density map and an integrated height profile.
  // One cell suffices when the projected path fits one filtered texel.
  const float horizontal = sqrt(max(0.0f, 1.0f - up * up));
  const float texel = args.shapeScale * float(kCloudHighWeatherPeriod) / float(kCloudWeatherResolution);
  const float footprint = max(texel, angularFootprint * interval.x);
  return createCloudRayGrid(interval, footprint / max(horizontal, 1e-4f), angularFootprint, budget);
}

[ForceInline]
public float cloudViewSampleOffset(float3 direction)
{
  // The same ray must reuse its strata when a prefix or cached tail clips it.
  return cloudNoiseUnit(cloudNoiseHash(asuint(direction), kCloudNoiseSeed));
}

public float cloudTransmittance(CloudArgs args, float3 origin, float3 direction, float distance,
  Sampler2D<float4> weatherSampler, Sampler3D<float4> baseSampler, Sampler3D<float4> detailSampler)
{
  float transmittance = 1;
  const float viewOffset = cloudViewSampleOffset(direction);
  for (uint layer = 0; layer < 2; layer++)
  {
    if (!cloudLayerEnabled(args, layer))
    {
      continue;
    }
    // The high layer averages its height profile over a centered interval.
    const float sampleOffset = layer == 0 ? viewOffset : 0.5f;
    for (uint segment = 0; segment < 2; segment++)
    {
      const CloudRayGrid grid = createCloudViewGrid(args,
        cloudLayerInterval(args, origin, direction, cloudLayerMaxDistance(args, layer), layer, segment),
        direction, args.viewAngularFootprint, layer);
      const float end = min(grid.interval.y, distance);
      float previous = grid.interval.x;
      for (uint cell = 0; cell < grid.cells && previous < end; cell++)
      {
        const float next = min(end, grid.boundary(cell + 1));
        const float sampleDistance = grid.sampleAt(cell, sampleOffset);
        const float3 position = origin + direction * sampleDistance;
        const float footprint = cloudDensityFootprint(args, direction, grid.spanAt(cell),
          args.viewAngularFootprint * sampleDistance, layer);
        const float heightFootprint = cloudHeightFootprint(args, position, direction, grid.spanAt(cell),
          args.viewAngularFootprint * sampleDistance, layer);
        const float extinction = cloudViewExtinction(args, position, sampleDistance,
          weatherSampler, baseSampler, detailSampler, footprint, heightFootprint);
        transmittance *= exp(-extinction * (next - previous));
        if (transmittance < kOpaqueCloudTransmittance)
        {
          return transmittance;
        }
        previous = next;
      }
    }
  }
  return transmittance;
}

[ForceInline]
float cloudLightSampleDepth(CloudArgs args, uint layer, float3 position, float3 direction, float span,
  Sampler2D<float4> weatherSampler, Sampler3D<float4> baseSampler, Sampler3D<float4> detailSampler)
{
  const float heightFootprint = cloudHeightFootprint(args, position, direction, span, 0, layer);
  const float extinction = layer == 0 ? args.extinction : args.highExtinction;
  // Quadrature integrates along the ray. A cell-sized isotropic mip also
  // includes neighbouring off-ray clouds and incorrectly shadows clear gaps.
  float footprint = cloudDensityFootprint(args, direction, span, 0, layer);
  if (layer == 0)
  {
    const float nominalSupport = 0.5f * args.shapeScale / float(max(args.lightSteps, 1u));
    const float baseFootprint = min(span, args.shapeScale / 128.0f);
    // Only budget-limited tiny shapes need extra filtering. Ordinary shadow
    // samples retain the base-texel footprint and resolved narrow cloud gaps.
    footprint = sqrt(baseFootprint * baseFootprint + max(0.0f, span * span - nominalSupport * nominalSupport));
  }
  return extinction * cloudDensity(cloudDensityParameters(args), position, weatherSampler, baseSampler, detailSampler,
    footprint, layer, heightFootprint) * span;
}

// Filtering transmitted energy makes effective optical depths non-additive.
// Keep the complete beam separate from the finite-neighborhood MS inputs.
public struct CloudOpticalDepth
{
  public float local;
  public float external;
  public float total;
};

// Detailed local correction has a finite, angle-independent budget. It replaces
// the same interval in the shared prefix field instead of adding extinction twice.
float2 cloudLowNearOpticalDepth(CloudArgs args, float3 origin, float3 direction, float2 interval,
  float correctionRange, float localRange, Sampler2D<float4> weather, Sampler3D<float4> base, Sampler3D<float4> detail)
{
  const float nominalSupport = 0.5f * args.shapeScale / float(max(args.lightSteps, 1u));
  const uint samples = min(max(2u, args.lightSteps),
    max(2u, 2u * uint(ceil(correctionRange / (2.0f * nominalSupport)))));
  const float support = correctionRange / float(samples);
  float2 depth = 0;
  uint tap = uint(max(0.0f, floor(interval.x / support)));
  for (; tap < samples && float(tap) * support < interval.y; tap++)
  {
    const float begin = max(interval.x, float(tap) * support);
    const float end = min(interval.y, float(tap + 1) * support);
    const float offset = (tap & 1u) == 0 ? 0.21132486541f : 0.78867513459f;
    const float distance = (float(tap >> 1) + offset) * (2.0f * support);
    const float opticalDepth = cloudLightSampleDepth(args, 0, origin + direction * distance, direction,
      support, weather, base, detail);
    const float local = max(0.0f, min(end, localRange) - begin);
    depth += opticalDepth * float2(local, end - begin - local) / support;
  }
  return depth;
}

// Uncached non-celestial rays and positions beyond the shared domain still have
// a strict query budget. Gaussian cell footprints average unresolved density;
// this path never falls back to a kilometre-scale sequence of 32 m queries.
CloudOpticalDepth cloudLowLightFallback(CloudArgs args, float3 origin, float3 direction, float2 interval,
  float localRange, float opticalDepthLimit, Sampler2D<float4> weather,
  Sampler3D<float4> base, Sampler3D<float4> detail)
{
  const uint samples = max(4u, 2u * args.lightSteps);
  const float support = (interval.y - interval.x) / float(samples);
  float2 depth = 0;
  for (uint tap = 0; tap < samples; tap++)
  {
    const float begin = interval.x + float(tap) * support;
    const float end = begin + support;
    const float offset = (tap & 1u) == 0 ? 0.21132486541f : 0.78867513459f;
    const float distance = interval.x + (float(tap >> 1) + offset) * (2.0f * support);
    const float3 position = origin + direction * distance;
    const float heightFootprint = cloudHeightFootprint(args, position, direction, support);
    const float extinction = args.extinction * cloudDensity(cloudDensityParameters(args), position,
      weather, base, detail, support, 0, heightFootprint);
    const float local = max(0.0f, min(end, localRange) - begin);
    depth += extinction * float2(local, end - begin - local);
    if (depth.x + depth.y >= opticalDepthLimit)
    {
      break;
    }
  }
  return { depth.x, depth.y, depth.x + depth.y };
}

CloudOpticalDepth cloudLowLightCascadeDepth(CloudArgs args, float3 origin, float3 direction, float3 cacheDirection,
  float2 interval, float localRange, float opticalDepthLimit, Sampler2D<float4> weather,
  Sampler3D<float4> base, Sampler3D<float4> detail, CloudLightingCache cache, int cacheLight,
  uint cascade, CloudLightGrid grid)
{
  if (cacheLight < 0)
  {
    return cloudLowLightFallback(args, origin, direction, interval, localRange, opticalDepthLimit,
      weather, base, detail);
  }
  const float3 cacheCell = grid.cellSize(cloudLightingCascadeResolution(args, cascade));
  // A coarse table must not replace near-cloud detail within its own footprint,
  // including the wider transverse cells at the default 96 km viewing range.
  const float correctionRange = max(0.25f * args.shapeScale, max(cacheCell.x, max(cacheCell.y, cacheCell.z)));
  const float correctionEnd = clamp(correctionRange, interval.x, interval.y);
  const float split = clamp(localRange, correctionEnd, interval.y);
  CloudOpticalDepth depth = {};
  bool cached = cloudCachedLightDepthInGrid(args, cache, uint(cacheLight), grid, origin + cacheDirection * correctionEnd,
    origin + cacheDirection * interval.y, depth.total, cascade);
  if (split <= correctionEnd)
  {
    depth.external = depth.total;
  }
  else if (split >= interval.y)
  {
    depth.local = depth.total;
  }
  else
  {
    cached = cached && cloudCachedLightDepthInGrid(args, cache, uint(cacheLight), grid, origin + cacheDirection * correctionEnd,
      origin + cacheDirection * split, depth.local, cascade)
      && cloudCachedLightDepthInGrid(args, cache, uint(cacheLight), grid, origin + cacheDirection * split,
        origin + cacheDirection * interval.y, depth.external, cascade);
  }
  if (!cached)
  {
    return cloudLowLightFallback(args, origin, direction, interval, localRange, opticalDepthLimit,
      weather, base, detail);
  }
  if (correctionEnd > interval.x)
  {
    const float2 fine = cloudLowNearOpticalDepth(args, origin, direction, float2(interval.x, correctionEnd),
      correctionRange, localRange, weather, base, detail);
    depth.local += fine.x;
    depth.external += fine.y;
    depth.total += fine.x + fine.y;
  }
  return depth;
}

float cloudBlendOpticalDepth(float nearDepth, float farDepth, float weight)
{
  const float minimum = min(nearDepth, farDepth);
  return max(0.0f, minimum - log(lerp(exp(minimum - nearDepth), exp(minimum - farDepth), weight)));
}

CloudOpticalDepth cloudLowLightOpticalDepth(CloudArgs args, float3 origin, float3 direction, float3 cacheDirection,
  float2 interval, float localRange, float opticalDepthLimit, Sampler2D<float4> weather,
  Sampler3D<float4> base, Sampler3D<float4> detail, CloudLightingCache cache, int cacheLight)
{
  if (cacheLight < 0)
  {
    return cloudLowLightCascadeDepth(args, origin, direction, cacheDirection, interval, localRange,
      opticalDepthLimit, weather, base, detail, cache, cacheLight, 0, {});
  }

  // The near grid is needed for cascade containment and for near-cache sampling.
  // Share it instead of rebuilding the same light basis in both operations.
  const CloudLightGrid nearGrid = cloudLightGrid(args, uint(cacheLight), 0);
  if (!cloudLightingHasFarCascade(args))
  {
    return cloudLowLightCascadeDepth(args, origin, direction, cacheDirection, interval, localRange,
      opticalDepthLimit, weather, base, detail, cache, cacheLight, 0, nearGrid);
  }

  float weight = cloudLightingFarWeight(args, origin);
  if (!cloudLightCascadeContainsInGrid(nearGrid, origin + cacheDirection * interval.x,
      origin + cacheDirection * interval.y))
  {
    weight = 1;
  }
  if (weight >= 1)
  {
    const CloudLightGrid farGrid = cloudLightGrid(args, uint(cacheLight), 1);
    return cloudLowLightCascadeDepth(args, origin, direction, cacheDirection, interval, localRange,
      opticalDepthLimit, weather, base, detail, cache, cacheLight, 1, farGrid);
  }
  const CloudOpticalDepth nearDepth = cloudLowLightCascadeDepth(args, origin, direction, cacheDirection,
    interval, localRange, opticalDepthLimit, weather, base, detail, cache, cacheLight, 0, nearGrid);
  if (weight <= 0)
  {
    return nearDepth;
  }
  const CloudLightGrid farGrid = cloudLightGrid(args, uint(cacheLight), 1);
  const CloudOpticalDepth farDepth = cloudLowLightCascadeDepth(args, origin, direction, cacheDirection,
    interval, localRange, opticalDepthLimit, weather, base, detail, cache, cacheLight, 1, farGrid);
  return { cloudBlendOpticalDepth(nearDepth.local, farDepth.local, weight),
    cloudBlendOpticalDepth(nearDepth.external, farDepth.external, weight),
    cloudBlendOpticalDepth(nearDepth.total, farDepth.total, weight) };
}

float cloudHighLightCellDepth(CloudArgs args, float3 origin, float3 direction,
  float begin, float end, float cellSpan, Sampler2D<float4> weatherSampler,
  Sampler3D<float4> baseSampler, Sampler3D<float4> detailSampler)
{
  const float middle = (begin + end) * 0.5f;
  const float span = end - begin;
  if (cellSpan > args.shapeScale * 0.25f)
  {
    const float offset = span * 0.28867513459f;
    return cloudLightSampleDepth(args, 1, origin + direction * (middle - offset), direction, 0.5f * span,
      weatherSampler, baseSampler, detailSampler)
      + cloudLightSampleDepth(args, 1, origin + direction * (middle + offset), direction, 0.5f * span,
          weatherSampler, baseSampler, detailSampler);
  }
  return cloudLightSampleDepth(args, 1, origin + direction * middle, direction, span,
    weatherSampler, baseSampler, detailSampler);
}

// Components contain optical depth inside and outside the finite local domain.
public CloudOpticalDepth cloudLightOpticalDepth(CloudArgs args, float3 origin, float3 direction, float distance,
  Sampler2D<float4> weatherSampler, Sampler3D<float4> baseSampler, Sampler3D<float4> detailSampler,
  CloudLightingCache cache, float localRange = 0, float opticalDepthLimit = kFloatMax, int cacheLight = -1)
{
  CloudOpticalDepth opticalDepth = {};
  const int light = cacheLight >= 0 ? cacheLight : cloudCachedLightIndex(args, direction);
  const float3 cacheDirection = light >= 0
    ? args.lights[light].directionToLight * (dot(direction, args.lights[light].directionToLight) >= 0 ? 1.0f : -1.0f)
    : direction;
  const float altitude = cloudAltitude(cloudDensityParameters(args), origin);
  for (uint layer = 0; layer < 2; layer++)
  {
    if (!cloudLayerEnabled(args, layer))
    {
      continue;
    }
    const float top = cloudLayerTop(args, layer);
    const float3 intervalDirection = layer == 0 ? cacheDirection : direction;
    const float2 outer = cloudSphereRoots(args, origin, intervalDirection, top);
    float begin = max(0.0f, outer.x);
    if (altitude < cloudLayerBase(args, layer))
    {
      begin = max(begin, cloudSphereRoots(args, origin, intervalDirection, cloudLayerBase(args, layer)).y);
    }
    // Sunlit high-cloud rays remain above the dense lower atmosphere.
    const float lightRange = layer == 0 ? args.lightMaxDistance
      : 2.0f * sqrt(top * (2.0f * args.planetRadius + top));
    const float limit = layer == 0 ? min(distance, args.lightMaxDistance) : min(distance, begin + lightRange);
    const float end = min(limit, outer.y);
    if (end <= begin)
    {
      continue;
    }
    if (layer == 0)
    {
      const CloudOpticalDepth low = cloudLowLightOpticalDepth(args, origin, direction, cacheDirection,
        float2(begin, end), localRange, opticalDepthLimit - opticalDepth.total,
        weatherSampler, baseSampler, detailSampler, cache, light);
      opticalDepth.local += low.local;
      opticalDepth.external += low.external;
      opticalDepth.total += low.total;
      if (opticalDepth.total >= opticalDepthLimit)
      {
        return opticalDepth;
      }
      continue;
    }
    const float spacing = min(args.shapeScale / 48.0f,
      0.125f * args.highThickness / max(abs(dot(direction, args.up)), 1e-4f));
    const float stretch = log(1.0f + lightRange / spacing);
    const uint steps = max(4u, (args.lightSteps + 1) / 2);
    // Keep one grid through the inner clear-air gap as a ray crosses the shell's tangent.
    float previous = begin;
    for (uint i = 0; i < steps; i++)
    {
      const float boundary = begin + spacing * (exp(stretch * float(i + 1) / float(steps)) - 1.0f);
      const float next = min(end, boundary);
      const float localEnd = clamp(localRange, previous, next);
      const float cellSpan = boundary - previous;
      if (localEnd > previous)
      {
        const float depth = cloudHighLightCellDepth(args, origin, direction, previous, localEnd, cellSpan,
          weatherSampler, baseSampler, detailSampler);
        opticalDepth.local += depth;
        opticalDepth.total += depth;
      }
      if (next > localEnd)
      {
        const float depth = cloudHighLightCellDepth(args, origin, direction, localEnd, next, cellSpan,
          weatherSampler, baseSampler, detailSampler);
        opticalDepth.external += depth;
        opticalDepth.total += depth;
      }
      if (opticalDepth.total >= opticalDepthLimit)
      {
        return opticalDepth;
      }
      previous = next;
      if (previous >= end)
      {
        break;
      }
    }
  }
  return opticalDepth;
}

public float cloudVisibilityTransmittance(CloudArgs args, float3 origin, float3 direction, float distance,
  Sampler2D<float4> weatherSampler, Sampler3D<float4> baseSampler, Sampler3D<float4> detailSampler,
  CloudLightingCache cache)
{
  const CloudOpticalDepth opticalDepth = cloudLightOpticalDepth(args, origin, direction, distance,
    weatherSampler, baseSampler, detailSampler, cache, 0, -log(kOpaqueCloudTransmittance));
  return exp(-opticalDepth.total);
}

float cloudPhase(float cosine, float g)
{
  const float denominator = max(1.0f + g * g - 2.0f * g * cosine, 0.001f);
  return (1.0f - g * g) / (4.0f * 3.14159265359f * denominator * sqrt(denominator));
}

// Cloud phase is invariant along a straight view ray. Compute it once per ray
// and reuse it for each cloud cell's incident-radiance evaluation.
public struct CloudRayPhase
{
  public float2 phase;
};

public CloudRayPhase createCloudRayPhase(CloudArgs args, float3 direction)
{
  CloudRayPhase result = {};
  [unroll]
  for (uint light = 0; light < 2; light++)
  {
    if (cloudLightHasEnergy(args.lights[light]))
    {
      const float cosine = dot(direction, args.lights[light].directionToLight);
      result.phase[light] = 0.85f * cloudPhase(cosine, args.anisotropy)
        + 0.15f * cloudPhase(cosine, -0.25f);
    }
  }
  return result;
}

[ForceInline]
public float3 cloudLightIrradiance(CloudLight light, float altitude)
{
  if (light.hasRadianceProfile == 0)
  {
    return light.irradiance;
  }
  float4 lower = light.radianceProfile[0];
  [unroll]
  for (uint i = 1; i < 4; i++)
  {
    const float4 upper = light.radianceProfile[i];
    if (altitude <= upper.w)
    {
      const float fraction = saturate((altitude - lower.w) / max(upper.w - lower.w, 1e-4f));
      return lerp(lower.rgb, upper.rgb, fraction);
    }
    lower = upper;
  }
  return lower.rgb;
}

float cloudMeanCosine(CloudArgs args)
{
  return 0.85f * args.anisotropy - 0.15f * 0.25f;
}

public float2 traceCloudDirectLight(CloudArgs args, float3 position, uint lightIndex, float localBeamDepth,
  Sampler2D<float4> weather, Sampler3D<float4> base, Sampler3D<float4> detail, CloudLightingCache cache)
{
  const float opticalDepth = cloudLightOpticalDepth(args, position, args.lights[lightIndex].directionToLight, kFloatMax,
    weather, base, detail, cache, 0, localBeamDepth - log(kOpaqueCloudTransmittance), int(lightIndex)).total;
  return exp(-float2(opticalDepth, max(opticalDepth - localBeamDepth, 0.0f)));
}

public float3 cloudIncidentRadiance(CloudArgs args, float3 position, float3 direction, float distance,
  uint layer, float3 ambientRadiance,
  Sampler2D<float4> weatherSampler, Sampler3D<float4> baseSampler, Sampler3D<float4> detailSampler,
  CloudLightingCache cache, CloudRayPhase rayPhase)
{
  float3 source = 0.0f;
  const float altitude = cloudAltitude(cloudDensityParameters(args), position);
  const float2 verticalDepth = cache.local.SampleLevel(cloudLocalLightCoordinates(args, position, altitude, layer), 0);
  const float meanCosine = cloudMeanCosine(args);
  const float totalVerticalDepth = verticalDepth.x + verticalDepth.y;
  float3 groundBeamIrradianceOverPi = 0;
  const float3 radialUp = normalize(position - args.groundReference + args.planetRadius * args.up);
  float cacheWeight;
  const float4 cachedBeam = sampleCloudDirectLight(args, cache.direct, position, altitude, distance, layer, cacheWeight);
  for (uint lightIndex = 0; lightIndex < 2; lightIndex++)
  {
    const CloudLight light = args.lights[lightIndex];
    const float3 irradiance = cloudLightIrradiance(light, altitude);
    if (!any(irradiance > 0))
    {
      continue;
    }
    const float lightCosine = dot(light.directionToLight, radialUp);
    const bool groundReceivesBeam = lightCosine > 0 && args.groundAlbedo > 0;
    float2 beam = lightIndex == 0 ? cachedBeam.xy : cachedBeam.zw;
    if (cacheWeight < 1)
    {
      const float localBeamDepth = groundReceivesBeam ? verticalDepth.x / lightCosine : 0.0f;
      const float2 resolved = traceCloudDirectLight(args, position, lightIndex, localBeamDepth,
        weatherSampler, baseSampler, detailSampler, cache);
      beam = lerp(resolved, beam, cacheWeight);
    }
    const float singleScattering = rayPhase.phase[lightIndex] * beam.x;
    const float diffuse = args.multipleScattering > 0 && (args.activeCloudLights & (1u << lightIndex)) != 0
      ? cloudNeuralDiffuse(args, cache.neural, position, altitude, cloudNeuralFrame(args, direction), layer, lightIndex)
      : 0.0f;
    source += irradiance * (singleScattering + args.multipleScattering * diffuse);
    if (groundReceivesBeam)
    {
      const float direct = lightCosine * exp(-totalVerticalDepth / lightCosine) / 3.14159265359f;
      // At the black lower boundary the two-stream diffuse exit flux is
      // 4*pi*J (beam ordinates have cosine one). It includes beam collisions
      // already, so only the reflected flux returns as a boundary condition.
      const float diffuse = 4.0f * cloudDiffuseBeamRadiance(float2(totalVerticalDepth, 0), lightCosine);
      groundBeamIrradianceOverPi += light.irradiance * beam.y * (direct + diffuse);
    }
  }
  // The supplied upper-hemisphere integral is normalized by 4 pi.
  const float3 upperRadiance = 2.0f * ambientRadiance;
  const float3 lowerRadiance = cloudGroundBoundaryRadiance(upperRadiance, groundBeamIrradianceOverPi,
    totalVerticalDepth, meanCosine, args.groundAlbedo);
  return source + cloudDiffuseBoundaryRadiance(upperRadiance, lowerRadiance, verticalDepth, meanCosine);
}
