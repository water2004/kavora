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
  const float4 light = sampleCloudDirectLight(Clouds, Sampler3D<float4>(Direct), input.xyz, request.x,
    input.w, uint(request.y), weight);
  Results[index] = float4(light[uint(request.z)], weight, 0, 0);
}
