// SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
// SPDX-License-Identifier: MIT
#include "rtx/pass/particles/particle_spawn_distribution_build.slangh"
#include "rtx/pass/particles/particle_screen_size.h"
#include "rtx/algorithm/rtxdi/rtxdi_temporal_history.h"
#include "rtx/utility/shader_types.slangh"

StructuredBuffer<float> weights;
RWStructuredBuffer<float> distribution;
StructuredBuffer<float> table;
RWStructuredBuffer<uint> samples;
RWStructuredBuffer<float4> policies;

[shader("compute")]
[numthreads(kParticleAreaBlockSize, 1, 1)]
void build(uint group : SV_GroupID, uint lane : SV_GroupIndex) {
  const uint index = group * kParticleAreaBlockSize + lane;
  const float cdf = inclusiveAreaPrefix(index < 130 ? weights[index] : 0.0f, lane);
  if (index < 130) {
    distribution[index] = cdf;
  }
  if (lane == kParticleAreaBlockSize - 1) {
    distribution[130 + group] = cdf;
  }
}

[shader("compute")]
[numthreads(kParticleAreaBlockSize, 1, 1)]
void finalize(uint lane : SV_GroupIndex) {
  finishParticleAreaBlocks(distribution, 130, 2, lane);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void sample(uint index : SV_DispatchThreadID) {
  if (index < 6000) {
    samples[index] = sampleParticleArea(table, 0, 130, (float(index) + 0.5f) / 6000.0f);
  }
  if (index == 0) {
    const ParticleScreenSizeMeasurement size = measureParticleScreenSize(
      float4(0.0f, 0.0f, 0.0f, 2.0f), float4(0.1f, 0.0f, 0.0f, 0.0f),
      float4(0.0f, 0.1f, 0.0f, 0.0f), float2(800.0f, 600.0f));
    policies[0] = float4(size.projectedSize, adjustParticleScreenSize(size.projectedSize, 40.0f, 0.0f, 1.0f).scale,
      particleScreenSizeOpacity(2.0f, 1.0f, 1.0f, 2.0f), temporalHistoryConfidence(false, 0.75f, 0.125f));
    policies[1] = float4(float(temporalHistoryUsable(true, kTemporalHistoryRejected)),
      float(temporalHistoryHasStaticCamera(true, kTemporalHistoryStaticCamera)),
      float(temporalHistoryCanDiscardEnlarged(true, kTemporalHistoryMovingCamera)),
      temporalHistoryConfidence(true, 0.75f, 0.125f));
  }
}

// Stub the reservoir algorithm while running the production acceptance and writeback adapter.
struct RTXDI_Reservoir { uint selections; };
struct RAB_Surface { uint unused; };
struct RAB_RandomSamplerState { uint unused; };
struct RTXDI_TemporalResamplingParameters { uint unused; };
struct TestOptions { bool rtxdiGradientResourcesAllocated; };
static const TestOptions cb = { true };
static const float kReprojectionConfidenceHistoryLength = 8.0f;
RWTexture2D<float> ReprojectionConfidence;
RWTexture2D<uint> TemporalPosition;
RWStructuredBuffer<uint> selections;

void RTXDI_TemporalResampling(inout RTXDI_Reservoir reservoir, uint2 pixel, RAB_Surface surface,
  inout RAB_RandomSamplerState rng, RTXDI_TemporalResamplingParameters parameters, out int2 previous, out bool similar) {
  ++reservoir.selections;
  previous = int2(2, 0);
  similar = true;
}

#include "rtx/algorithm/rtxdi/rtxdi_temporal_history.slangh"

[shader("compute")]
[numthreads(2, 1, 1)]
void history(uint2 pixel : SV_DispatchThreadID) {
  RTXDI_Reservoir reservoir = { 7 };
  RAB_RandomSamplerState rng = {};
  resampleTemporalHistory(pixel.x == 0, reservoir, pixel, RAB_Surface(0), rng, RTXDI_TemporalResamplingParameters(0));
  selections[pixel.x] = reservoir.selections;
}
