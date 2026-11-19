/* SPDX-License-Identifier: MIT */
module atmosphere;

import "rtx/algorithm/fog_radiance_cache.slang";
import "rtx/algorithm/fog_froxel_sampling.slang";
import "rtx/algorithm/fog_medium.slang";
import "rtx/algorithm/clouds.slang";
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

public float2 composeCloudMoments(float2 first, float2 second)
{
  return first + (1.0f - first.y) * second;
}

// The exact first extinction moment of a constant quadrature cell. Absolute ray
// distances let prefix checkpoints split cells without changing the guide.
public float2 cloudCellMoments(float extinction, float begin, float length, float maxDistance)
{
  const float opticalDepth = extinction * length;
  if (opticalDepth <= 0.0f)
  {
    return 0;
  }
  float opacity;
  float localMoment;
  if (opticalDepth < 0.1f)
  {
    opacity = opticalDepth * (1.0f - opticalDepth * (0.5f - opticalDepth * (1.0f / 6.0f - opticalDepth / 24.0f)));
    localMoment = length * opticalDepth
      * (0.5f - opticalDepth * (1.0f / 3.0f - opticalDepth * (1.0f / 8.0f - opticalDepth / 30.0f)));
  }
  else
  {
    const float transmittance = exp(-opticalDepth);
    opacity = 1.0f - transmittance;
    localMoment = opacity / extinction - length * transmittance;
  }
  return float2((begin * opacity + localMoment) / max(maxDistance, 1e-6f), opacity);
}

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
  Sampler2D<float4> weather, Sampler3D<float4> base, Sampler3D<float4> detail,
  float3 origin, float3 direction, float distance,
  float3 mediumExtinction = float3(0))
{
  return Fog.transmittance(args, origin, direction, distance)
    * airTransmittance(args.air, origin, direction, distance)
    * cloudTransmittance(args.clouds, origin, direction, distance, weather, base, detail)
    * volumeTransmittance(mediumExtinction, distance);
}

// Merge fog, cloud, air and lighting-cache boundaries.
public struct AtmosphereIntervals
{
  public float starts[7];
  public float ends[7];

  float first()
  {
    float distance = floatMax;
    [unroll]
    for (uint i = 0; i < 7; i++)
    {
      distance = min(distance, starts[i]);
    }
    return distance;
  }

  float last()
  {
    float distance = 0;
    [unroll]
    for (uint i = 0; i < 7; i++)
    {
      distance = max(distance, ends[i]);
    }
    return distance;
  }

  float next(float current)
  {
    float distance = floatMax;
    [unroll]
    for (uint i = 0; i < 7; i++)
    {
      distance = min(distance, starts[i] > current ? starts[i] : floatMax);
      distance = min(distance, ends[i] > current ? ends[i] : floatMax);
    }
    return distance;
  }
};

