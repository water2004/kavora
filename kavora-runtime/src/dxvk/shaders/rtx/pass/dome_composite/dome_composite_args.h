/* SPDX-License-Identifier: MIT */
#pragma once

#include "rtx/utility/shader_types.h"
#include "rtx/utility/cpu_gpu_compat.h"

#define DOME_COMPOSITE_SOURCE 0
#define DOME_COMPOSITE_OUTPUT 1
#define DOME_COMPOSITE_NIGHT_FIELD 2
#define DOME_COMPOSITE_NIGHT_ARGS 3
#define DOME_COMPOSITE_GALAXY 4
#define DOME_COMPOSITE_TRANSMITTANCE 5
#define DOME_COMPOSITE_BACKGROUND 6

struct DomeCompositeArgs {
  mat4x3 worldToLight;
  mat4x3 outputToWorld;
  vec3 radiance;
  float opacity;
  uint width;
  uint height;
  uint flags;
  uint blendType;
};

static const uint kDomeFirstLayer = 1;
static const uint kDomeHasTexture = 2;
static const uint kDomeHasTransmittance = 4;
static const uint kDomeRectilinear = 8;
static const uint kDomeBackgroundOnly = 16;
static const uint kDomeSeparateBackground = 32;
static const uint kDomeLunarSurface = 64;
static const uint kDomeMoonPhaseShift = 8;

#ifdef __cplusplus
inline
#endif
float transmitDomeChannel(float background, float radiance, float transmittance, float opacity) {
  return (1.0f - opacity) * background + opacity * (radiance + transmittance * background);
}

// Global opacity interpolates the complete blend operation, so zero is neutral for every mode.
#ifdef __cplusplus
inline
#endif
float blendDomeChannel(float background, float foreground, float alpha, float opacity, uint blendType) {
  alpha = min(max(alpha, 0.0f), 1.0f);
  opacity = min(max(opacity, 0.0f), 1.0f);
  float color = min(max(foreground, 0.0f), 1.0f);
  float blended = foreground;
  switch (blendType) {
  case 0: blended = foreground * alpha + background * (1.0f - alpha); break;
  case 1: blended = foreground * alpha + background; break;
  case 2: blended = foreground * (1.0f - alpha) + background; break;
  case 3: blended = foreground * color + background * (1.0f - color); break;
  case 4: blended = foreground * color + background; break;
  case 5: blended = foreground * (1.0f - color) + background; break;
  case 6: blended = foreground + background; break;
  case 7: blended = foreground * background; break;
  case 8: blended = 2.0f * foreground * background; break;
  case 9: blended = foreground * (1.0f - alpha) + background * alpha; break;
  case 10: blended = foreground * (1.0f - color) + background * color; break;
  }
  return max(background + opacity * (blended - background), 0.0f);
}

#include "rtx/utility/cpu_gpu_compat_undef.h"
