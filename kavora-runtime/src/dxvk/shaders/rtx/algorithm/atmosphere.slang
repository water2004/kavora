/* SPDX-License-Identifier: MIT */
module atmosphere;

import "rtx/algorithm/fog_radiance_cache.slang";
import "rtx/algorithm/fog_froxel_sampling.slang";
import "rtx/algorithm/fog_medium.slang";
import "rtx/algorithm/clouds.slang";
import "rtx/algorithm/cloud_view_transport.slang";
import "rtx/algorithm/cloud_lighting_cache.slang";
import "rtx/algorithm/air_medium.slang";
import "rtx/pass/volume_parameters.slang";

#include "rtx/utility/volume_integral.h"

static const float floatMax = 3.402823466e+38f;

public struct AtmosphereSegment {
  public float3 radiance;
  public float3 transmittance;
  // Cloud-only extinction moments for motion guides: normalized first moment and opacity.
  public float2 cloudMoments;
};



// Store the conditional mean rather than the small raw first moment, retaining
// distance precision at thin edges. This compression affects motion guides only.
public uint packCloudMoments(float2 moments)
{
  // Continuations shift their moment to the camera origin, so the compression
  // scale is not a distance cutoff. Longer paths may have a mean above one.
  const float mean = moments.y > 0.0f ? max(0.0f, moments.x / moments.y) : 0.0f;
  return f32tof16(mean) | (f32tof16(saturate(moments.y)) << 16);
}

public float2 unpackCloudMoments(uint packed)
{
  const float opacity = f16tof32(packed >> 16);
  return float2(f16tof32(packed & 0xffff) * opacity, opacity);
}

public interface IAtmosphereFog
{
  static bool interval(VolumeArgs args, float3 origin, float3 direction, float distance, out float2 range);
  static FogMedium sampleMedium(VolumeArgs args, float3 from, float3 to, float distance);
  static float3 transmittance(VolumeArgs args, float3 origin, float3 direction, float distance);
};

public struct ClearAtmosphereFog : IAtmosphereFog
{
  public static bool interval(VolumeArgs args, float3 origin, float3 direction, float distance, out float2 range)
  {
    range = 0;
    return false;
  }

  public static FogMedium sampleMedium(VolumeArgs args, float3 from, float3 to, float distance)
  {
    return {};
  }

  public static float3 transmittance(VolumeArgs args, float3 origin, float3 direction, float distance)
  {
    return 1;
  }
}

public interface IAtmosphereFogRadiance
{
  float3 incident(VolumeArgs args, float3 position, FogRayProjection projection, float distance, float4 phase);
};

public struct FroxelFogRadiance : IAtmosphereFogRadiance
{
  public Sampler3D<float4> radianceY;
  public Sampler3D<float2> radianceCoCg;

  public __init(Sampler3D<float4> radianceY, Sampler3D<float2> radianceCoCg)
  {
    this.radianceY = radianceY;
    this.radianceCoCg = radianceCoCg;
  }

  public float3 incident(VolumeArgs args, float3 position, FogRayProjection projection, float distance, float4 phase)
  {
    return fogIncidentRadiance(args, radianceY, radianceCoCg, position, projection, distance)
      .evaluate(phase, args.multiScatteringEstimate);
  }
}

public struct ClearFogRadiance : IAtmosphereFogRadiance
{
  public float3 incident(VolumeArgs args, float3 position, FogRayProjection projection, float distance, float4 phase)
  {
    return 0;
  }
}

public float3 atmosphereTransmittance<Fog : IAtmosphereFog>(VolumeArgs args,
  Sampler2DArray<float4> weather, Sampler3D<float4> base, Sampler3D<float4> detail,
  float3 origin, float3 direction, float distance,
  float3 mediumExtinction = float3(0))
{
  return Fog.transmittance(args, origin, direction, distance)
    * airTransmittance(args.air, origin, direction, distance)
    * cloudTransmittance(args.clouds, origin, direction, distance, weather, base, detail)
    * volumeTransmittance(mediumExtinction, distance);
}

// Distances stay anchored to the original ray across checkpoints and cached tails.
public struct AtmosphereRay
{
  public float3 origin;
  public float3 direction;
  public float3 eye;
  public FogRayProjection fogProjection;
  public float2 fogInterval;
  public float2 froxelInterval;
  public float2 airInterval;
  public float2 cloudIntervals[4];
  public float4 projectedPhase;
  public float3 mediumExtinction;
  public float cloudAngularFootprint;
  public AirRayPhase airPhase;
  public CloudRayPhase cloudPhase;
  public bool hasCloud;
};

public AtmosphereSegment emptyAtmosphereSegment()
{
  AtmosphereSegment result;
  result.radiance = 0;
  result.transmittance = 1;
  result.cloudMoments = 0;
  return result;
}

