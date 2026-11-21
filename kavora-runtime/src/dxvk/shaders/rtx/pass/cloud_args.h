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
  CLOUD_PARAMETER uint hasRadianceProfile;
  // Surface irradiance when a profile exists; otherwise uniform illumination.
  CLOUD_PARAMETER vec3 irradiance;
  // Radians; small celestial disks share the central-axis far cloud shadow.
  CLOUD_PARAMETER float angularRadius;
  CLOUD_PARAMETER vec4 radianceProfile[4];
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
  CLOUD_PARAMETER uint phasePadding;
  CLOUD_PARAMETER float multipleScattering;
  CLOUD_PARAMETER float weather;
  CLOUD_PARAMETER float thunder;
  CLOUD_PARAMETER vec3 groundReference;
  CLOUD_PARAMETER float planetRadius;
  CLOUD_PARAMETER float wetness;
  CLOUD_PARAMETER float lightMaxDistance;
  CLOUD_PARAMETER vec3 previousWindDelta;
  CLOUD_PARAMETER float viewAngularFootprint;
  CLOUD_PARAMETER float highBaseHeight;
  CLOUD_PARAMETER float highThickness;
  CLOUD_PARAMETER float highExtinction;
  CLOUD_PARAMETER float highCoverage;
  CLOUD_PARAMETER float highMaxDistance;
  CLOUD_PARAMETER float groundAlbedo;
  // Keep the constant buffer aligned after the ground albedo.
  CLOUD_PARAMETER uint padding;
  CLOUD_PARAMETER uint activeCloudLights; // A bit for each populated directional light slot.
  CLOUD_PARAMETER float meterToWorld;
  CLOUD_PARAMETER uint highSteps;
  CLOUD_PARAMETER vec2 highMapOrigin;
  CLOUD_PARAMETER vec2 highLookupOrigin;
  CLOUD_PARAMETER vec3 previousHighWindDelta;
  CLOUD_PARAMETER uint highWindPadding;
};

#undef CLOUD_PARAMETER

#ifdef __cplusplus
static_assert(sizeof(CloudLight) == 96);
static_assert(sizeof(CloudArgs) == 384);
#endif
