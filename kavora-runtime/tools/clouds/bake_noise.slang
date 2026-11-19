// SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors
// SPDX-License-Identifier: MIT
// This is the unmodified compute shader from AshenOneArt's 3DTextureExportTools.
// bake_noise.py pins its revision and content. GenerateCloudNoise is the base entry.
#include "NoiseGenerator.compute"

float2 detailBands(float3 uv)
{
  return float2(
    0.67f * worleyNoise3DTileable(uv, 2, 1) + 0.33f * worleyNoise3DTileable(uv, 4, 1),
    0.67f * worleyNoise3DTileable(uv, 8, 1) + 0.33f * worleyNoise3DTileable(uv, 16, 1));
}

float4 erosionBands(float3 uv)
{
  // Curl displaces the filaments along a periodic stream function.
  const float h = 1.0f / 64.0f;
  const float3 x = float3(h, 0, 0);
  const float3 z = float3(0, 0, h);
  const float2 curl = float2(
    perlinNoise3DTileable((uv + z) * 2, 2) - perlinNoise3DTileable((uv - z) * 2, 2),
    perlinNoise3DTileable((uv - x) * 2, 2) - perlinNoise3DTileable((uv + x) * 2, 2)) / (2 * h);
  const float3 stream = (uv + float3(curl.x, 0, curl.y) / 16) * float3(1, 2, 2);
  return float4(1 - detailBands(stream), detailBands(uv));
}

[shader("compute")]
[numthreads(4, 4, 4)]
void erosion(uint3 id : SV_DispatchThreadID)
{
  float4 sum = 0;
  for (uint i = 0; i < 8; i++)
  {
    const float3 offset = float3(i & 1, (i >> 1) & 1, i >> 2);
    sum += erosionBands((float3(id) + 0.25f + 0.5f * offset) / 64);
  }
  Result[id] = sum / 8;
}
