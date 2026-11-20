/* SPDX-License-Identifier: MIT */
//!variant gbuffer_atmosphere_columns.comp
//!variant gbuffer_haze_columns.comp CACHED_HAZE_BUILD
//!variant gbuffer_cloud_columns.comp CACHED_HAZE_SAMPLE
//!end-variants
#define RAY_TRACING_PRIMARY_RAY 1
#define GBUFFER_FEATURE_DEBUG_VIEW 0
import "rtx/algorithm/fog_froxel_sampling.slang";
import "rtx/concept/surface_material/translucent_medium.slang";

#include "rtx/pass/common_binding_indices.h"
#include "rtx/pass/common_bindings.slangh"
#include "rtx/concept/ray/ray.slangh"
#include "rtx/algorithm/scene_air.slangh"
#include "rtx/algorithm/scene_air_shadow.slangh"
#include "rtx/pass/gbuffer/gbuffer_binding_indices.h"
#include "rtx/utility/brdf.slangh"
#include "rtx/algorithm/volume_lighting.slangh"

layout(binding = GBUFFER_BINDING_LINEAR_WRAP_SAMPLER)
SamplerState LinearWrapSampler;
layout(binding = GBUFFER_BINDING_VOLUME_FILTERED_RADIANCE_Y_INPUT)
Sampler3D<float4> VolumeFilteredRadianceY;
layout(binding = GBUFFER_BINDING_VOLUME_FILTERED_RADIANCE_CO_CG_INPUT)
Sampler3D<float2> VolumeFilteredRadianceCoCg;
layout(binding = GBUFFER_BINDING_SKYPROBE)
SamplerCube<float3> SkyProbe;
layout(push_constant)
ConstantBuffer<GbufferPushConstants> push;

#include "rtx/pass/gbuffer/primary_atmosphere_cache.slangh"
#include "rtx/pass/gbuffer/primary_haze_cache.slangh"
#include "rtx/algorithm/environment_radiance.slangh"
#include "rtx/algorithm/scene_cloud_lighting.slangh"
#ifndef CACHED_HAZE_BUILD
#include "rtx/pass/gbuffer/cloud_column_history.slangh"
#endif

float4 columnEndpointDistances(uint2 begin, uint2 end, uint stride)
{
  if (stride == GBUFFER_ATMOSPHERE_COLUMN_STRIDE)
  {
    const float a = loadAtmosphereEndpoint(begin);
    const float b = loadAtmosphereEndpoint(uint2(end.x, begin.y));
    const float c = loadAtmosphereEndpoint(uint2(begin.x, end.y));
    const float d = loadAtmosphereEndpoint(end);
    const float2 lo = float2(min(a, b), min(c, d));
    const float2 hi = float2(max(a, b), max(c, d));
    const float2 mid = float2(max(lo.x, lo.y), min(hi.x, hi.y));
    return float4(min(lo.x, lo.y), min(mid.x, mid.y), max(mid.x, mid.y), max(hi.x, hi.y));
  }

  // Retain the finite depth range and a separate sky endpoint.
  float nearest = floatMax;
  float farthest = 0.0f;
  bool hasSky = false;
  for (uint y = begin.y; y <= end.y; y++)
  {
    for (uint x = begin.x; x <= end.x; x++)
    {
      const float distance = loadAtmosphereEndpoint(uint2(x, y));
      nearest = min(nearest, distance);
      farthest = distance != floatMax ? max(farthest, distance) : farthest;
      hasSky = hasSky || distance == floatMax;
    }
  }
  return nearest == floatMax ? floatMax.xxxx
    : float4(nearest, nearest + 0.5f * (farthest - nearest), farthest, hasSky ? floatMax : farthest);
}

