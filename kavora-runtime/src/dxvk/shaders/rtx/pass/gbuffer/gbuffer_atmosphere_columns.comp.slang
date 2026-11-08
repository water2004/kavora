/* SPDX-License-Identifier: MIT */
#define RAY_TRACING_PRIMARY_RAY 1
#define GBUFFER_FEATURE_DEBUG_VIEW 0

#include "rtx/pass/common_binding_indices.h"
#include "rtx/pass/common_bindings.slangh"
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

#include "rtx/pass/gbuffer/primary_atmosphere_cache.slangh"
#include "rtx/algorithm/environment_radiance.slangh"

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint2 column : SV_DispatchThreadID)
{
  if (any(column >= atmosphereColumnExtent()))
  {
    return;
  }
  const int stride = int(atmosphereColumnStride());
  const uint2 center = column * uint(stride);
  uint lastCheckpoint = 0;
  // Include every bilinear consumer, including zero-weight taps at even pixels.
  for (int y = -stride; y < stride; y++)
  {
    for (int x = -stride; x < stride; x++)
    {
      const int2 pixel = int2(center) + int2(x, y);
      if (all(pixel >= 0) && all(pixel < int2(cb.camera.resolution)))
      {
        const uint needed = loadAtmosphereEndpoint(uint2(pixel)).y + uint(cb.volumeArgs.fogIntegrationMode == 1);
        lastCheckpoint = max(lastCheckpoint, needed);
      }
    }
  }
  if (lastCheckpoint == 0)
  {
    return;
  }

  const Ray cameraRay = primaryAtmosphereRay(min(center, cb.camera.resolution - 1));
  const AtmosphereRay ray = createAtmosphereRay(cb.volumeArgs, cameraRay.origin, cameraRay.direction,
    0, translucentMediumExtinction(cb.startInMediumMaterialIndex));
  const float3 ambient = cb.volumeArgs.clouds.enable
    ? sampleEnvironmentRadiance(cb.domeLightArgs, cb.skyBrightness, SkyProbe, LinearWrapSampler, cb.volumeArgs.sceneUpDirection) * 0.5f
    : float3(0);
  AtmosphereSegment accumulated = emptyAtmosphereSegment();
  float previous = 0;
  for (uint checkpoint = 1; checkpoint <= lastCheckpoint; checkpoint++)
  {
    const float next = max(previous, atmosphereCheckpointDistance(ray.fogProjection, checkpoint));
    const AtmosphereSegment segment = integrateAtmosphereRange(cb.volumeArgs, VolumeFilteredRadianceY,
      VolumeFilteredRadianceCoCg, ray, previous, next, ambient);
    accumulated = composeAtmosphereSegments(accumulated, segment);
    storeAtmosphereCheckpoint(column, checkpoint, accumulated);
    previous = next;
  }
}
