/* SPDX-License-Identifier: MIT */
#pragma once

#include "rtx/utility/shader_types.h"

#ifdef __cplusplus
#define CLOUD_PARAMETER
#else
#define CLOUD_PARAMETER public
#endif

CLOUD_PARAMETER struct CloudLight {
  CLOUD_PARAMETER vec3 directionToLight;
  CLOUD_PARAMETER float padding0;
  CLOUD_PARAMETER vec3 irradiance;
  CLOUD_PARAMETER float padding1;
};

CLOUD_PARAMETER struct CloudArgs {
  CLOUD_PARAMETER CloudLight lights[2];
  CLOUD_PARAMETER vec3 up;
  CLOUD_PARAMETER uint enable;
  CLOUD_PARAMETER vec3 windOffset;
  CLOUD_PARAMETER float baseHeight;
  CLOUD_PARAMETER float thickness;
  CLOUD_PARAMETER float shapeScale;
  CLOUD_PARAMETER float extinction;
  CLOUD_PARAMETER float coverage;
  CLOUD_PARAMETER float maxDistance;
  CLOUD_PARAMETER float albedo;
  CLOUD_PARAMETER uint steps;
  CLOUD_PARAMETER uint lightSteps;
  CLOUD_PARAMETER float anisotropy;
  CLOUD_PARAMETER float multipleScattering;
  CLOUD_PARAMETER float weather;
  CLOUD_PARAMETER float thunder;
  CLOUD_PARAMETER vec3 groundReference;
  CLOUD_PARAMETER float planetRadius;
  CLOUD_PARAMETER uint transportMode;
  CLOUD_PARAMETER uint skyResolution;
  CLOUD_PARAMETER float night;
  CLOUD_PARAMETER uint padding;
  CLOUD_PARAMETER vec3 previousWindDelta;
  CLOUD_PARAMETER uint padding2;
};

#undef CLOUD_PARAMETER

#ifdef __cplusplus
static_assert(sizeof(CloudArgs) == 192);
#endif
