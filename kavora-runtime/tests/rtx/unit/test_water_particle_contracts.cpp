// SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
// SPDX-License-Identifier: MIT
#include <array>
#include <cmath>
#include <cstddef>
#include <iostream>
#include <limits>

#include "rtx/algorithm/rtxdi/rtxdi_temporal_history.h"
#include "rtx/pass/particles/particle_spawn_distribution.h"
#include "rtx/pass/water_sim/water_sim_args.h"

static_assert(WATER_SIM_BINDING_PREVIOUS != WATER_SIM_BINDING_CURRENT);
static_assert(WATER_SIM_BINDING_IMPULSES != WATER_SIM_BINDING_CURRENT);
static_assert(WATER_SIM_BINDING_IMPULSES != WATER_SIM_BINDING_PREVIOUS);
static_assert(WATER_DETAIL_BINDING_INPUT != WATER_DETAIL_BINDING_OUTPUT);
static_assert(sizeof(WaterSimImpulse) == 16);
static_assert(sizeof(WaterSimStep) == 40);
static_assert(sizeof(WaterImpactStep) == 40);
static_assert(offsetof(WaterImpactStep, firstImpulse) == 24);
static_assert(sizeof(WaterDetailStep) == 4);

bool checkAreaTable() {
  using namespace dxvk;
  constexpr uint triangles = 130;
  std::array<float, triangles + 2> table{};
  for (uint i = 0; i < 127; ++i) {
    table[i] = 1.0f;
  }
  table[127] = 4.0f;
  table[128] = table[129] = 2.0f;
  table[130] = 4.0f;
  table[131] = 6.0f;

  std::array<uint, triangles> counts{};
  for (uint i = 0; i < 6000; ++i) {
    const uint triangle = sampleParticleArea(table.data(), 0, triangles, (i + 0.5f) / 6000.0f);
    if (triangle >= triangles) {
      return false;
    }
    ++counts[triangle];
  }
  std::array<uint, triangles> expected{};
  expected[0] = 1000;
  expected[127] = 3000;
  expected[128] = 2000;
  if (counts != expected || sampleParticleArea(table.data(), 0, triangles, 0.0f) != 0 ||
      sampleParticleArea(table.data(), 0, triangles, 1.0f) != 128 ||
      sampleParticleArea(table.data(), 0, triangles, 4.0f / 6.0f) != 128) {
    return false;
  }
  table.fill(0.0f);
  return sampleParticleArea(table.data(), 0, triangles, 0.5f) == kInvalidParticleAreaOffset &&
    sampleParticleArea(nullptr, 0, 0, 0.5f) == kInvalidParticleAreaOffset &&
    sampleParticleArea(nullptr, kInvalidParticleAreaOffset, triangles, 0.5f) == kInvalidParticleAreaOffset &&
    sampleParticleArea(nullptr, 0, triangles, std::numeric_limits<float>::quiet_NaN()) == kInvalidParticleAreaOffset;
}

bool checkTemporalHistory() {
  using namespace dxvk;
  return !temporalHistoryUsable(true, kTemporalHistoryRejected) &&
    temporalHistoryUsable(false, kTemporalHistoryRejected) &&
    temporalHistoryUsable(true, kTemporalHistoryOrdinary) &&
    temporalHistoryHasStaticCamera(true, kTemporalHistoryStaticCamera) &&
    !temporalHistoryHasStaticCamera(true, kTemporalHistoryMovingCamera) &&
    !temporalHistoryHasStaticCamera(false, kTemporalHistoryStaticCamera) &&
    temporalHistoryCanDiscardEnlarged(true, kTemporalHistoryOrdinary) &&
    !temporalHistoryCanDiscardEnlarged(true, kTemporalHistoryMovingCamera) &&
    temporalHistoryConfidence(false, 0.75f, 0.125f) == 0.0f &&
    temporalHistoryConfidence(true, 0.75f, 0.125f) == 0.875f &&
    temporalHistoryConfidence(true, 0.95f, 0.125f) == 1.0f;
}

int main() {
  if (!checkAreaTable()) {
    std::cerr << "Particle CDF selection failed\n";
    return 1;
  }
  if (!checkTemporalHistory()) {
    std::cerr << "RTXDI history policy failed\n";
    return 1;
  }
  std::cout << "Particle CDF, RTXDI history and water binding contracts passed\n";
  return 0;
}
