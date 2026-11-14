// SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
// SPDX-License-Identifier: MIT
#pragma once

#include "rtx/utility/shader_types.h"
#include "rtx/pass/water_sim/water_sim_binding_indices.h"

#define WATER_EFFECT_RIPPLES 1u
#define WATER_EFFECT_WIND 2u
#define WATER_EFFECT_RINGS 4u
#define WATER_EFFECT_IMPACTS 8u
#define WATER_EFFECT_SLUDGE 16u

static const uint kWaterSimResolution = 256;
static const uint kWaterSimMaxImpulses = 16;
static const uint kWaterWindResolution = 64;
static const uint kWaterWindCascades = 2;
static const uint kWaterRingResolution = 128;
static const float kWaterWindTileLong = 32.0f;
static const float kWaterWindTileShort = 7.0f;
static const float kWaterRingTile = 12.0f;
static const float kWaterWindLoopSeconds = 600.0f;

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

struct WaterDetailStep {
  float time;
  float amplitude;
};

struct WaterImpactStep {
  ivec2 shift;
  vec2 origin;
  float cellSize;
  float delta;
  uint firstImpulse;
  uint impulseCount;
  uint effects;
  uint reset;
};