public AtmosphereSegment composeAtmosphereSegments(AtmosphereSegment first, AtmosphereSegment second)
{
  AtmosphereSegment result;
  result.radiance = first.radiance + first.transmittance * second.radiance;
  result.transmittance = first.transmittance * second.transmittance;
  result.cloudMoments = composeCloudMoments(first.cloudMoments, second.cloudMoments);
  return result;
}

public AtmosphereRay createAtmosphereRay<Fog : IAtmosphereFog>(VolumeArgs args,
  float3 origin, float3 direction, uint volumeHint = 0, float3 mediumExtinction = float3(0),
  float cloudAngularFootprint = 0)
{
  AtmosphereRay ray;
  ray.origin = origin;
  ray.direction = direction;
  ray.eye = origin;
  ray.mediumExtinction = mediumExtinction;
  ray.cloudAngularFootprint = max(args.clouds.viewAngularFootprint, cloudAngularFootprint);
  float2 fog;
  const bool hasFog = Fog.interval(args, origin, direction, floatMax, fog);
  ray.hasCloud = false;
  [unroll]
  for (uint i = 0; i < 4; i++)
  {
    const uint layer = i / 2;
    const float2 cloud = cloudLayerInterval(args.clouds, origin, direction, cloudLayerMaxDistance(args.clouds, layer), layer, i % 2);
    ray.cloudIntervals[i] = cloud;
    ray.hasCloud = ray.hasCloud || cloud.y > cloud.x;
  }
  if (!hasFog)
  {
    fog = 0;
  }

  ray.fogProjection = {};
  if (args.numActiveFroxelVolumes > 0)
  {
    ray.fogProjection = createFogRayProjection(args, origin, direction, volumeHint);
  }
  const float originDepth = ray.fogProjection.originDepth;
  const float directionDepth = ray.fogProjection.directionDepth;
  const float nearPlane = ray.fogProjection.nearPlane;
  float2 cache = fog.xx;
  if (hasFog)
  {
    if (abs(directionDepth) > 1e-6f)
    {
      const float nearT = (nearPlane - originDepth) / directionDepth;
      const float farT = (nearPlane + args.froxelMaxDistance - originDepth) / directionDepth;
      cache = clamp(float2(min(nearT, farT), max(nearT, farT)), fog.x, fog.y);
    }
    else if (originDepth >= nearPlane && originDepth <= nearPlane + args.froxelMaxDistance)
    {
      cache = fog;
    }
  }
  ray.fogInterval = fog;
  ray.froxelInterval = cache;
  ray.airInterval = airInterval(args.air, origin, direction);
  ray.airPhase = {};
  ray.cloudPhase = {};
  if (ray.airInterval.y > ray.airInterval.x)
  {
    ray.airPhase = createAirRayPhase(args.air, direction);
  }
  if (ray.hasCloud)
  {
    ray.cloudPhase = createCloudRayPhase(args.clouds, direction);
  }
  ray.projectedPhase = projectFogPhase(args.volumetricFogAnisotropy, direction);
  return ray;
}

