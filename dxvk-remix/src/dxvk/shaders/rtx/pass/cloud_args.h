/* SPDX-License-Identifier: MIT */
#pragma once

#include "rtx/utility/shader_types.h"

struct CloudLight {
  vec3 directionToLight;
  float padding0;
  vec3 irradiance;
  float padding1;
};

struct CloudArgs {
  CloudLight lights[2];
  vec3 up;
  uint enable;
  vec3 windOffset;
  float baseHeight;
  float thickness;
  float shapeScale;
  float extinction;
  float coverage;
  float maxDistance;
  float albedo;
  uint steps;
  uint lightSteps;
  float anisotropy;
  float multipleScattering;
  float weather;
  float thunder;
  vec3 groundReference;
  float planetRadius;
  uint transportMode;
  uint skyResolution;
  float night;
  uint padding;
};

#ifdef __cplusplus
static_assert(sizeof(CloudArgs) == 176);
#endif
