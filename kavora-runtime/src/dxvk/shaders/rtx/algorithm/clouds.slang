/* SPDX-License-Identifier: MIT */
module clouds;

import "rtx/pass/cloud_parameters.slang";
import "rtx/utility/cloud_density.slang";
import "rtx/utility/cloud_phase.slang";
import "rtx/algorithm/cloud_scattering.slang";
import "rtx/algorithm/cloud_lighting_cache.slang";
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
  density.wetness = args.wetness;
  density.highMapOrigin = args.highMapOrigin;
  density.highLookupOrigin = args.highLookupOrigin;
  density.highBaseHeight = args.highBaseHeight;
  density.highThickness = args.highThickness;
  density.highCoverage = args.highCoverage;
  return density;
}

public float2 cloudSphereRoots(CloudArgs args, float3 origin, float3 direction, float altitude)
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
float4 cloudLayerIntervals(CloudArgs args, float3 origin, float3 direction, float distance, uint layer)
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
    return float4(begin, end, 0, 0);
  }
  if (inner.x <= begin)
  {
    const float entry = max(begin, inner.y);
    return end > entry ? float4(entry, end, 0, 0) : float4(0);
  }
  const float2 second = end > inner.y ? float2(inner.y, end) : float2(0);
  return float4(begin, inner.x, second);
}

public float2 cloudLayerInterval(CloudArgs args, float3 origin, float3 direction, float distance,
  uint layer, uint segment = 0)
{
  const float4 intervals = cloudLayerIntervals(args, origin, direction, distance, layer);
  return segment == 0 ? intervals.xy : intervals.zw;
}

[ForceInline]
public uint cloudLayerAt(CloudArgs args, float3 position)
{
  return args.highExtinction > 0 && args.highThickness > 0
    && cloudAltitude(cloudDensityParameters(args), position) >= args.highBaseHeight ? 1u : 0u;
}

public float cloudExtinction(CloudArgs args, float3 position, Sampler2DArray<float4> weatherSampler,
  Sampler3D<float4> baseSampler, Sampler3D<float4> detailSampler)
{
  const uint layer = cloudLayerAt(args, position);
  const float extinction = layer == 0 ? args.extinction : args.highExtinction;
  return extinction * cloudDensity(cloudDensityParameters(args), position, weatherSampler,
    baseSampler, detailSampler, layer);
}

[ForceInline]
public float cloudLayerExtinction(CloudArgs args, float3 position, uint layer,
  Sampler2DArray<float4> weatherSampler, Sampler3D<float4> baseSampler, Sampler3D<float4> detailSampler)
{
  const float extinction = layer == 0 ? args.extinction : args.highExtinction;
  return extinction * cloudDensity(cloudDensityParameters(args), position, weatherSampler,
    baseSampler, detailSampler, layer);
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

  // The high layer has horizontal density and constant vertical support.
  const float horizontal = sqrt(max(0.0f, 1.0f - up * up));
  const float toRayDistance = rcp(max(horizontal, 1e-6f));
  const float texel = args.shapeScale * float(kCloudCirrusPeriod) / float(kCloudCirrusResolution);
  return createCloudRayGrid(interval, texel * toRayDistance, angularFootprint * toRayDistance, float(args.highSteps));
}

[ForceInline]
public float cloudViewSampleOffset(float3 direction)
{
  // The same ray must reuse its strata when a prefix or cached tail clips it.
  return cloudNoiseUnit(cloudNoiseHash(asuint(direction), kCloudNoiseSeed));
}

public float cloudPixelSampleOffset(uint2 pixel, uint frame)
{
  return cloudNoiseUnit(reversebits(frame) ^ cloudNoiseHash(uint3(pixel, 0), kCloudNoiseSeed));
}

public interface ICloudViewIntegral
{
  [mutating] void add<let Column : bool>(CloudArgs args, float3 position, float sigma, float begin, float end);
  bool opaque();
};

