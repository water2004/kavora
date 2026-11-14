// SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
// SPDX-License-Identifier: MIT
#include "rtx/pass/water_sim/water_sim_args.h"

[[vk::push_constant]] ConstantBuffer<WaterDetailStep> Step;
[[vk::binding(WATER_DETAIL_BINDING_OUTPUT)]] RWTexture2D<float2> Output;

float ringRandom(uint key) {
  key ^= key >> 16;
  key *= 0x7feb352du;
  key ^= key >> 15;
  key *= 0x846ca68bu;
  key ^= key >> 16;
  return float(key >> 8) * (1.0f / 16777216.0f);
}

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint2 thread : SV_DispatchThreadID) {
  static const int cells = 16;
  const float2 position = (float2(thread) + 0.5f) * (float(cells) / kWaterRingResolution);
  const int2 cell = int2(position);
  float2 slope = 0.0f;
  for (int y = -1; y <= 1; ++y) {
    for (int x = -1; x <= 1; ++x) {
      const int2 neighbor = cell + int2(x, y);
      const uint2 wrapped = uint2(neighbor) & uint(cells - 1);
      const uint key = wrapped.y * cells + wrapped.x + 1u;
      const float2 center = float2(neighbor) + 0.15f + 0.7f * float2(ringRandom(key * 19u), ringRandom(key * 43u));
      const float2 offset = position - center;
      const float rate = 1.5f + floor(8.0f * ringRandom(key * 71u)) * (1.0f / 16.0f);
      const float age = frac(Step.time * rate + ringRandom(key * 97u));
      const float distance = length(offset);
      const float behindFront = age * 0.78f - distance;
      // Eight texels cover one cell. Keep the wave band below the tile's Nyquist limit.
      if (behindFront > 0.0f && behindFront < 0.34f && distance > 0.001f) {
        const float envelope = smoothstep(0.0f, 0.07f, behindFront) * (1.0f - behindFront / 0.34f) * (1.0f - age);
        slope += (offset / distance) * (sin(behindFront * 19.0f) * envelope * 0.18f);
      }
    }
  }
  Output[thread] = slope * Step.amplitude;
}
