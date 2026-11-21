/* SPDX-License-Identifier: MIT */
#define RAY_TRACING_PRIMARY_RAY 1
#define GBUFFER_FEATURE_DEBUG_VIEW 0
import "rtx/algorithm/clouds.slang";
import "rtx/concept/surface_material/translucent_medium.slang";

#include "rtx/pass/common_binding_indices.h"
#include "rtx/pass/common_bindings.slangh"
#include "rtx/concept/ray/ray.slangh"
#include "rtx/algorithm/scene_air.slangh"
#include "rtx/pass/gbuffer/gbuffer_binding_indices.h"
#include "rtx/utility/brdf.slangh"

layout(binding = GBUFFER_BINDING_LINEAR_WRAP_SAMPLER)
SamplerState LinearWrapSampler;
layout(push_constant)
ConstantBuffer<GbufferPushConstants> push;

#include "rtx/pass/gbuffer/primary_haze_cache.slangh"
#include "rtx/algorithm/environment_radiance.slangh"
#include "rtx/algorithm/scene_cloud_lighting.slangh"

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint2 invocation : SV_DispatchThreadID)
{
  const uint2 pixel = invocation + uint2(0, push.dispatchOffsetY);
  if (any(pixel >= cb.camera.resolution))
  {
    return;
  }

  const float2 center = float2(pixel) + 0.5f;
  const Ray cameraRay = rayCreatePrimaryFromPixel(cb.camera, pixel);
  const float3 medium = translucentMediumExtinction(cb.startInMediumMaterialIndex, surfaceMaterials);
  const AtmosphereRay ray = createAtmosphereRay<ClearAtmosphereFog>(cb.volumeArgs, CloudPhaseSampler,
    cameraRay.origin, cameraRay.direction, 0, medium);
  const float3 ambient = ray.hasCloud ? sampleCloudAmbientRadiance(cb.volumeArgs.clouds) : float3(0);
  const AtmosphereSegment transfer = integrateAtmosphereWithHaze(cb.volumeArgs, CachedAtmosphereHaze(center),
    cloudPixelSampleOffset(pixel, cb.frameIdx), CloudWeatherSampler, CloudBaseSampler, CloudDetailSampler,
    sceneCloudLightingCache(), ray, 0, loadAtmosphereEndpoint(pixel), ambient, sceneAirTables(cb.volumeArgs.air, LinearWrapSampler));
  storePrimaryAtmosphere(pixel, transfer);
}
