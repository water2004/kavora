/* SPDX-License-Identifier: MIT */
#include "rtx/pass/common_bindings.slangh"
#include "rtx/pass/clouds/cloud_sky_binding_indices.h"
#include "rtx/utility/math.slangh"
#include "rtx/concept/camera/camera.slangh"
#include "rtx/algorithm/clouds.slangh"
#include "rtx/algorithm/cloud_sky.slangh"
#include "rtx/algorithm/environment_radiance.slangh"

layout(binding = CLOUD_SKY_BINDING_PROBE)
SamplerCube<float3> SkyProbe;
layout(binding = CLOUD_SKY_BINDING_SAMPLER)
SamplerState SkySampler;
layout(binding = CLOUD_SKY_BINDING_OUTPUT)
RWTexture2D<float4> CloudSky;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint2 pixel : SV_DispatchThreadID)
{
  const CloudArgs args = cb.volumeArgs.clouds;
  const uint2 extent = uint2(args.skyResolution * 2, args.skyResolution);
  if (any(pixel >= extent)) return;
  const float3 direction = cloudSkyDirection((float2(pixel) + 0.5f) / float2(extent), args.up);
  const float3 origin = cameraGetWorldPosition(cb.camera);
  const CloudRayGrid grid = { cloudInterval(args, origin, direction, args.maxDistance), cloudSampleSpacing(args, direction) };
  const float3 ambient = sampleEnvironmentRadiance(cb.domeLightArgs, cb.skyBrightness, SkyProbe, SkySampler, args.up) * 0.5f;
  float3 radiance = 0;
  float transmittance = 1;
  float previous = grid.interval.x;
  for (uint cell = 0; previous < grid.interval.y; cell++)
  {
    const float next = grid.boundary(cell + 1);
    const float3 position = origin + direction * grid.sampleAt(cell);
    const float extinction = cloudExtinction(args, position);
    if (extinction > 0)
    {
      const float stepTransmittance = exp(-extinction * (next - previous));
      radiance += transmittance * (1.0f - stepTransmittance) * args.albedo
        * cloudIncidentRadiance(args, position, direction, ambient);
      transmittance *= stepTransmittance;
    }
    if (transmittance < kOpaqueCloudTransmittance) break;
    previous = next;
  }
  CloudSky[pixel] = float4(radiance, transmittance);
}
