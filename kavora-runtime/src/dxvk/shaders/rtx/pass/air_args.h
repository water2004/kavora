/* SPDX-License-Identifier: MIT */
#pragma once

#include "rtx/utility/shader_types.h"

#ifdef __cplusplus
#define AIR_PARAMETER
#else
#define AIR_PARAMETER public
#endif

AIR_PARAMETER struct AirLight {
  AIR_PARAMETER vec3 directionToLight;
  AIR_PARAMETER float angularRadius;
  AIR_PARAMETER vec3 irradiance;
  AIR_PARAMETER float padding;
};

// Optical lengths are kilometres and extinction is inverse kilometres.
// World positions are converted relative to the sea-level reference plane.
AIR_PARAMETER struct AirArgs {
  AIR_PARAMETER AirLight lights[2];
  AIR_PARAMETER vec3 up;
  AIR_PARAMETER float worldToKilometers;
  AIR_PARAMETER vec3 groundReference;
  AIR_PARAMETER float innerRadius;
  AIR_PARAMETER vec3 rayleighExtinction;
  AIR_PARAMETER float lowerDepth;
  AIR_PARAMETER float atmosphereHeight;
  AIR_PARAMETER float rayleighHeight;
  AIR_PARAMETER float mieHeight;
  AIR_PARAMETER float mieExtinction;
  AIR_PARAMETER float mieAlbedo;
  AIR_PARAMETER float mieAnisotropy;
  AIR_PARAMETER uint enable;
  AIR_PARAMETER uint transmittanceTextureIndex;
  AIR_PARAMETER uint scatteringTextureIndex;
  AIR_PARAMETER uvec2 transmittanceExtent;
  AIR_PARAMETER uint16_t scatteringSize;
  AIR_PARAMETER uint16_t tableSamplerIndex;
};

#undef AIR_PARAMETER

#ifdef __cplusplus
static_assert(sizeof(AirLight) == 32);
static_assert(sizeof(AirArgs) == 160);
#endif
