// SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
// SPDX-License-Identifier: MIT
#include "rtx/pass/water_sim/water_sim_args.h"

[[vk::push_constant]] ConstantBuffer<WaterImpactStep> Step;
[[vk::binding(WATER_SIM_BINDING_PREVIOUS)]] Texture2D<float2> Previous;
[[vk::binding(WATER_SIM_BINDING_CURRENT)]] RWTexture2D<float2> Current;
[[vk::binding(WATER_SIM_BINDING_IMPULSES)]] StructuredBuffer<WaterSimImpulse> Impulses;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint2 cell : SV_DispatchThreadID) {
  const int2 previousCell = int2(cell) + Step.shift;
  float2 state = Step.reset || any(previousCell < 0) || any(previousCell >= int(kWaterSimResolution))
    ? float2(0.0f) : Previous.Load(int3(previousCell, 0));
  state = max(0.0f, state - Step.delta * float2(0.52f, 0.8f));
  state.x = (Step.effects & WATER_EFFECT_SLUDGE) ? state.x : 0.0f;
  state.y = (Step.effects & WATER_EFFECT_RINGS) ? state.y : 0.0f;
  const float2 position = Step.origin + (float2(cell) + 0.5f) * Step.cellSize;
  for (uint i = Step.firstImpulse; i < Step.impulseCount; ++i) {
    const WaterSimImpulse impulse = Impulses[i];
    const float strength = abs(impulse.strength);
    const float radius = max(0.65f, impulse.radius * 1.6f);
    const float2 offset = (position - impulse.position) / radius;
    const float distance2 = dot(offset, offset);
    if (Step.effects & WATER_EFFECT_SLUDGE) {
      state.x = max(state.x, saturate(strength * 2.1f) * saturate(1.0f - distance2));
    }
    if (Step.effects & WATER_EFFECT_RINGS) {
      state.y = max(state.y, saturate((strength / impulse.radius - 0.3f) * 1.9f) * saturate(1.0f - distance2 * 0.42f));
    }
  }
  Current[cell] = state;
}
