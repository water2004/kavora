// SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
// SPDX-License-Identifier: MIT
#include "rtx/pass/common_bindings.slangh"
#include "rtx/pass/clouds/cloud_ambient_binding_indices.h"
#include "rtx/utility/math.slangh"
import "rtx/algorithm/cloud_sky.slang";
#include "rtx/algorithm/environment_radiance.slangh"

layout(binding = CLOUD_AMBIENT_BINDING_PROBE)
SamplerCube<float3> SkyProbe;
layout(binding = CLOUD_AMBIENT_BINDING_SAMPLER)
SamplerState SkySampler;
layout(binding = CLOUD_AMBIENT_BINDING_OUTPUT)
RWTexture2D<float4> CloudAmbientOutput;

groupshared float3 AmbientSum[256];

[shader("compute")]
[numthreads(256, 1, 1)]
void main(uint lane : SV_GroupIndex, uint3 group : SV_GroupID)
{
  const CloudAmbientQuadrature quadrature = createCloudAmbientQuadrature(cb.volumeArgs.clouds);
  float3 radiance = 0;
  const bool exterior = group.x == kExteriorAmbientTexel || !cb.volumeArgs.clouds.enable;
  if (exterior)
  {
    // Mean upper boundary before cloud transport. The lower hemisphere
    // receives ground reflection, not a copy of the exterior sky.
    const float3 direction = quadrature.direction(lane, (float(lane) + 0.5f) / 256.0f);
    radiance = sampleIndirectEnvironmentRadiance(direction, SkyProbe, SkySampler) / 256.0f;
  }
  else
  {
    for (uint index = lane; index < kCloudAmbientSampleCount; index += 256)
    {
      const float4 sample = quadrature.sample(index);
      radiance += sampleEnvironmentRadiance(cb.domeLightArgs, cb.skyBrightness, SkyProbe, SkySampler, sample.xyz) * sample.w;
    }
  }
  AmbientSum[lane] = radiance;
  GroupMemoryBarrierWithGroupSync();
  for (uint offset = 128; offset > 0; offset /= 2)
  {
    if (lane < offset)
    {
      AmbientSum[lane] += AmbientSum[lane + offset];
    }
    GroupMemoryBarrierWithGroupSync();
  }
  if (lane == 0)
  {
    CloudAmbientOutput[uint2(exterior ? kExteriorAmbientTexel : 0, 0)] = float4(AmbientSum[0], 1);
  }
}
