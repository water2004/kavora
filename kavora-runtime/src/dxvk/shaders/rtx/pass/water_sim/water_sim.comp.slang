// SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
// SPDX-License-Identifier: MIT
#include "rtx/pass/water_sim/water_sim_args.h"

[[vk::push_constant]] ConstantBuffer<WaterSimStep> Step;
[[vk::binding(WATER_SIM_BINDING_PREVIOUS)]] Texture2D<float4> Previous;
[[vk::binding(WATER_SIM_BINDING_CURRENT)]] RWTexture2D<float4> Current;
[[vk::binding(WATER_SIM_BINDING_IMPULSES)]] StructuredBuffer<WaterSimImpulse> Impulses;

float2 previousState(int2 coordinate) {
  return Step.reset || any(coordinate < 0) || any(coordinate >= int(kWaterSimResolution))
    ? float2(0.0f)
    : Previous.Load(int3(coordinate, 0)).xy;
}

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint2 cell : SV_DispatchThreadID) {
  const int2 previousCell = int2(cell) + Step.shift;
  const float2 state = previousState(previousCell);
  const float left = previousState(previousCell + int2(-1, 0)).x;
  const float right = previousState(previousCell + int2(1, 0)).x;
  const float near = previousState(previousCell + int2(0, -1)).x;
  const float far = previousState(previousCell + int2(0, 1)).x;
  const float laplacian = left + right + near + far - 4.0f * state.x;
  const float2 position = Step.origin + (float2(cell) + 0.5f) * Step.cellSize;

  float velocity = state.y + Step.travel * laplacian;
  for (uint index = 0; index < Step.impulseCount; ++index) {
    const WaterSimImpulse impulse = Impulses[index];
    const float2 offset = (position - impulse.position) / impulse.radius;
    const float coverage = saturate(1.0f - dot(offset, offset));
    velocity += impulse.strength * coverage * coverage;
  }

  // A damping ramp absorbs energy at the clipmap boundary.
  const uint2 edge = min(cell, (kWaterSimResolution - 1) - cell);
  const float attenuation = lerp(0.8f, Step.damping, saturate(float(min(edge.x, edge.y)) / 12.0f));
  velocity = clamp(velocity * attenuation, -4.0f, 4.0f);
  const float height = clamp(state.x * attenuation + velocity * Step.timeStep, -0.25f, 0.25f);
  const float2 slope = float2(right - left, far - near) * (0.5f / Step.cellSize);
  Current[cell] = float4(height, velocity, slope);
}
