// SPDX-FileCopyrightText: Copyright (c) 2022 NVIDIA CORPORATION. All rights reserved.
// SPDX-License-Identifier: MIT
#pragma once

#include "rtx/utility/shader_types.h"

#ifdef __cplusplus
#define VOLUME_CAMERA_PARAMETER
#else
#define VOLUME_CAMERA_PARAMETER public
#endif

// Set when the camera looks down the negative view-space Z axis.
VOLUME_CAMERA_PARAMETER static const uint rightHandedFlag = 1 << 0;

VOLUME_CAMERA_PARAMETER struct VolumeDefinitionCamera
{
  VOLUME_CAMERA_PARAMETER mat4 viewToProjection;
  VOLUME_CAMERA_PARAMETER mat4 translatedWorldToView;
  VOLUME_CAMERA_PARAMETER mat4 translatedWorldToProjectionJittered;
  VOLUME_CAMERA_PARAMETER mat4 projectionToTranslatedWorld;
  VOLUME_CAMERA_PARAMETER mat4 prevTranslatedWorldToView;
  VOLUME_CAMERA_PARAMETER mat4 prevTranslatedWorldToProjection;

  VOLUME_CAMERA_PARAMETER vec3 translatedWorldOffset;
  VOLUME_CAMERA_PARAMETER float nearPlane;

  VOLUME_CAMERA_PARAMETER vec3 previousTranslatedWorldOffset;
  VOLUME_CAMERA_PARAMETER uint flags;

  VOLUME_CAMERA_PARAMETER float4 worldPlanes[6]; // [left, right, bottom, top, near, far]
};

#undef VOLUME_CAMERA_PARAMETER
