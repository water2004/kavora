// SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors
// SPDX-License-Identifier: MIT
import "rtx/algorithm/cloud_direct_light.slang";
import "rtx/pass/cloud_parameters.slang";

ConstantBuffer<CloudArgs> Clouds;
Sampler3D<float4>.Handle Direct;
StructuredBuffer<float4> Origins;
StructuredBuffer<float4> Directions;
RWStructuredBuffer<float4> Results;
uint Count;

[shader("compute")]
[numthreads(64, 1, 1)]
void directLight(uint index : SV_DispatchThreadID)
{
  if (index >= Count)
  {
    return;
  }
  const float4 input = Origins[index];
  const float4 request = Directions[index];
  float weight;
  const uint layer = uint(request.y);
  const uint light = uint(request.z) / 2;
  const CloudDirectLight cache = sampleCloudDirectLight(Clouds, Sampler3D<float4>(Direct), input.xyz, request.x,
    input.w, layer, weight);
  const float3 up = normalize(input.xyz - Clouds.groundReference + Clouds.up * Clouds.planetRadius);
  const float2 beam = cache.at(Clouds, request.x, layer, light, dot(up, Clouds.lights[light].directionToLight), request.w);
  Results[index] = float4(beam[uint(request.z) & 1], weight, 0, 0);
}
