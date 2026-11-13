/*
* Copyright (c) 2022, NVIDIA CORPORATION. All rights reserved.
*
* Permission is hereby granted, free of charge, to any person obtaining a
* copy of this software and associated documentation files (the "Software"),
* to deal in the Software without restriction, including without limitation
* the rights to use, copy, modify, merge, publish, distribute, sublicense,
* and/or sell copies of the Software, and to permit persons to whom the
* Software is furnished to do so, subject to the following conditions:
*
* The above copyright notice and this permission notice shall be included in
* all copies or substantial portions of the Software.
*
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
* IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
* FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.  IN NO EVENT SHALL
* THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
* LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
* FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
* DEALINGS IN THE SOFTWARE.
*/
#pragma once

#include "rtx/utility/shader_types.h"
#ifdef __cplusplus
#include "rtx/concept/camera/volume_camera.h"
#define CAMERA_PARAMETER
#else
#define CAMERA_PARAMETER public
#endif

CAMERA_PARAMETER struct Camera
{
  CAMERA_PARAMETER mat4 worldToView;
  CAMERA_PARAMETER mat4 viewToWorld;
  CAMERA_PARAMETER mat4 viewToProjection;
  CAMERA_PARAMETER mat4 projectionToView;
  CAMERA_PARAMETER mat4 viewToProjectionJittered;
  CAMERA_PARAMETER mat4 projectionToViewJittered;
  CAMERA_PARAMETER mat4 worldToProjectionJittered;
  CAMERA_PARAMETER mat4 projectionToWorldJittered;
  CAMERA_PARAMETER mat4 translatedWorldToView;
  CAMERA_PARAMETER mat4 translatedWorldToProjectionJittered;
  CAMERA_PARAMETER mat4 projectionToTranslatedWorld;

  CAMERA_PARAMETER mat4 prevWorldToView;
  CAMERA_PARAMETER mat4 prevViewToWorld;
  CAMERA_PARAMETER mat4 prevWorldToProjection;
  CAMERA_PARAMETER mat4 prevWorldToProjectionJittered;
  CAMERA_PARAMETER mat4 prevProjectionToView;
  CAMERA_PARAMETER mat4 prevProjectionToViewJittered;
  CAMERA_PARAMETER mat4 prevTranslatedWorldToView;
  CAMERA_PARAMETER mat4 prevTranslatedWorldToProjection;

  CAMERA_PARAMETER mat4 projectionToPrevProjection;
  CAMERA_PARAMETER mat4 projectionToPrevProjectionJittered;

  CAMERA_PARAMETER uvec2 resolution;
  // Note: Near plane is not expressed with the same sign as the view space Z coordinates, so this
  // should be considered if ever mixing them.
  CAMERA_PARAMETER float nearPlane;
  CAMERA_PARAMETER uint flags;
};

#undef CAMERA_PARAMETER