#ifdef CACHED_HAZE_BUILD
void integrateHazeColumn(uint2 column, AtmosphereRay ray, float4 scene, VolumeArgs volume)
{
  const float2 air = ray.airInterval;
  const float2 fog = ray.fogInterval;
  const float end = max(air.y, fog.y);
  const float finiteNear = scene.x == floatMax ? 0.0f : min(scene.x, end);
  const float finiteFar = scene.z == floatMax ? end : min(scene.z, end);
  // Keep scene depths, the fog boundaries and depth samples through the air.
  // The uppermost anchor covers the end of all radiance-producing media.
  float depths[GBUFFER_HAZE_COLUMN_ENDPOINTS] = { finiteNear, finiteFar, fog.x, fog.y,
    lerp(air.x, air.y, 0.03125f), lerp(air.x, air.y, 0.125f), lerp(air.x, air.y, 0.5f), end };
  [unroll]
  for (uint i = 0; i < GBUFFER_HAZE_COLUMN_ENDPOINTS; ++i)
  {
    [unroll]
    for (uint j = i + 1; j < GBUFFER_HAZE_COLUMN_ENDPOINTS; ++j)
    {
      const float near = min(depths[i], depths[j]);
      depths[j] = max(depths[i], depths[j]);
      depths[i] = near;
    }
  }
  let haze = IntegratedAtmosphereHaze<SceneAtmosphereFog, FroxelFogRadiance, SceneAirVisibility>(
    FroxelFogRadiance(VolumeFilteredRadianceY, VolumeFilteredRadianceCoCg));
  const AirTables airTables = sceneAirTables(volume.air, LinearWrapSampler);
  const uint base = hazeColumnOffset(column);
  AtmosphereSegment prefix = emptyAtmosphereSegment();
  float previous = 0;
  for (uint i = 0; i < GBUFFER_HAZE_COLUMN_ENDPOINTS; ++i)
  {
    const float next = depths[i];
    if (next > previous)
      prefix = composeAtmosphereSegments(prefix, haze.range(volume, ray, previous, next, airTables));
    storeHazePrefix(base + i * GBUFFER_HAZE_COLUMN_ENDPOINT_WORDS, next, prefix);
    previous = next;
  }
}
#endif

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint2 invocation : SV_DispatchThreadID)
{
  const uint2 column = invocation + uint2(0, push.dispatchOffsetY);
#ifdef CACHED_HAZE_BUILD
  const uint stride = hazeColumnStride();
#else
  const uint stride = atmosphereColumnStride();
#endif
  if (any(column >= columnExtent(stride)))
  {
    return;
  }
  const uint2 begin = column * stride;
  const uint2 end = min(begin + stride - 1, cb.camera.resolution - 1);
  const float4 distances = columnEndpointDistances(begin, end, stride);
  const float2 center = 0.5f * float2(begin + end) + 0.5f;
  const float2 uv = center / float2(cb.camera.resolution);
  const Ray cameraRay = rayCreatePrimary(cb.camera, uv, cb.screenSpacePixelSpreadHalfAngle, true);
  const float3 medium = translucentMediumExtinction(cb.startInMediumMaterialIndex, surfaceMaterials);
#ifdef CACHED_HAZE_BUILD
  VolumeArgs volume = cb.volumeArgs;
  volume.clouds.enable = 0;
  const AtmosphereRay ray = createAtmosphereRay<SceneAtmosphereFog>(volume, CloudPhaseSampler, cameraRay.origin, cameraRay.direction, 0, medium);
  integrateHazeColumn(column, ray, distances, volume);
#else
#ifdef CACHED_HAZE_SAMPLE
  const AtmosphereRay ray = createAtmosphereRay<ClearAtmosphereFog>(cb.volumeArgs, CloudPhaseSampler, cameraRay.origin, cameraRay.direction, 0, medium,
    float(stride) * cb.volumeArgs.clouds.viewAngularFootprint);
  const CachedAtmosphereHaze haze = CachedAtmosphereHaze(center);
#else
  const AtmosphereRay ray = createAtmosphereRay<SceneAtmosphereFog>(cb.volumeArgs, CloudPhaseSampler, cameraRay.origin, cameraRay.direction, 0, medium,
    float(stride) * cb.volumeArgs.clouds.viewAngularFootprint);
  let haze = IntegratedAtmosphereHaze<SceneAtmosphereFog, FroxelFogRadiance, SceneAirVisibility>(
    FroxelFogRadiance(VolumeFilteredRadianceY, VolumeFilteredRadianceCoCg));
#endif
  const float3 ambient = ray.hasCloud
    ? sampleCloudAmbientRadiance(cb.volumeArgs.clouds)
    : float3(0);
  AtmosphereSegment accumulated = emptyAtmosphereSegment();
  CloudColumnHistory history = {};
  history.offset = cloudColumnSampleOffset(column, cb.frameIdx);
  float previous = 0;
  for (uint endpoint = 0; endpoint < GBUFFER_ATMOSPHERE_COLUMN_ENDPOINTS; endpoint++)
  {
    const float next = distances[endpoint];
    if (next > previous)
    {
      const AtmosphereSegment segment = integrateAtmosphereWithHaze(cb.volumeArgs, haze, history,
        CloudWeatherSampler, CloudBaseSampler, CloudDetailSampler,
        sceneCloudLightingCache(), ray, previous, next, ambient, sceneAirTables(cb.volumeArgs.air, LinearWrapSampler));
      accumulated = composeAtmosphereSegments(accumulated, segment);
    }
    storeAtmosphereColumnEndpoint(column, endpoint, next, accumulated);
    previous = next;
  }
  if (cb.volumeArgs.clouds.enable)
  {
    history.store(column);
  }
#endif
}
