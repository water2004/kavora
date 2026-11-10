// SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
// SPDX-License-Identifier: MIT
#pragma once

#include "rtx/utility/shader_types.h"

#define WATER_SIM_BINDING_PREVIOUS 0
#define WATER_SIM_BINDING_CURRENT 1
#define WATER_SIM_BINDING_IMPULSES 2

static const uint kWaterSimResolution = 256;
static const uint kWaterSimMaxImpulses = 16;

struct WaterSimStep {
  ivec2 shift;
  vec2 origin;
  float cellSize;
  float travel;
  float damping;
  float timeStep;
  uint impulseCount;
  uint reset;
};

struct WaterSimImpulse {
  vec2 position;
  float radius;
  float strength;
};
