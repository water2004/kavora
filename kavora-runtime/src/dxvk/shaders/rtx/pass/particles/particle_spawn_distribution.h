// SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
// SPDX-License-Identifier: MIT
#pragma once

static const uint kParticleAreaBlockSize = 128;
static const uint kInvalidParticleAreaOffset = 0xffffffffu;

inline uint particleAreaBlockCount(uint triangleCount) {
  return (triangleCount + kParticleAreaBlockSize - 1) / kParticleAreaBlockSize;
}