void marchCloudGrid<Integral : ICloudViewIntegral, let Column : bool>(CloudArgs args, float3 origin, float3 direction,
  uint layer, CloudRayGrid grid, float2 range, float angularFootprint, float offset,
  Sampler2DArray<float4> weather, Sampler3D<float4> base, Sampler3D<float4> detail, inout Integral integral)
{
  if (range.y <= grid.interval.x || range.x >= grid.interval.y)
  {
    return;
  }
  const CloudDensityArgs density = cloudDensityParameters(args);
  const float extinction = layer == 0 ? args.extinction : args.highExtinction;
  const float horizontal = length(cross(direction, args.up));
  for (uint cell = grid.cellAt(range.x); cell < grid.cells; cell++)
  {
    const float2 bounds = float2(grid.boundary(cell), grid.boundary(cell + 1));
    const float begin = max(range.x, bounds.x);
    const float end = min(range.y, bounds.y);
    if (end <= begin)
    {
      break;
    }
    const float span = bounds.y - bounds.x;
    // A far cell covers a long chord. Integrate its density at Gauss nodes,
    // then shade once at the density centroid. Reusing the full-cell rule for
    // clipped ranges keeps separate prefix queries consistent.
    float mass = 0;
    float moment = 0;
    [unroll]
    for (uint i = 0; i < (Column ? 4u : 1u); ++i)
    {
      const float4 nodes = float4(0.069431844f, 0.330009478f, 0.669990522f, 0.930568156f);
      const float4 weights = float4(0.173927423f, 0.326072577f, 0.326072577f, 0.173927423f);
      const float fraction = Column ? nodes[i] : offset;
      const float weight = Column ? weights[i] : 1.0f;
      const float distance = bounds.x + fraction * span;
      const float cone = angularFootprint * distance;
      const float footprint = max(cone, 0.5f * weight * span * (layer == 0 ? 1.0f : horizontal));
      const float weighted = weight * cloudDensity(density, origin + direction * distance, weather, base, detail, layer, true, footprint);
      mass += weighted;
      moment += weighted * fraction;
    }
    if (mass > 0)
    {
      const float distance = bounds.x + span * (Column ? moment / mass : offset);
      integral.add<Column>(args, origin + direction * distance, extinction * mass, begin, end);
      if (integral.opaque())
      {
        return;
      }
    }
  }
}