// A segment uses absolute distances from its original ray origin.
public struct AtmosphereRay
{
  public float3 origin;
  public float3 direction;
  public FogRayProjection fogProjection;
  public AtmosphereIntervals intervals;
  public float4 projectedPhase;
  public float3 mediumExtinction;
  public float cloudAngularFootprint;
  public AirRayPhase airPhase;
  public CloudRayPhase cloudPhase;
  public bool hasFog;
  public bool hasCloud;
  public bool hasAir;
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
  ray.mediumExtinction = mediumExtinction;
  ray.cloudAngularFootprint = max(args.clouds.viewAngularFootprint, cloudAngularFootprint);
  float2 fog;
  ray.hasFog = Fog.interval(args, origin, direction, floatMax, fog);
  ray.hasCloud = false;
  [unroll]
  for (uint i = 0; i < 4; i++)
  {
    const uint layer = i / 2;
    const float2 cloud = cloudLayerInterval(args.clouds, origin, direction, cloudLayerMaxDistance(args.clouds, layer), layer, i % 2);
    ray.intervals.starts[i + 1] = cloud.x;
    ray.intervals.ends[i + 1] = cloud.y;
    ray.hasCloud = ray.hasCloud || cloud.y > cloud.x;
  }
  if (!ray.hasFog)
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
  if (ray.hasFog)
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
  ray.intervals.starts[0] = fog.x;
  ray.intervals.ends[0] = fog.y;
  ray.intervals.starts[5] = cache.x;
  ray.intervals.ends[5] = cache.y;
  const float2 air = airInterval(args.air, origin, direction);
  ray.intervals.starts[6] = air.x;
  ray.intervals.ends[6] = air.y;
  ray.hasAir = air.y > air.x;
  ray.airPhase = {};
  ray.cloudPhase = {};
  if (ray.hasAir)
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

// Integrates a clipped range without moving the ray origin or its medium boundaries.
public AtmosphereSegment integrateAtmosphereRange<Fog : IAtmosphereFog, FogRadiance : IAtmosphereFogRadiance>(VolumeArgs args,
  FogRadiance fogRadiance,
  Sampler2D<float4> weather, Sampler3D<float4> base, Sampler3D<float4> detail, CloudLightingCache cloudLighting,
  AtmosphereRay ray, float rangeBegin, float rangeEnd, float3 cloudAmbient, AirTables airTables)
{
  AtmosphereSegment result = emptyAtmosphereSegment();
  if (!ray.hasFog && !ray.hasCloud && !ray.hasAir)
  {
    result.transmittance = volumeTransmittance(ray.mediumExtinction, max(0.0f, rangeEnd - rangeBegin));
    return result;
  }
  const float3 origin = ray.origin;
  const float3 direction = ray.direction;
  const float cloudViewOffset = ray.hasCloud ? cloudViewSampleOffset(direction) : 0.5f;
  const AirRayPhase airPhase = ray.airPhase;
  const CloudRayPhase cloudPhase = ray.cloudPhase;
  const float originDepth = ray.fogProjection.originDepth;
  const float directionDepth = ray.fogProjection.directionDepth;
  const float nearPlane = ray.fogProjection.nearPlane;
  const float2 fog = float2(ray.intervals.starts[0], ray.intervals.ends[0]);
  const float2 cache = float2(ray.intervals.starts[5], ray.intervals.ends[5]);
  const AirRayGrid airGrid = { float2(ray.intervals.starts[6], ray.intervals.ends[6]) };
  const float limit = min(rangeEnd, ray.intervals.last());
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

  for (float intervalBegin = ray.intervals.first(); intervalBegin < limit;)
  {
    const float intervalEnd = ray.intervals.next(intervalBegin);
    const float begin = max(rangeBegin, intervalBegin);
    const float end = min(limit, intervalEnd);
    if (begin >= end)
    {
      intervalBegin = intervalEnd;
      continue;
    }
    const float middle = (intervalBegin + intervalEnd) * 0.5f;
    const bool fogActive = ray.hasFog && middle > fog.x && middle < fog.y;
    const bool airActive = ray.hasAir && middle > airGrid.interval.x && middle < airGrid.interval.y;
    bool cloudActive = false;
    uint cloudLayer = 0;
    CloudRayGrid cloudGrid = {};
    [unroll]
    for (uint i = 0; i < 4; i++)
    {
      const float2 cloud = float2(ray.intervals.starts[i + 1], ray.intervals.ends[i + 1]);
      if (middle > cloud.x && middle < cloud.y)
      {
        cloudActive = true;
        cloudLayer = i / 2;
        cloudGrid = createCloudViewGrid(args.clouds, cloud, direction, ray.cloudAngularFootprint, cloudLayer);
      }
    }
    if (!fogActive && !cloudActive && !airActive)
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
    uint cloudCell = cloudActive ? cloudGrid.cellAt(begin) : 0;
    float nextCloud = cloudActive ? cloudGrid.boundary(cloudCell + 1) : floatMax;
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
    float cloudSigma = 0;
    float3 cloudSource = 0;
    bool cloudSampleValid = false;
    const float cloudSampleOffset = cloudLayer == 0 ? cloudViewOffset : 0.5f;
    AirMedium airMedium = {};
    bool airSampleValid = false;
    while (previous < end)
    {
      const float next = max(previous, min(end, min(nextAir, min(nextCloud, min(nextFog, nextCache)))));
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
        airMedium = sampleAirMedium(args.air, airTables, airPosition, airPhase);
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
      // Fog cells and prefix checkpoints retain the cloud cell's sample and footprint.
      if (cloudActive && !cloudSampleValid)
      {
        const float cloudSample = cloudGrid.sampleAt(cloudCell, cloudSampleOffset);
        const float3 cloudPosition = origin + direction * cloudSample;
        const float cloudFootprint = cloudDensityFootprint(args.clouds, direction, cloudGrid.spanAt(cloudCell),
          ray.cloudAngularFootprint * cloudSample, cloudLayer);
        cloudSigma = cloudViewExtinction(args.clouds, cloudPosition, cloudSample, weather, base, detail, cloudFootprint,
          cloudHeightFootprint(args.clouds, cloudPosition, direction, cloudGrid.spanAt(cloudCell),
            ray.cloudAngularFootprint * cloudSample, cloudLayer));
        cloudSource = cloudSigma > 0 ? cloudSigma * args.clouds.albedo
          * cloudIncidentRadiance(args.clouds, cloudPosition, direction, cloudSample, cloudLayer, cloudAmbient,
              weather, base, detail, cloudLighting, cloudPhase) : 0;
        cloudSampleValid = true;
      }
      const float3 extinction = fogMedium.extinction + cloudSigma + airExtinction + ray.mediumExtinction;
      if (cloudSigma > 0)
      {
        source += cloudSource;
        result.cloudMoments = composeCloudMoments(result.cloudMoments,
          cloudCellMoments(cloudSigma, previous, length, cloudMaxViewDistance(args.clouds)));
      }
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
      if (next >= nextCloud)
      {
        nextCloud = cloudGrid.boundary(++cloudCell + 1);
        cloudSampleValid = false;
      }
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

public AtmosphereSegment integrateAtmosphereRange<Fog : IAtmosphereFog>(VolumeArgs args,
  Sampler3D<float4> radianceY, Sampler3D<float2> radianceCoCg,
  Sampler2D<float4> weather, Sampler3D<float4> base, Sampler3D<float4> detail, CloudLightingCache cloudLighting,
  AtmosphereRay ray, float rangeBegin, float rangeEnd, float3 cloudAmbient, AirTables airTables)
{
  return integrateAtmosphereRange<Fog, FroxelFogRadiance>(args, FroxelFogRadiance(radianceY, radianceCoCg),
    weather, base, detail, cloudLighting, ray, rangeBegin, rangeEnd, cloudAmbient, airTables);
}

public AtmosphereSegment integrateAtmosphereSegment<Fog : IAtmosphereFog>(VolumeArgs args,
  Sampler3D<float4> radianceY, Sampler3D<float2> radianceCoCg,
  Sampler2D<float4> weather, Sampler3D<float4> base, Sampler3D<float4> detail, CloudLightingCache cloudLighting,
  float3 origin, float3 direction, float distance, float3 cloudAmbient, AirTables airTables,
  uint volumeHint = 0, float3 mediumExtinction = float3(0))
{
  const AtmosphereRay ray = createAtmosphereRay<Fog>(args, origin, direction, volumeHint, mediumExtinction);
  return integrateAtmosphereRange<Fog>(args, radianceY, radianceCoCg, weather, base, detail, cloudLighting, ray, 0, distance,
    cloudAmbient, airTables);
}
