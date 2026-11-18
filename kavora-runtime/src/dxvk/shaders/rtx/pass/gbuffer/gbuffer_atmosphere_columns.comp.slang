/* SPDX-License-Identifier: MIT */
#define RAY_TRACING_PRIMARY_RAY 1
#define GBUFFER_FEATURE_DEBUG_VIEW 0
import "rtx/algorithm/fog_froxel_sampling.slang";
import "rtx/concept/surface_material/translucent_medium.slang";

#include "rtx/pass/common_binding_indices.h"
#include "rtx/pass/common_bindings.slangh"
#include "rtx/concept/ray/ray.slangh"
#include "rtx/algorithm/scene_air.slangh"
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
#include "rtx/algorithm/environment_radiance.slangh"
#include "rtx/algorithm/scene_cloud_lighting.slangh"

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

  // The coarse fog setting still bounds the amount of integration work. Its
  // columns retain the finite depth range and capture a separate sky endpoint.
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

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint2 invocation : SV_DispatchThreadID)
{
  const uint2 column = invocation + uint2(0, push.dispatchOffsetY);
  if (any(column >= atmosphereColumnExtent()))
  {
    return;
  }
  const uint stride = atmosphereColumnStride();
  const uint2 begin = column * stride;
  const uint2 end = min(begin + stride - 1, cb.camera.resolution - 1);
  const float4 distances = columnEndpointDistances(begin, end, stride);
  const float2 uv = (0.5f * float2(begin + end) + 0.5f) / float2(cb.camera.resolution);
  const Ray cameraRay = rayCreatePrimary(cb.camera, uv, cb.screenSpacePixelSpreadHalfAngle, true);
  const AtmosphereRay ray = createAtmosphereRay<SceneAtmosphereFog>(cb.volumeArgs, cameraRay.origin, cameraRay.direction,
    0, translucentMediumExtinction(cb.startInMediumMaterialIndex, surfaceMaterials),
    float(stride) * cb.volumeArgs.clouds.viewAngularFootprint);
  const float3 ambient = ray.hasCloud
    ? sampleCloudAmbientRadiance(cb.volumeArgs.clouds)
    : float3(0);
  AtmosphereSegment accumulated = emptyAtmosphereSegment();
  float previous = 0;
  for (uint endpoint = 0; endpoint < GBUFFER_ATMOSPHERE_COLUMN_ENDPOINTS; endpoint++)
  {
    const float next = distances[endpoint];
    if (next > previous)
    {
      const AtmosphereSegment segment = integrateAtmosphereRange<SceneAtmosphereFog>(cb.volumeArgs, VolumeFilteredRadianceY,
        VolumeFilteredRadianceCoCg, CloudWeatherSampler, CloudBaseSampler, CloudDetailSampler,
        sceneCloudLightingCache(), ray, previous, next, ambient, sceneAirTables(cb.volumeArgs.air, LinearWrapSampler));
      accumulated = composeAtmosphereSegments(accumulated, segment);
    }
    storeAtmosphereColumnEndpoint(column, endpoint, next, accumulated);
    previous = next;
  }
}