// Air and froxel fog are a continuous background. Cloud samples never split
// the air grid, so their count cannot multiply Chapman or scattering lookups.
AtmosphereSegment integrateHazeRange<Fog : IAtmosphereFog, FogRadiance : IAtmosphereFogRadiance>(VolumeArgs args,
  FogRadiance fogRadiance, AtmosphereRay ray, float rangeBegin, float rangeEnd, AirTables airTables)
{
  AtmosphereSegment result = emptyAtmosphereSegment();
  const float3 origin = ray.origin;
  const float3 direction = ray.direction;
  const float originDepth = ray.fogProjection.originDepth;
  const float directionDepth = ray.fogProjection.directionDepth;
  const float nearPlane = ray.fogProjection.nearPlane;
  const float2 fog = ray.fogInterval;
  const float2 cache = ray.froxelInterval;
  const AirRayGrid airGrid = { ray.airInterval };
  const float limit = min(rangeEnd, max(fog.y, airGrid.interval.y));
  AirOpticalDepthRay airOptics = {};
  float3 previousAirDepth = 0;
  const float airEnd = min(limit, airGrid.interval.y);
  if (airEnd > max(rangeBegin, airGrid.interval.x))
  {
    airOptics = createAirOpticalDepthRay(args.air, origin, direction, float2(airGrid.interval.x, airEnd));
    if (rangeBegin > airGrid.interval.x)
    {
      previousAirDepth = airOptics.prefix(args.air, direction, rangeBegin);
    }
  }
  float previousEnd = rangeBegin;

  const float boundaries[6] = { fog.x, fog.y, cache.x, cache.y, airGrid.interval.x, airGrid.interval.y };
  for (float intervalBegin = min(fog.x, airGrid.interval.x); intervalBegin < limit;)
  {
    float intervalEnd = floatMax;
    [unroll]
    for (uint boundary = 0; boundary < 6; boundary++)
    {
      if (boundaries[boundary] > intervalBegin)
      {
        intervalEnd = min(intervalEnd, boundaries[boundary]);
      }
    }
    const float begin = max(rangeBegin, intervalBegin);
    const float end = min(limit, intervalEnd);
    if (begin >= end)
    {
      intervalBegin = intervalEnd;
      continue;
    }
    const float middle = (intervalBegin + intervalEnd) * 0.5f;
    const bool fogActive = middle > fog.x && middle < fog.y;
    const bool airActive = middle > airGrid.interval.x && middle < airGrid.interval.y;
    if (!fogActive && !airActive)
    {
      intervalBegin = intervalEnd;
      continue;
    }
    result.transmittance *= volumeTransmittance(ray.mediumExtinction, max(0.0f, begin - previousEnd));
    const bool cacheActive = fogActive && middle > cache.x && middle < cache.y;
    const bool alignedToCache = cacheActive && abs(directionDepth) > 1e-6f;
    const float fogSpacing = fogActive && !alignedToCache ? (intervalEnd - intervalBegin) / 8.0f : floatMax;
    uint fogCell = uint(floor((begin - intervalBegin) / fogSpacing)) + 1;
    float nextFog = fogSpacing < floatMax ? intervalBegin + float(fogCell) * fogSpacing : floatMax;
    uint airCell = airActive ? airGrid.cellAt(begin) : 0;
    float nextAir = airActive ? airGrid.boundary(airCell + 1) : floatMax;
    const float sliceRate = float(args.froxelDepthSlices) * 2.0f;
    const float sliceDirection = directionDepth > 0 ? 1.0f : -1.0f;
    float sliceBoundary = 0;
    float nextCache = floatMax;
    if (alignedToCache)
    {
      const float beginW = pow(saturate((originDepth + directionDepth * begin - nearPlane) / args.froxelMaxDistance),
        1.0f / args.froxelDepthSliceDistributionExponent);
      sliceBoundary = (directionDepth > 0 ? floor(beginW * sliceRate) : ceil(beginW * sliceRate)) + sliceDirection;
      const float depth = nearPlane + pow(saturate(sliceBoundary / sliceRate),
        args.froxelDepthSliceDistributionExponent) * args.froxelMaxDistance;
      nextCache = max(begin, (depth - originDepth) / directionDepth);
    }
    float previous = begin;
    AirMedium airMedium = {};
    bool airSampleValid = false;
    while (previous < end)
    {
      const float next = max(previous, min(end, min(nextAir, min(nextFog, nextCache))));
      const float length = next - previous;
      const float3 from = origin + direction * previous;
      const float3 to = origin + direction * next;
      const float3 position = origin + direction * ((previous + next) * 0.5f);
      FogMedium fogMedium;
      fogMedium.extinction = 0;
      fogMedium.scattering = 0;
      if (fogActive)
      {
        fogMedium = Fog.sampleMedium(args, from, to, length);
      }
      float3 source = 0;
      if (any(fogMedium.scattering > 0))
      {
        source = fogMedium.scattering
          * fogRadiance.incident(args, position, ray.fogProjection, (previous + next) * 0.5f, ray.projectedPhase);
      }
      if (airActive && !airSampleValid)
      {
        const float3 airPosition = origin + direction * airGrid.sampleAt(airCell);
        airMedium = sampleAirMedium(args.air, airTables, airPosition, ray.airPhase);
        airMedium.source /= max(airMedium.extinction, 1e-20f);
        airSampleValid = true;
      }
      float3 airExtinction = 0;
      if (airActive && length > 0)
      {
        const float3 opticalDepth = airOptics.prefix(args.air, direction, next);
        airExtinction = max(0.0f, opticalDepth - previousAirDepth) / length;
        previousAirDepth = opticalDepth;
        source += airMedium.source * airExtinction;
      }
      const float3 extinction = fogMedium.extinction + airExtinction + ray.mediumExtinction;
      const float3 stepTransmittance = exp(-extinction * length);
      const float3 integral = float3(volumeSourceIntegral(extinction.x, length, stepTransmittance.x),
        volumeSourceIntegral(extinction.y, length, stepTransmittance.y),
        volumeSourceIntegral(extinction.z, length, stepTransmittance.z));
      result.radiance += result.transmittance * source * integral;
      result.transmittance *= stepTransmittance;
      if (all(result.transmittance < kOpaqueCloudTransmittance))
      {
        return result;
      }
      previous = next;
      if (next >= nextAir)
      {
        nextAir = airGrid.boundary(++airCell + 1);
        airSampleValid = false;
      }
      if (next >= nextFog)
      {
        nextFog = intervalBegin + float(++fogCell) * fogSpacing;
      }
      if (next >= nextCache)
      {
        sliceBoundary += sliceDirection;
        const float depth = nearPlane + pow(saturate(sliceBoundary / sliceRate),
          args.froxelDepthSliceDistributionExponent) * args.froxelMaxDistance;
        nextCache = sliceBoundary > 0 && sliceBoundary < sliceRate
          ? max(next, (depth - originDepth) / directionDepth) : floatMax;
      }
    }
    previousEnd = end;
    intervalBegin = intervalEnd;
  }
  result.transmittance *= volumeTransmittance(ray.mediumExtinction, max(0.0f, rangeEnd - previousEnd));
  return result;
}

