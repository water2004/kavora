// SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
// SPDX-License-Identifier: MIT
//!variant water_wind_rows.comp
//!variant water_wind_columns.comp WATER_FFT_COLUMNS
//!end-variants

#include "rtx/pass/water_sim/water_sim_args.h"

[[vk::push_constant]] ConstantBuffer<WaterDetailStep> Step;
[[vk::binding(WATER_DETAIL_BINDING_INPUT)]] StructuredBuffer<float4> Input;
#ifdef WATER_FFT_COLUMNS
[[vk::binding(WATER_DETAIL_BINDING_OUTPUT)]] RWTexture2DArray<float4> Output;
#else
[[vk::binding(WATER_DETAIL_BINDING_OUTPUT)]] RWStructuredBuffer<float4> Output;
#endif

groupshared float4 values[kWaterWindResolution];
static const float kTau = 6.28318530718f;

float2 multiplyComplex(float2 a, float2 b) {
  return float2(a.x * b.x - a.y * b.y, a.x * b.y + a.y * b.x);
}

uint spectrumIndex(uint2 bin, uint cascade) {
  return (cascade * kWaterWindResolution + bin.y) * kWaterWindResolution + bin.x;
}

[shader("compute")]
[numthreads(kWaterWindResolution, 1, 1)]
void main(uint3 group : SV_GroupID, uint lane : SV_GroupIndex) {
  const uint cascade = group.y;
#ifdef WATER_FFT_COLUMNS
  const uint2 bin = uint2(group.x, lane);
  float4 value = Input[spectrumIndex(bin, cascade)];
#else
  const uint2 bin = uint2(lane, group.x);
  const uint2 opposite = (kWaterWindResolution - bin) & (kWaterWindResolution - 1u);
  const float4 seed = Input[spectrumIndex(bin, cascade)];
  const float2 mirror = Input[spectrumIndex(opposite, cascade)].xy;
  float sine, cosine;
  sincos(seed.z * Step.time, sine, cosine);
  const float2 height = multiplyComplex(seed.xy, float2(cosine, sine)) +
    multiplyComplex(mirror * float2(1.0f, -1.0f), float2(cosine, -sine));
  const int2 signedBin = int2(bin + kWaterWindResolution / 2u) % int(kWaterWindResolution) - int(kWaterWindResolution / 2u);
  const float tile = cascade == 0 ? kWaterWindTileLong : kWaterWindTileShort;
  const float2 k = float2(signedBin) * (kTau / tile);
  // Pack the spectra of two real derivatives into one complex transform.
  const float2 slopes = multiplyComplex(height, float2(-k.y, k.x));
  float4 value = float4(height, slopes);
#endif

  values[reversebits(lane) >> 26] = value;
  GroupMemoryBarrierWithGroupSync();
  [unroll]
  for (uint span = 2; span <= kWaterWindResolution; span *= 2) {
    const uint halfSpan = span / 2;
    if (lane < kWaterWindResolution / 2u) {
      const uint offset = lane % halfSpan;
      const uint left = (lane / halfSpan) * span + offset;
      const uint right = left + halfSpan;
      float sine, cosine;
      sincos(kTau * float(offset) / float(span), sine, cosine);
      const float4 a = values[left];
      const float4 b = values[right];
      const float2 phase = float2(cosine, sine);
      const float4 rotated = float4(multiplyComplex(b.xy, phase), multiplyComplex(b.zw, phase));
      values[left] = a + rotated;
      values[right] = a - rotated;
    }
    GroupMemoryBarrierWithGroupSync();
  }
  value = values[lane];
#ifdef WATER_FFT_COLUMNS
  Output[uint3(bin, cascade)] = float4(value.x, value.zw, 0.0f);
#else
  Output[spectrumIndex(bin, cascade)] = value * Step.amplitude;
#endif
}
