/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
#include "rtx/pass/cloud_noise.h"
#include "rtx/utility/cloud_noise.slangh"

layout(rgba8, binding = 0)
RWTexture3D<float4> CloudNoise;
layout(push_constant)
ConstantBuffer<CloudNoiseGenerationArgs> cb;

[shader("compute")]
[numthreads(4, 4, 4)]
void main(uint3 id : SV_DispatchThreadID)
{
  const uint resolution = cb.detail ? kCloudDetailResolution : kCloudBaseResolution;
  const float3 uv = (float3(id) + 0.5f) / float(resolution);
  if (cb.detail)
  {
    CloudNoise[id] = float4(cloudWorleyFbm(uv, 2, kCloudNoiseSeed + 101),
      cloudWorleyFbm(uv, 4, kCloudNoiseSeed + 113),
      cloudWorleyFbm(uv, 8, kCloudNoiseSeed + 127), 1);
    return;
  }
  float perlin = 0;
  float amplitude = 1;
  for (uint octave = 0; octave < 3; octave++)
  {
    const uint period = 4u << octave;
    perlin += amplitude * cloudPerlin(uv * float(period), period, kCloudNoiseSeed + octave);
    amplitude *= 0.5f;
  }
  perlin = saturate(perlin * (0.75f / 1.75f) + 0.5f);
  const float worley4 = cloudWorleyFbm(uv, 4, kCloudNoiseSeed + 11);
  CloudNoise[id] = float4(saturate((perlin - worley4 + 1.0f) / (2.0f - worley4)), worley4,
    cloudWorleyFbm(uv, 8, kCloudNoiseSeed + 23), cloudWorleyFbm(uv, 16, kCloudNoiseSeed + 37));
}
