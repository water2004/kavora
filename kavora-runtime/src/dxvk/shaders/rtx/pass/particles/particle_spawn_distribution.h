// SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
// SPDX-License-Identifier: MIT
#pragma once

#include "rtx/utility/shader_types.h"

#ifdef __cplusplus
#include <algorithm>
#include <cmath>
namespace dxvk {
using ParticleAreaTable = const float*;
#else
typedef StructuredBuffer<float> ParticleAreaTable;
#endif

static const uint kParticleAreaBlockSize = 128;
static const uint kInvalidParticleAreaOffset = 0xffffffffu;

inline uint particleAreaBlockCount(uint triangleCount) {
  return triangleCount / kParticleAreaBlockSize + (triangleCount % kParticleAreaBlockSize != 0);
}

inline float particleAreaBelow(float total) {
#ifdef __cplusplus
  return std::nextafter(total, 0.0f);
#else
  return asfloat(asuint(total) - 1u);
#endif
}

inline uint findParticleArea(ParticleAreaTable areas, uint start, uint count, float target) {
  uint end = start + count;
  while (start < end) {
    const uint middle = start + (end - start) / 2;
    if (areas[middle] <= target) {
      start = middle + 1;
    } else {
      end = middle;
    }
  }
  return start;
}

// One local inclusive CDF per block, followed by the inclusive CDF of block totals.
inline uint sampleParticleArea(ParticleAreaTable areas, uint start, uint triangles, float randomValue) {
  if (!triangles || start == kInvalidParticleAreaOffset || !(randomValue >= 0.0f && randomValue <= 1.0f)) {
    return kInvalidParticleAreaOffset;
  }
#ifdef __cplusplus
  using std::max;
  using std::min;
#endif
  const uint blockTotals = start + triangles;
  const uint blocks = particleAreaBlockCount(triangles);
  const float total = areas[blockTotals + blocks - 1];
  if (!(total > 0.0f)) {
    return kInvalidParticleAreaOffset;
  }

  const float target = min(randomValue * total, particleAreaBelow(total));
  const uint block = findParticleArea(areas, blockTotals, blocks, target) - blockTotals;
  if (block >= blocks) {
    return kInvalidParticleAreaOffset;
  }
  const uint first = block * kParticleAreaBlockSize;
  const uint count = min(kParticleAreaBlockSize, triangles - first);
  const float prior = block == 0 ? 0.0f : areas[blockTotals + block - 1];
  const float localTotal = areas[start + first + count - 1];
  if (!(localTotal > 0.0f)) {
    return kInvalidParticleAreaOffset;
  }

  const float localTarget = min(max(target - prior, 0.0f), particleAreaBelow(localTotal));
  const uint triangle = findParticleArea(areas, start + first, count, localTarget) - start;
  return triangle < first + count ? triangle : kInvalidParticleAreaOffset;
}

#ifdef __cplusplus
}
#endif
