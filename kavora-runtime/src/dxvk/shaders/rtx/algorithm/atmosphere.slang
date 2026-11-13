/* SPDX-License-Identifier: MIT */
module atmosphere;

import "rtx/algorithm/fog_radiance_cache.slang";
import "rtx/algorithm/fog_froxel_sampling.slang";
import "rtx/algorithm/fog_medium.slang";
import "rtx/algorithm/clouds.slang";
import "rtx/pass/volume_parameters.slang";

#include "rtx/utility/volume_integral.h"

static const float floatMax = 3.402823466e+38f;

public struct AtmosphereSegment {
  public float3 radiance;
  public float3 transmittance;
};

public interface IAtmosphereFog
{
  static bool interval(VolumeArgs args, float3 origin, float3 direction, float distance, out float2 range);
  static FogMedium sampleMedium(VolumeArgs args, float3 from, float3 to, float distance);
  static float3 transmittance(VolumeArgs args, float3 origin, float3 direction, float distance);
};

public float3 atmosphereTransmittance<Fog : IAtmosphereFog>(VolumeArgs args,
  Sampler2D<float4> weather, Sampler3D<float4> base, Sampler3D<float4> detail,
  float3 origin, float3 direction, float distance,
  float3 mediumExtinction = float3(0))
{
  return Fog.transmittance(args, origin, direction, distance)
    * (args.clouds.transportMode == 0 ? cloudTransmittance(args.clouds, origin, direction, distance,
      weather, base, detail) : 1.0f)
    * volumeTransmittance(mediumExtinction, distance);
}

// Merge fog, cloud and lighting-cache boundaries.
public struct AtmosphereIntervals
{
  public float3 starts;
  public float3 ends;

  float first() { return min(min(starts.x, starts.y), starts.z); }
  float last() { return max(max(ends.x, ends.y), ends.z); }

  float next(float current)
  {
    float3 a = select(starts > current, starts, floatMax);
    float3 b = select(ends > current, ends, floatMax);
    float3 nearest = min(a, b);
    return min(min(nearest.x, nearest.y), nearest.z);
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
  public bool hasFog;
  public bool hasCloud;
};

public AtmosphereSegment emptyAtmosphereSegment()
{
  AtmosphereSegment result;
  result.radiance = 0;
  result.transmittance = 1;
  return result;
}

public AtmosphereSegment composeAtmosphereSegments(AtmosphereSegment first, AtmosphereSegment second)
{
  AtmosphereSegment result;
  result.radiance = first.radiance + first.transmittance * second.radiance;
  result.transmittance = first.transmittance * second.transmittance;
  return result;
}

public AtmosphereRay createAtmosphereRay<Fog : IAtmosphereFog>(VolumeArgs args,
  float3 origin, float3 direction, uint volumeHint = 0, float3 mediumExtinction = float3(0))
{
  AtmosphereRay ray;
  ray.origin = origin;
  ray.direction = direction;
  ray.mediumExtinction = mediumExtinction;
  float2 fog;
  ray.hasFog = Fog.interval(args, origin, direction, floatMax, fog);
  const float2 cloud = args.clouds.transportMode == 0
    ? cloudInterval(args.clouds, origin, direction, args.clouds.maxDistance) : float2(0);
  ray.hasCloud = cloud.y > cloud.x;
  if (!ray.hasFog) fog = 0;

  ray.fogProjection = createFogRayProjection(args, origin, direction, volumeHint);
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
  ray.intervals.starts = float3(fog.x, cloud.x, cache.x);
  ray.intervals.ends = float3(fog.y, cloud.y, cache.y);
  ray.projectedPhase = projectFogPhase(args.volumetricFogAnisotropy, direction);
  return ray;
}

// Integrates a clipped range without moving the ray origin or its medium boundaries.
public AtmosphereSegment integrateAtmosphereRange<Fog : IAtmosphereFog>(VolumeArgs args,
  Sampler3D<float4> radianceY, Sampler3D<float2> radianceCoCg,
  Sampler2D<float4> weather, Sampler3D<float4> base, Sampler3D<float4> detail,
  AtmosphereRay ray, float rangeBegin, float rangeEnd, float3 cloudAmbient)
{
  AtmosphereSegment result = emptyAtmosphereSegment();
  if (!ray.hasFog && !ray.hasCloud)
  {
    result.transmittance = volumeTransmittance(ray.mediumExtinction, max(0.0f, rangeEnd - rangeBegin));
    return result;
  }
  const float3 origin = ray.origin;
  const float3 direction = ray.direction;
  const float originDepth = ray.fogProjection.originDepth;
  const float directionDepth = ray.fogProjection.directionDepth;
  const float nearPlane = ray.fogProjection.nearPlane;
  const float2 fog = float2(ray.intervals.starts.x, ray.intervals.ends.x);
  const float2 cloud = float2(ray.intervals.starts.y, ray.intervals.ends.y);
  const CloudRayGrid cloudGrid = { cloud, ray.hasCloud ? cloudSampleSpacing(args.clouds, direction) : 1.0f };
  const float2 cache = float2(ray.intervals.starts.z, ray.intervals.ends.z);
  const float limit = min(rangeEnd, ray.intervals.last());
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
    const bool cloudActive = ray.hasCloud && middle > cloud.x && middle < cloud.y;
    if (!fogActive && !cloudActive)
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
    while (previous < end)
    {
      const float next = max(previous, min(end, min(nextCloud, min(nextFog, nextCache))));
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
        const FogRadianceSample sample = fogIncidentRadiance(args, radianceY, radianceCoCg,
          position, ray.fogProjection, (previous + next) * 0.5f);
        source = fogMedium.scattering * sample.evaluate(ray.projectedPhase, args.multiScatteringEstimate);
      }
      // Keep each cloud quadrature cell constant when a prefix checkpoint clips it.
      const float cloudSample = cloudGrid.sampleAt(cloudCell);
      const float3 cloudPosition = origin + direction * cloudSample;
      const float cloudSigma = cloudActive ? cloudExtinction(args.clouds, cloudPosition, weather, base, detail) : 0;
      const float3 extinction = fogMedium.extinction + cloudSigma + ray.mediumExtinction;
      if (cloudSigma > 0)
      {
        source += cloudSigma * args.clouds.albedo
          * cloudIncidentRadiance(args.clouds, cloudPosition, direction, cloudAmbient, weather, base, detail);
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

public AtmosphereSegment integrateAtmosphereSegment<Fog : IAtmosphereFog>(VolumeArgs args,
  Sampler3D<float4> radianceY, Sampler3D<float2> radianceCoCg,
  Sampler2D<float4> weather, Sampler3D<float4> base, Sampler3D<float4> detail,
  float3 origin, float3 direction, float distance, float3 cloudAmbient, uint volumeHint = 0,
  float3 mediumExtinction = float3(0))
{
  const AtmosphereRay ray = createAtmosphereRay<Fog>(args, origin, direction, volumeHint, mediumExtinction);
  return integrateAtmosphereRange<Fog>(args, radianceY, radianceCoCg, weather, base, detail, ray, 0, distance, cloudAmbient);
}
