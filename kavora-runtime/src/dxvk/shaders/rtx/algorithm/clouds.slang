/* SPDX-License-Identifier: MIT */
module clouds;

import "rtx/pass/cloud_parameters.slang";
import "rtx/utility/cloud_density.slang";
import "rtx/utility/hanpi_cloud.slang";
import "rtx/algorithm/cloud_scattering.slang";
import "rtx/algorithm/cloud_lighting_cache.slang";
import "rtx/algorithm/cloud_direct_light.slang";
import "rtx/algorithm/cloud_neural_scattering.slang";
import "rtx/algorithm/cloud_source_interpolation.slang";
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

// Keep background rays on the observer's side of the planet even when air is disabled.
public float cloudHemisphereEnd(CloudArgs args, float3 origin, float3 direction)
{
  const float cosine = dot(direction, args.up);
  const float height = args.planetRadius + dot(origin - args.groundReference, args.up);
  return cosine < 0 ? max(0.0f, height / -cosine) : kFloatMax;
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

public float cloudExtinction(CloudArgs args, float3 position, Sampler2DArray<float4> weatherSampler,
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
public float cloudLayerExtinction(CloudArgs args, float3 position, uint layer,
  Sampler2DArray<float4> weatherSampler, Sampler3D<float4> baseSampler, Sampler3D<float4> detailSampler,
  float footprint, float heightFootprint)
{
  const float extinction = layer == 0 ? args.extinction : args.highExtinction;
  return extinction * cloudDensity(cloudDensityParameters(args), position, weatherSampler,
    baseSampler, detailSampler, footprint, layer, heightFootprint);
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
  if (layer == 0)
  {
    const float budget = float(args.steps);
    const float spacing = min(args.shapeScale / max(budget, 1.0f), 0.0625f * args.thickness / max(up, 1e-4f));
    return createCloudRayGrid(interval, spacing, angularFootprint, budget);
  }

  // The high layer has a 2D density map and an integrated height profile.
  // One cell suffices when the projected path fits one filtered texel.
  const float horizontal = sqrt(max(0.0f, 1.0f - up * up));
  const float toRayDistance = rcp(max(horizontal, 1e-6f));
  const float texel = args.shapeScale * float(kCloudHighWeatherPeriod) / float(kCloudHighWeatherResolution);
  return createCloudRayGrid(interval, texel * toRayDistance, angularFootprint * toRayDistance, float(args.highSteps));
}

[ForceInline]
public float cloudViewSampleOffset(float3 direction)
{
  // The same ray must reuse its strata when a prefix or cached tail clips it.
  return cloudNoiseUnit(cloudNoiseHash(asuint(direction), kCloudNoiseSeed));
}

public float cloudColumnSampleOffset(uint2 column, uint frame)
{
  // Projection jitter changes the direction bits. Keep the scramble tied to
  // the integration column so successive frames still cover different strata.
  return cloudNoiseUnit(reversebits(frame) ^ cloudNoiseHash(uint3(column, 0), kCloudNoiseSeed));
}

public interface ICloudViewIntegral
{
  [mutating] void add<let Column : bool>(CloudArgs args, float3 position, float sigma, float begin, float end);
  bool opaque();
};

// The shell owns the grid so a continuation can start at its first covered cell.
public void marchCloudView<Integral : ICloudViewIntegral>(CloudArgs args, float3 origin, float3 direction,
  uint layer, float2 interval, float2 range, float angularFootprint,
  Sampler2DArray<float4> weather, Sampler3D<float4> base, Sampler3D<float4> detail, inout Integral integral,
  float lowLayerOffset)
{
  if (range.y <= range.x)
  {
    return;
  }
  const float detailEnd = min(interval.y, cloudLayerMaxDistance(args, layer));
  const CloudRayGrid grid = createCloudViewGrid(args, float2(interval.x, max(interval.x, detailEnd)),
    direction, angularFootprint, layer);
  const float sampleOffset = layer == 0 ? lowLayerOffset : 0.5f;
  for (uint cell = range.x < detailEnd ? grid.cellAt(range.x) : grid.cells; cell < grid.cells; cell++)
  {
    const float2 bounds = float2(grid.boundary(cell), grid.boundary(cell + 1));
    const float begin = max(range.x, bounds.x);
    const float end = min(range.y, bounds.y);
    if (end <= begin)
    {
      break;
    }
    const float distance = lerp(bounds.x, bounds.y, sampleOffset);
    const float3 position = origin + direction * distance;
    const float span = bounds.y - bounds.x;
    const float cone = angularFootprint * distance;
    const float footprint = cloudDensityFootprint(args, direction, span, cone, layer);
    const float heightFootprint = cloudHeightFootprint(args, position, direction, span, cone, layer);
    const float extinction = cloudLayerExtinction(args, position, layer, weather, base, detail, footprint, heightFootprint);
    if (extinction > 0)
    {
      integral.add<false>(args, position, extinction, begin, end);
      if (integral.opaque())
      {
        return;
      }
    }
  }
  // One filtered weather cell closes the shell beyond its resolved part. Anchor it
  // to the shell rather than the surface hit so prefixes use the same coefficient.
  const float farBegin = max(interval.x, detailEnd);
  const float begin = max(range.x, farBegin);
  if (range.y > begin)
  {
    const float span = interval.y - farBegin;
    const float distance = farBegin + 0.5f * span;
    const float3 position = origin + direction * distance;
    const float cone = angularFootprint * distance;
    const float sigma = cloudLayerExtinction(args, position, layer, weather, base, detail,
      cloudDensityFootprint(args, direction, span, cone, layer),
      cloudHeightFootprint(args, position, direction, span, cone, layer));
    if (sigma > 0)
    {
      integral.add<true>(args, position, sigma, begin, range.y);
    }
  }
}

struct CloudViewTransmission : ICloudViewIntegral
{
  float value;

  [mutating] void add<let Column : bool>(CloudArgs args, float3 position, float sigma, float begin, float end)
  {
    value *= exp(-sigma * (end - begin));
  }

  bool opaque()
  {
    return value < kOpaqueCloudTransmittance;
  }
};

public float cloudTransmittance(CloudArgs args, float3 origin, float3 direction, float distance,
  Sampler2DArray<float4> weatherSampler, Sampler3D<float4> baseSampler, Sampler3D<float4> detailSampler,
  float atmosphereEnd = kFloatMax)
{
  CloudViewTransmission integral;
  integral.value = 1.0f;
  for (uint layer = 0; layer < 2; layer++)
  {
    if (!cloudLayerEnabled(args, layer))
    {
      continue;
    }
    for (uint segment = 0; segment < 2; segment++)
    {
      const float2 interval = cloudLayerInterval(args, origin, direction,
        min(atmosphereEnd, cloudHemisphereEnd(args, origin, direction)), layer, segment);
      marchCloudView(args, origin, direction, layer, interval, float2(interval.x, min(interval.y, distance)),
        args.viewAngularFootprint, weatherSampler, baseSampler, detailSampler, integral, cloudViewSampleOffset(direction));
      if (integral.opaque())
      {
        return integral.value;
      }
    }
  }
  return integral.value;
}

// Filtering transmitted energy makes effective optical depths non-additive.
// Keep the complete beam separate from the finite-neighborhood MS inputs.
public struct CloudOpticalDepth
{
  public float local;
  public float external;
  public float total;
};

// EvaluateSunLuminance's geometric cone is shared by the shadow correction
// and the direct-light bake. The directional prefix owns the far segment.
float2 integrateLowCloudShadow(CloudArgs args, float3 origin, float3 direction, float2 interval,
  float localRange, float opticalDepthLimit, uint steps,
  Sampler2DArray<float4> weather, Sampler3D<float4> base, Sampler3D<float4> detail)
{
  const float length = interval.y - interval.x;
  const float baseTexel = args.shapeScale * float(kCloudBasePeriod) / float(kCloudBaseResolution);
  // Fit the widening local cone to the fixed budget. The arithmetic series
  // spans the exact interval and cannot exhaust it after only a few taps.
  // Long uncached paths approach uniform spacing across the whole ray.
  const float tileSize = args.shapeScale * float(kCloudBasePeriod);
  const float meanStep = length / float(steps);
  float width = lerp(meanStep, min(meanStep, hanPiConeFirstStep(length, steps, args.meterToWorld)),
    min(1.0f, tileSize / length));
  const float growth = 2.0f * (meanStep - width) / float(max(1u, steps - 1u));
  float begin = interval.x;
  float2 depth = 0;
  for (uint sample = 0; sample < steps && begin < interval.y; sample++)
  {
    const float end = sample + 1u == steps ? interval.y : min(interval.y, begin + width);
    const float span = end - begin;
    const float3 position = origin + direction * (begin + 0.5f * span);
    const float progression = 3.0f * float(sample) / float(max(steps - 1u, 1u));
    // HanPi widens the body filter along the light ray but resolves erosion
    // at the quadrature point. Step length is not a footprint on the erosion map.
    const float bodyMip = min(progression, log2(max(1.0f, 0.5f * span / baseTexel)));
    const float radialSpan = cloudHeightFootprint(args, position, direction, span);
    const float extinction = args.extinction * cloudDensity(cloudDensityParameters(args), position, weather, base,
      detail, 0, 0, radialSpan, bodyMip);
    const float local = clamp(localRange - begin, 0.0f, span);
    depth += extinction * float2(local, span - local);
    if (depth.x + depth.y >= opticalDepthLimit)
    {
      break;
    }
    begin = end;
    width += growth;
  }
  return depth;
}

CloudOpticalDepth cloudLowLightFallback(CloudArgs args, float3 origin, float3 direction, float2 interval,
  float localRange, float opticalDepthLimit, Sampler2DArray<float4> weather,
  Sampler3D<float4> base, Sampler3D<float4> detail)
{
  const float2 depth = integrateLowCloudShadow(args, origin, direction, interval, localRange, opticalDepthLimit,
    max(4u, 2u * args.lightSteps), weather, base, detail);
  return { depth.x, depth.y, depth.x + depth.y };
}

CloudOpticalDepth cloudLowLightCascadeDepth(CloudArgs args, float3 origin, float3 direction, float3 cacheDirection,
  float2 interval, float localRange, float opticalDepthLimit, Sampler2DArray<float4> weather,
  Sampler3D<float4> base, Sampler3D<float4> detail, CloudLightingCache cache, int cacheLight,
  uint cascade, CloudLightGrid grid)
{
  if (cacheLight < 0)
  {
    return cloudLowLightFallback(args, origin, direction, interval, localRange, opticalDepthLimit,
      weather, base, detail);
  }
  const float3 cacheCell = grid.cellSize(cloudLightingCascadeResolution(args, cascade));
  // Resolve the first body tile at the cloud entry. Camera-to-cloud empty space
  // consumes none of the local budget. The cache takes over beyond this tile.
  const float nearSpan = max(args.shapeScale * float(kCloudBasePeriod), max(cacheCell.x, max(cacheCell.y, cacheCell.z)));
  const float correctionEnd = min(interval.x + nearSpan, interval.y);
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
    const float2 fine = integrateLowCloudShadow(args, origin, direction, float2(interval.x, correctionEnd),
      localRange, kFloatMax, max(2u, args.lightSteps), weather, base, detail);
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
  float2 interval, float localRange, float opticalDepthLimit, Sampler2DArray<float4> weather,
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

// Components contain optical depth inside and outside the finite local domain.
public CloudOpticalDepth cloudLightOpticalDepth(CloudArgs args, float3 origin, float3 direction, float distance,
  Sampler2DArray<float4> weatherSampler, Sampler3D<float4> baseSampler, Sampler3D<float4> detailSampler,
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
    const float limit = layer == 0 ? min(distance, args.lightMaxDistance)
      : min(distance, begin + kHanPiHighLightRangeMeters * args.meterToWorld);
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
    // EvaluateSunLuminanceHighCloud integrates equal segments within 3 km.
    const uint steps = max(1u, args.lightSteps);
    const float step = (end - begin) / float(steps);
    const float footprint = cloudDensityFootprint(args, direction, step, 0, layer);
    const CloudDensityArgs densityArgs = cloudDensityParameters(args);
    for (uint i = 0; i < steps; i++)
    {
      const float cellStart = begin + float(i) * step;
      const float3 position = origin + direction * (cellStart + 0.5f * step);
      const float radialFootprint = cloudHeightFootprint(args, position, direction, step, 0, layer);
      const float sigma = args.highExtinction * cloudDensity(densityArgs, position, weatherSampler,
        baseSampler, detailSampler, footprint, layer, radialFootprint);
      const float local = clamp(localRange - cellStart, 0.0f, step);
      opticalDepth.local += sigma * local;
      opticalDepth.external += sigma * (step - local);
      opticalDepth.total += sigma * step;
      if (opticalDepth.total >= opticalDepthLimit)
      {
        return opticalDepth;
      }
    }
  }
  return opticalDepth;
}

public float cloudVisibilityTransmittance(CloudArgs args, float3 origin, float3 direction, float distance,
  Sampler2DArray<float4> weatherSampler, Sampler3D<float4> baseSampler, Sampler3D<float4> detailSampler,
  CloudLightingCache cache)
{
  const CloudOpticalDepth opticalDepth = cloudLightOpticalDepth(args, origin, direction, distance,
    weatherSampler, baseSampler, detailSampler, cache, 0, -log(kOpaqueCloudTransmittance));
  return exp(-opticalDepth.total);
}

public float cloudPhase(float cosine, float g)
{
  const float denominator = max(1.0f + g * g - 2.0f * g * cosine, 0.001f);
  return (1.0f - g * g) / (4.0f * 3.14159265359f * denominator * sqrt(denominator));
}

// Cloud phase is invariant along a straight view ray. Compute it once per ray
// and reuse it for each cloud cell's incident-radiance evaluation.
public struct CloudRayPhase
{
  public float2 phase;
  public float2 highPowderWeight;
  public CloudNeuralAngle neuralAngles[2];
  public float2 viewLightCosine;
  public CloudNeuralAngle skyAngle;
};

public CloudRayPhase createCloudRayPhase(CloudArgs args, float3 direction)
{
  CloudRayPhase result = {};
  result.skyAngle = cloudSkyAngle(dot(direction, args.up));
  [unroll]
  for (uint light = 0; light < 2; light++)
  {
    if (cloudLightHasEnergy(args.lights[light]))
    {
      const float cosine = dot(direction, args.lights[light].directionToLight);
      result.viewLightCosine[light] = cosine;
      result.neuralAngles[light] = cloudNeuralAngle(cosine, dot(direction, args.up),
        dot(args.lights[light].directionToLight, args.up));
      // HanPi uses equal forward and backward lobes. Normalize their sum
      // because Kavora supplies irradiance in linear radiometric units.
      result.phase[light] = 0.5f * (cloudPhase(cosine, args.anisotropy) + cloudPhase(cosine, -0.25f));
      // Use the public HDRP volume default for the scene's powder intensity.
      result.highPowderWeight[light] = 0.25f * (1.0f - smoothstep(-0.5f, 0.5f, cosine));
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
  return 0.5f * (args.anisotropy - 0.25f);
}

// Transfer exterior skylight through the same resolved columns that shade the
// cloud itself. Air between layers sees cloud above and below its own position.
public float3 cloudDiffuseAirIllumination(CloudArgs args, Sampler3D<float2> local,
  float3 position, float3 upper)
{
  float2 depths = 0;
  const float altitude = cloudAltitude(cloudDensityParameters(args), position);
  [unroll]
  for (uint layer = 0; layer < 2; layer++)
  {
    if (cloudLayerEnabled(args, layer))
    {
      float2 column = local.SampleLevel(cloudLocalLightCoordinates(args, position, altitude, layer), 0);
      const float total = column.x + column.y;
      if (altitude <= cloudLayerBase(args, layer)) column = float2(total, 0);
      if (altitude >= cloudLayerTop(args, layer)) column = float2(0, total);
      depths += column;
    }
  }
  const float cosine = cloudMeanCosine(args);
  const float3 lower = cloudGroundBoundaryRadiance(upper, 0, depths.x + depths.y, cosine, args.groundAlbedo);
  return cloudDiffuseBoundaryRadiance(upper, lower, depths, cosine);
}

public float2 traceCloudDirectLight(CloudArgs args, float3 position, uint lightIndex, float localBeamDepth,
  Sampler2DArray<float4> weather, Sampler3D<float4> base, Sampler3D<float4> detail, CloudLightingCache cache)
{
  const float opticalDepth = cloudLightOpticalDepth(args, position, args.lights[lightIndex].directionToLight, kFloatMax,
    weather, base, detail, cache, 0, max(localBeamDepth, 0.0f) - log(kOpaqueCloudTransmittance), int(lightIndex)).total;
  return exp(-float2(opticalDepth, max(opticalDepth - localBeamDepth, 0.0f)));
}

public float3 cloudIncidentRadiance<let Column : bool = false>(CloudArgs args, float3 position, float distance,
  uint layer, float sigma, float3 ambientRadiance,
  Sampler2DArray<float4> weatherSampler, Sampler3D<float4> baseSampler, Sampler3D<float4> detailSampler,
  CloudLightingCache cache, CloudRayPhase rayPhase)
{
  float3 source = 0.0f;
  const float altitude = cloudAltitude(cloudDensityParameters(args), position);
  const float3 uv = cloudLocalLightCoordinates(args, position, altitude, layer);
  const float2 verticalDepth = cache.local.SampleLevel(uv, 0);
  const float3 neuralPosition = Column ? cloudLocalLightPosition(args, uv.xz, altitude) : position;
  const float meanCosine = cloudMeanCosine(args);
  const float totalVerticalDepth = verticalDepth.x + verticalDepth.y;
  const float powder = layer == 1 ? hanPiHighPowder(sigma / args.highExtinction) : 1.0f;
  float3 groundBeamIrradianceOverPi = 0;
  const float3 radialUp = normalize(position - args.groundReference + args.planetRadius * args.up);
  float cacheWeight = 0;
  CloudDirectLight cachedBeam = CloudDirectLight(0);
  if (!Column)
  {
    cachedBeam = sampleCloudDirectLight(args, cache.direct, position, altitude, distance, layer, cacheWeight);
  }
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
    float2 beam;
    if (Column)
    {
      const float vertical = lightCosine >= 0 ? verticalDepth.x : verticalDepth.y;
      beam = float2(exp(-vertical / max(abs(lightCosine), 1e-5f)), 1);
    }
    else
    {
      beam = cachedBeam.at(args, altitude, layer, lightIndex, lightCosine, sigma);
      if (cacheWeight < 1)
      {
        const float localBeamDepth = groundReceivesBeam ? verticalDepth.x / lightCosine : 0.0f;
        const float2 resolved = traceCloudDirectLight(args, position, lightIndex, localBeamDepth,
          weatherSampler, baseSampler, detailSampler, cache);
        beam = lerp(resolved, beam, cacheWeight);
      }
    }
    const float singleScattering = rayPhase.phase[lightIndex] * beam.x
      * lerp(1.0f, powder, rayPhase.highPowderWeight[lightIndex]);
    float diffuse = 0;
    if (args.multipleScattering > 0 && (args.activeCloudLights & (1u << lightIndex)) != 0)
    {
      const float residual = cloudNeuralDiffuse(args, cache.neural, neuralPosition, altitude, rayPhase.neuralAngles[lightIndex], layer, lightIndex);
      const float density = sigma / (layer == 0 ? args.extinction : args.highExtinction);
      diffuse = max(0.0f, residual + cloudSourceVariation(layer, density, beam.x, rayPhase.viewLightCosine[lightIndex]));
    }
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
  const float2 boundary = cloudNeuralSky(args, cache.neural, neuralPosition, altitude, rayPhase.skyAngle, layer);
  return source + upperRadiance * boundary.x + lowerRadiance * boundary.y;
}