// Both grids belong to the full shell arc. Endpoints clip cells without moving their samples.
public void marchCloudView<Integral : ICloudViewIntegral>(CloudArgs args, float3 origin, float3 direction,
  uint layer, float2 interval, float2 range, float angularFootprint,
  Sampler2DArray<float4> weather, Sampler3D<float4> base, Sampler3D<float4> detail, inout Integral integral,
  float lowLayerOffset)
{
  if (range.y <= range.x)
  {
    return;
  }
  const float split = clamp(cloudLayerMaxDistance(args, layer), interval.x, interval.y);
  if (range.x < split)
  {
    const CloudRayGrid grid = createCloudViewGrid(args, float2(interval.x, split), direction, angularFootprint, layer);
    marchCloudGrid<Integral, false>(args, origin, direction, layer, grid, range, angularFootprint,
      layer == 0 ? lowLayerOffset : 0.5f, weather, base, detail, integral);
  }
  if (range.y > split && !integral.opaque())
  {
    const CloudRayGrid grid = createCloudRayGrid(float2(split, interval.y), 0.5f * args.shapeScale, 0, 12);
    marchCloudGrid<Integral, true>(args, origin, direction, layer, grid, range, angularFootprint,
      0.5f, weather, base, detail, integral);
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

// Each component is the optical depth of one deck. Both pieces of a
// grazing intersection share one budget and skip the gap in the inner shell.
public float2 cloudLightOpticalDepth(CloudArgs args, float3 origin, float3 direction, float distance,
  Sampler2DArray<float4> weather, Sampler3D<float4> base, Sampler3D<float4> detail)
{
  if (!args.enable)
  {
    return 0;
  }
  const float3 radialUp = normalize(origin - args.groundReference + args.planetRadius * args.up);
  const float inverseCosine = rcp(max(dot(direction, radialUp), 0.5f));
  const float rayEnd = min(distance, cloudHemisphereEnd(args, origin, direction));
  const CloudDensityArgs density = cloudDensityParameters(args);
  float2 depth = 0;
  [unroll]
  for (uint layer = 0; layer < 2; ++layer)
  {
    const float4 intervals = cloudLayerIntervals(args, origin, direction, rayEnd, layer);
    if (intervals.y <= intervals.x)
    {
      continue;
    }
    const float thickness = layer == 0 ? args.thickness : args.highThickness;
    const float maxLength = thickness * inverseCosine;
    const float firstLength = min(intervals.y - intervals.x, maxLength);
    const float length = min(firstLength + (intervals.w - intervals.z), maxLength);
    const uint steps = layer == 0 ? clamp(args.lightSteps, 1u, 6u) : 3u;
    const float stepLength = length / float(steps * steps);
    const float extinction = layer == 0 ? args.extinction : args.highExtinction;
    for (uint tap = 0; tap < steps; ++tap)
    {
      // Revelation's (i + noise)^2 location with the quadratic cell width.
      const float index = float(tap) + 0.5f;
      const float center = index * index * stepLength;
      const float rayT = center < firstLength ? intervals.x + center : intervals.z + center - firstLength;
      const float3 position = origin + direction * rayT;
      const float value = cloudDensity(density, position, weather, base, detail, layer, tap < 3);
      depth[layer] += extinction * (2.0f * stepLength * index) * value;
      if (depth[layer] >= 24.0f)
      {
        break;
      }
    }
  }
  return depth;
}

public float cloudVisibilityTransmittance(CloudArgs args, float3 origin, float3 direction, float distance,
  Sampler2DArray<float4> weather, Sampler3D<float4> base, Sampler3D<float4> detail)
{
  const float2 depth = cloudLightOpticalDepth(args, origin, direction, distance, weather, base, detail);
  return exp(-(depth.x + depth.y));
}

// Cloud phase is invariant along a straight view ray. Compute it once per ray
// and reuse it for each cloud cell's incident-radiance evaluation.
public struct CloudRayPhase
{
  public float3 viewDirection;
  public float2 phase[2];
  public CloudNeuralAngle neuralAngles[2];
  public CloudNeuralAngle skyAngle;
};

public CloudRayPhase createCloudRayPhase(CloudArgs args, float3 direction, Sampler2D<float> phaseTable)
{
  CloudRayPhase result = {};
  result.viewDirection = direction;
  result.skyAngle = cloudSkyAngle(dot(direction, args.up));
  [unroll]
  for (uint light = 0; light < 2; light++)
  {
    if (cloudLightHasEnergy(args.lights[light]))
    {
      const float cosine = dot(direction, args.lights[light].directionToLight);
      result.neuralAngles[light] = cloudNeuralAngle(cosine, dot(direction, args.up),
        dot(args.lights[light].directionToLight, args.up));
      result.phase[light] = cloudDiskPhase(phaseTable, cosine);
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
      depths += column * (1.0f - cloudMeanCosine(layer));
    }
  }
  const float3 lower = cloudGroundBoundaryRadiance(upper, 0, depths.x + depths.y, 0, args.groundAlbedo);
  return cloudDiffuseBoundaryRadiance(upper, lower, depths, 0);
}

// The forward optical depth describes the material available for subsequent
// collisions. Use the same three bounded quadrature nodes as the trainer.
public float cloudInwardDepth(CloudArgs args, float3 position, float altitude, float3 view, float viewUp, uint layer,
  Sampler2DArray<float4> weather, Sampler3D<float4> base, Sampler3D<float4> detail)
{
  const float bottom = cloudLayerBase(args, layer);
  const float top = cloudLayerTop(args, layer);
  const float vertical = viewUp >= 0 ? top - altitude : altitude - bottom;
  const float span = min(max(0.0f, vertical) / max(abs(viewUp), 1e-10f), (top - bottom) / max(abs(viewUp), 0.5f));
  const float step = span / 9.0f;
  const CloudDensityArgs density = cloudDensityParameters(args);
  float sum = 0;
  [unroll]
  for (uint i = 0; i < 3; ++i)
  {
    const float t = float(i) + 0.5f;
    sum += (2.0f * t) * cloudDensity(density, position + view * (t * t * step), weather, base, detail, layer);
  }
  return step * sum * (layer == 0 ? args.extinction : args.highExtinction);
}

public float cloudSourceColumnDepth(CloudArgs args, float3 position, float altitude, uint layer, float cached,
  Sampler2DArray<float4> weather, Sampler3D<float4> base, Sampler3D<float4> detail)
{
  const float edge = cloudLocalColumnExteriorWeight(args, position, layer);
  if (edge == 0)
    return cached;

  // Beyond the last column center, resolve the finite shell at the interaction.
  const float thickness = cloudLayerTop(args, layer) - cloudLayerBase(args, layer);
  const float spacing = thickness / 4.0f;
  const float3 up = normalize(position - args.groundReference + args.planetRadius * args.up);
  const CloudDensityArgs density = cloudDensityParameters(args);
  float sum = 0;
  [unroll]
  for (uint i = 0; i < 4; ++i)
  {
    const float h = cloudLayerBase(args, layer) + (float(i) + 0.5f) * spacing;
    sum += cloudDensity(density, position + up * (h - altitude), weather, base, detail, layer, true, 0.5f * spacing);
  }
  return lerp(cached, sum * spacing * (layer == 0 ? args.extinction : args.highExtinction), edge);
}

public float3 cloudIncidentRadiance<let Column : bool = false>(CloudArgs args, float3 position,
  uint layer, float3 ambientRadiance,
  Sampler2DArray<float4> weatherSampler, Sampler3D<float4> baseSampler, Sampler3D<float4> detailSampler,
  CloudLightingCache cache, CloudRayPhase rayPhase)
{
  float3 source = 0.0f;
  const float altitude = cloudAltitude(cloudDensityParameters(args), position);
  const float3 uv = cloudLocalLightCoordinates(args, position, altitude, layer);
  const float2 verticalDepth = cache.local.SampleLevel(uv, 0);
  const float3 neuralPosition = Column ? cloudLocalLightPosition(args, uv.xz, altitude, layer) : position;
  const float meanCosine = cloudMeanCosine(layer);
  const float totalVerticalDepth = cloudSourceColumnDepth(args, position, altitude, layer, verticalDepth.x + verticalDepth.y,
    weatherSampler, baseSampler, detailSampler);
  float3 groundBeamIrradianceOverPi = 0;
  // The supplied upper-hemisphere integral is normalized by 4 pi.
  float3 upperRadiance = 2.0f * ambientRadiance;
  const bool illuminatedByUpperDeck = layer == 0 && cloudLayerEnabled(args, 1) && args.multipleScattering > 0;
  float2 upperDeckFlux = 0;
  if (illuminatedByUpperDeck)
    upperDeckFlux = cache.local.SampleLevel(cloudBoundaryFluxCoordinates(uv.xz), 0);
  const float3 radialUp = normalize(position - args.groundReference + args.planetRadius * args.up);
  const float inwardDepth = args.activeCloudLights != 0 && args.multipleScattering > 0
    ? cloudInwardDepth(args, position, altitude, rayPhase.viewDirection, dot(rayPhase.viewDirection, radialUp),
        layer, weatherSampler, baseSampler, detailSampler) : 0;
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
    const float2 depth = cloudLightOpticalDepth(args, position, light.directionToLight, kFloatMax,
      weatherSampler, baseSampler, detailSampler);
    const float beam = exp(-depth[layer]);
    float otherLayerTransmission = exp(-depth[1u - layer]);
    if (illuminatedByUpperDeck && lightCosine > 0)
    {
      // The upper deck illuminates this deck through both its forward beam
      // and its diffuse lower boundary. The short ray already measured its
      // slant depth; use the equivalent vertical slab at this light angle.
      const float g = cloudMeanCosine(1);
      const float forwardBeam = exp(-depth.y * (1.0f - g * g));
      upperRadiance += irradiance * (args.multipleScattering * upperDeckFlux[lightIndex] / 3.14159265359f);
      otherLayerTransmission = lerp(otherLayerTransmission, forwardBeam, args.multipleScattering);
    }
    const float singleScattering = rayPhase.phase[lightIndex][layer] * beam;
    float diffuse = 0;
    if (args.multipleScattering > 0 && (args.activeCloudLights & (1u << lightIndex)) != 0)
    {
      diffuse = cloudNeuralDiffuse(cache.neural, float3(depth[layer], inwardDepth, totalVerticalDepth),
        rayPhase.neuralAngles[lightIndex], layer, lightIndex);
    }
    source += (irradiance * otherLayerTransmission) * (singleScattering + args.multipleScattering * diffuse);
    if (groundReceivesBeam)
    {
      const float flux = cloudBeamTransfer(totalVerticalDepth, meanCosine, lightCosine).downwardFlux;
      groundBeamIrradianceOverPi += (light.irradiance * otherLayerTransmission) * (flux / 3.14159265359f);
    }
  }
  const float3 lowerRadiance = cloudGroundBoundaryRadiance(upperRadiance, groundBeamIrradianceOverPi,
    totalVerticalDepth, meanCosine, args.groundAlbedo);
  const float2 boundary = cloudNeuralSky(args, cache.neural, neuralPosition, altitude, rayPhase.skyAngle, layer);
  return source + upperRadiance * boundary.x + lowerRadiance * boundary.y;
}
