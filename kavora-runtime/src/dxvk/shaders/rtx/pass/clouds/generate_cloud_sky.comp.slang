/* SPDX-License-Identifier: MIT */
import "rtx/concept/camera/camera.slang";
import "rtx/algorithm/cloud_sky.slang";
import "rtx/algorithm/atmosphere.slang";
#include "rtx/pass/common_bindings.slangh"
#include "rtx/algorithm/scene_air.slangh"
#include "rtx/algorithm/scene_cloud_lighting.slangh"
#include "rtx/algorithm/environment_radiance.slangh"
#include "rtx/pass/clouds/cloud_sky_binding_indices.h"
#include "rtx/utility/math.slangh"

layout(binding = CLOUD_SKY_BINDING_OUTPUT)
RWTexture2DArray<float4> CloudSky;
layout(binding = CLOUD_SKY_BINDING_PROBE)
SamplerCube<float3> CloudSkyProbe;
layout(binding = CLOUD_SKY_BINDING_AIR_SAMPLER)
SamplerState CloudAirSampler;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint2 pixel : SV_DispatchThreadID)
{
  const uint2 extent = uint2(CLOUD_SKY_RESOLUTION * 2, CLOUD_SKY_RESOLUTION);
  if (any(pixel >= extent))
  {
    return;
  }
  const VolumeArgs volume = cb.volumeArgs;
  const float3 direction = cloudSkyDirection((float2(pixel) + 0.5f) / float2(extent), volume.clouds.up);
  const float3 origin = cameraGetWorldPosition(cb.camera);
  const AtmosphereRay ray = createAtmosphereRay<ClearAtmosphereFog>(volume, origin, direction, 0, 0, kCloudSkyTexelAngle);
  const float split = cloudSkyTailStart(volume);
  const float3 ambient = ray.hasCloud ? sampleCloudAmbientRadiance(volume.clouds) : float3(0);
  const CloudLightingCache lighting = sceneCloudLightingCache();
  const AirTables air = sceneAirTables(volume.air, CloudAirSampler);
  const AtmosphereSegment tail = integrateAtmosphereRange<ClearAtmosphereFog, ClearFogRadiance>(volume, {},
    CloudWeatherSampler, CloudBaseSampler, CloudDetailSampler, lighting, ray, split, floatMax, ambient, air);
  CloudSky[uint3(pixel, kCloudSkyTailRadianceLayer)] = float4(tail.radiance, tail.cloudMoments.y);
  CloudSky[uint3(pixel, kCloudSkyTailTransmittanceLayer)] = float4(tail.transmittance, tail.cloudMoments.x);

  // Fog samples the incident sky before applying its own ray attenuation.
  // The near integration ends where the shared far transfer begins.
  if (volume.enable)
  {
    const AtmosphereSegment near = integrateAtmosphereRange<ClearAtmosphereFog, ClearFogRadiance>(volume, {},
      CloudWeatherSampler, CloudBaseSampler, CloudDetailSampler, lighting, ray, 0, split, ambient, air);
    const float3 boundary = sampleIndirectEnvironmentRadiance(direction, CloudSkyProbe, CloudAirSampler);
    const float3 radiance = near.radiance + near.transmittance * (tail.radiance + tail.transmittance * boundary);
    CloudSky[uint3(pixel, kCloudSkyIrradianceLayer)] = float4(radiance, 0);
  }
}