public AtmosphereSegment integrateAtmosphereRange<Fog : IAtmosphereFog, FogRadiance : IAtmosphereFogRadiance>(VolumeArgs args,
  FogRadiance fogRadiance,
  Sampler2DArray<float4> weather, Sampler3D<float4> base, Sampler3D<float4> detail, CloudLightingCache cloudLighting,
  AtmosphereRay ray, float rangeBegin, float rangeEnd, float3 cloudAmbient, AirTables airTables)
{
  AtmosphereSegment result = emptyAtmosphereSegment();
  float previous = rangeBegin;
  float nextCloudStart = rangeBegin;
  const float distanceScale = cloudMaxViewDistance(args.clouds);
  // The cloud shells do not overlap. Compose the aerial perspective through
  // each cloud at its extinction centroid and preserve the cloud motion moments.
  while (previous < rangeEnd)
  {
    uint nearest = 4;
    float nearestStart = rangeEnd;
    if (ray.hasCloud)
    {
      [unroll]
      for (uint i = 0; i < 4; i++)
      {
        const float2 interval = ray.cloudIntervals[i];
        const float begin = max(rangeBegin, interval.x);
        if (begin >= nextCloudStart && begin < nearestStart && interval.y > begin)
        {
          nearest = i;
          nearestStart = begin;
        }
      }
    }
    AtmosphereSegment clouds = emptyAtmosphereSegment();
    float depth = rangeEnd;
    if (nearest < 4)
    {
      const float2 interval = ray.cloudIntervals[nearest];
      const float end = min(rangeEnd, interval.y);
      nextCloudStart = end;
      const uint layer = nearest / 2;
      CloudViewTransfer cloud = CloudViewTransfer(ray.eye, layer, cloudAmbient,
        cloudLighting, ray.cloudPhase, weather, base, detail);
      marchCloudView(args.clouds, ray.origin, ray.direction, layer, interval, float2(nearestStart, end),
        ray.cloudAngularFootprint, weather, base, detail, cloud);
      if (cloud.moments.y == 0)
      {
        continue;
      }
      depth = clamp(distanceScale * cloud.moments.x / cloud.moments.y, nearestStart, end);
      clouds = { cloud.radiance, cloud.transmission.xxx, cloud.moments };
    }
    result = composeAtmosphereSegments(result,
      integrateHazeRange<Fog, FogRadiance>(args, fogRadiance, ray, previous, depth, airTables));
    result = composeAtmosphereSegments(result, clouds);
    if (all(result.transmittance < kOpaqueCloudTransmittance))
    {
      break;
    }
    previous = depth;
  }
  return result;
}

public AtmosphereSegment integrateAtmosphereRange<Fog : IAtmosphereFog>(VolumeArgs args,
  Sampler3D<float4> radianceY, Sampler3D<float2> radianceCoCg,
  Sampler2DArray<float4> weather, Sampler3D<float4> base, Sampler3D<float4> detail, CloudLightingCache cloudLighting,
  AtmosphereRay ray, float rangeBegin, float rangeEnd, float3 cloudAmbient, AirTables airTables)
{
  return integrateAtmosphereRange<Fog, FroxelFogRadiance>(args, FroxelFogRadiance(radianceY, radianceCoCg),
    weather, base, detail, cloudLighting, ray, rangeBegin, rangeEnd, cloudAmbient, airTables);
}

public AtmosphereSegment integrateAtmosphereSegment<Fog : IAtmosphereFog>(VolumeArgs args,
  Sampler3D<float4> radianceY, Sampler3D<float2> radianceCoCg,
  Sampler2DArray<float4> weather, Sampler3D<float4> base, Sampler3D<float4> detail, CloudLightingCache cloudLighting,
  float3 origin, float3 direction, float distance, float3 cloudAmbient, AirTables airTables,
  uint volumeHint = 0, float3 mediumExtinction = float3(0))
{
  const AtmosphereRay ray = createAtmosphereRay<Fog>(args, origin, direction, volumeHint, mediumExtinction);
  return integrateAtmosphereRange<Fog>(args, radianceY, radianceCoCg, weather, base, detail, cloudLighting, ray, 0, distance,
    cloudAmbient, airTables);
}
