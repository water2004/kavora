/* SPDX-License-Identifier: MIT */
#define RAY_TRACING_PRIMARY_RAY 1
#define GBUFFER_FEATURE_DEBUG_VIEW 0
import "rtx/algorithm/fog_froxel_sampling.slang";

#include "rtx/pass/common_binding_indices.h"
#include "rtx/pass/common_bindings.slangh"
#include "rtx/algorithm/scene_air.slangh"
#include "rtx/pass/gbuffer/gbuffer_binding_indices.h"
#include "rtx/utility/brdf.slangh"

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

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint2 invocation : SV_DispatchThreadID)
{
  const uint2 column = invocation + uint2(0, push.dispatchOffsetY);
  if (any(column >= atmosphereColumnExtent()))
  {
    return;
  }
  const int stride = int(atmosphereColumnStride());
  const uint2 center = column * uint(stride);
  uint lastCheckpoint = 0;
  bool needsFarCheckpoint = false;
  const bool standard = cb.volumeArgs.fogIntegrationMode != 1;
  // Include every bilinear consumer, including zero-weight taps at even pixels.
  for (int y = -stride; y < stride; y++)
  {
    for (int x = -stride; x < stride; x++)
    {
      const int2 pixel = int2(center) + int2(x, y);
      if (all(pixel >= 0) && all(pixel < int2(cb.camera.resolution)))
      {
        const uint needed = loadAtmosphereEndpoint(uint2(pixel)).y + uint(!standard);
        const bool far = standard && needed == GBUFFER_ATMOSPHERE_CHECKPOINT_CAPACITY;
        needsFarCheckpoint = needsFarCheckpoint || far;
        lastCheckpoint = max(lastCheckpoint, far ? 0u : needed);
      }
    }
  }
  if (lastCheckpoint == 0 && !needsFarCheckpoint)
  {
    return;
  }

  const Ray cameraRay = primaryAtmosphereRay(min(center, cb.camera.resolution - 1));
  const AtmosphereRay ray = createAtmosphereRay<SceneAtmosphereFog>(cb.volumeArgs, cameraRay.origin, cameraRay.direction,
    0, translucentMediumExtinction(cb.startInMediumMaterialIndex, surfaceMaterials));
  const float3 ambient = ray.hasCloud
    ? sampleCloudAmbientRadiance(cb.volumeArgs.clouds)
    : float3(0);
  AtmosphereSegment accumulated = emptyAtmosphereSegment();
  float previous = 0;
  const float farDistance = needsFarCheckpoint ? atmosphereCheckpointMaxDistance() : 0.0f;
  uint checkpoint = 1;
  while (checkpoint <= lastCheckpoint || needsFarCheckpoint)
  {
    // The far slot stores a world distance. Projected near depths can follow it on grazing rays.
    const bool hasNear = checkpoint <= lastCheckpoint;
    const float nearDistance = hasNear ? atmosphereCheckpointDistance(ray.fogProjection, checkpoint) : 0.0f;
    const bool storeFar = needsFarCheckpoint && (!hasNear || farDistance <= nearDistance);
    const float next = max(previous, storeFar ? farDistance : nearDistance);
    const AtmosphereSegment segment = integrateAtmosphereRange<SceneAtmosphereFog>(cb.volumeArgs, VolumeFilteredRadianceY,
      VolumeFilteredRadianceCoCg,
      CloudWeatherSampler, CloudBaseSampler, CloudDetailSampler, sceneCloudLightingCache(), ray, previous, next, ambient, sceneAirTables(cb.volumeArgs.air, LinearWrapSampler));
    accumulated = composeAtmosphereSegments(accumulated, segment);
    storeAtmosphereCheckpoint(column, storeFar ? GBUFFER_ATMOSPHERE_CHECKPOINT_CAPACITY : checkpoint, accumulated);
    needsFarCheckpoint = needsFarCheckpoint && !storeFar;
    checkpoint += uint(!storeFar);
    previous = next;
  }
}
