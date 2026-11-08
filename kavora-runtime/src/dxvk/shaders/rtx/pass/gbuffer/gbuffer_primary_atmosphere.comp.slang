/* SPDX-License-Identifier: MIT */
#define RAY_TRACING_PRIMARY_RAY 1
#define GBUFFER_FEATURE_DEBUG_VIEW 0

#include "rtx/pass/common_binding_indices.h"
#include "rtx/pass/common_bindings.slangh"
#include "rtx/pass/gbuffer/gbuffer_binding_indices.h"
#include "rtx/utility/shader_types.slangh"
#include "rtx/utility/brdf.slangh"

layout(binding = GBUFFER_BINDING_LINEAR_WRAP_SAMPLER)
SamplerState LinearWrapSampler;
layout(binding = GBUFFER_BINDING_VOLUME_FILTERED_RADIANCE_Y_INPUT)
Sampler3D<float4> VolumeFilteredRadianceY;
layout(binding = GBUFFER_BINDING_VOLUME_FILTERED_RADIANCE_CO_CG_INPUT)
Sampler3D<float2> VolumeFilteredRadianceCoCg;
layout(binding = GBUFFER_BINDING_SKYPROBE)
SamplerCube<float3> SkyProbe;

#include "rtx/pass/gbuffer/primary_intersection.slangh"
#include "rtx/pass/gbuffer/primary_atmosphere.slangh"
#include "rtx/algorithm/environment_radiance.slangh"
#include "rtx/algorithm/view_distance.slangh"

#include "rtx/pass/gbuffer/primary_atmosphere_cache.slangh"

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint2 pixel : SV_DispatchThreadID)
{
  if (any(pixel >= cb.camera.resolution))
  {
    return;
  }
  const uint2 endpoint = loadAtmosphereEndpoint(pixel);
  const float distance = asfloat(endpoint.x);
  const uint checkpoint = endpoint.y;
  const Ray cameraRay = primaryAtmosphereRay(pixel);
  const AtmosphereRay ray = createAtmosphereRay(cb.volumeArgs, cameraRay.origin, cameraRay.direction,
    0, translucentMediumExtinction(cb.startInMediumMaterialIndex));
  if (!ray.hasFog && !ray.hasCloud)
  {
    storePrimaryAtmosphere(pixel, integrateAtmosphereRange(cb.volumeArgs, VolumeFilteredRadianceY,
      VolumeFilteredRadianceCoCg, ray, 0, distance, float3(0)));
    return;
  }
  const float3 ambient = cb.volumeArgs.clouds.enable
    ? sampleEnvironmentRadiance(cb.domeLightArgs, cb.skyBrightness, SkyProbe, LinearWrapSampler, cb.volumeArgs.sceneUpDirection) * 0.5f
    : float3(0);
  if (cb.volumeArgs.fogIntegrationMode == 1)
  {
    const uint count = min(cb.volumeArgs.froxelDepthSlices, GBUFFER_ATMOSPHERE_PREINTEGRATED_CAPACITY);
    const float cacheEnd = atmosphereCheckpointDistance(ray.fogProjection, count);
    const AtmosphereSegment prefix = samplePreintegratedAtmosphere(pixel, ray.fogProjection,
      min(distance, cacheEnd), checkpoint);
    const AtmosphereSegment tail = distance > cacheEnd
      ? integrateAtmosphereRange(cb.volumeArgs, VolumeFilteredRadianceY, VolumeFilteredRadianceCoCg,
          ray, cacheEnd, distance, ambient) : emptyAtmosphereSegment();
    storePrimaryAtmosphere(pixel, composeAtmosphereSegments(prefix, tail));
    return;
  }
  const float begin = checkpoint == 0 ? 0.0f
    : atmosphereCheckpointDistance(ray.fogProjection, checkpoint);
  const AtmosphereSegment prefix = interpolateAtmosphereCheckpoints(pixel, checkpoint);
  const AtmosphereSegment tail = integrateAtmosphereRange(cb.volumeArgs, VolumeFilteredRadianceY,
    VolumeFilteredRadianceCoCg, ray, begin, distance, ambient);
  storePrimaryAtmosphere(pixel, composeAtmosphereSegments(prefix, tail));
}
