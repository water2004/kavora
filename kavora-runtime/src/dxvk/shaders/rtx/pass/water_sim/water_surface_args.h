// SPDX-FileCopyrightText: Copyright (c) 2026 GBwater, Kavora contributors
// SPDX-License-Identifier: MIT
#pragma once

#include "rtx/utility/shader_types.h"

#ifdef __cplusplus
#define WATER_SURFACE_PUBLIC
#else
#define WATER_SURFACE_PUBLIC public
#endif

WATER_SURFACE_PUBLIC struct WaterSurfaceArgs {
  WATER_SURFACE_PUBLIC vec4 simulationClip; // XY origin, inverse extent, ripple strength.
  WATER_SURFACE_PUBLIC vec3 impactClip;
  WATER_SURFACE_PUBLIC uint effects;
  WATER_SURFACE_PUBLIC vec2 tileOrigin;
  WATER_SURFACE_PUBLIC float gustPhase;
  WATER_SURFACE_PUBLIC float rain;
};
#undef WATER_SURFACE_PUBLIC
