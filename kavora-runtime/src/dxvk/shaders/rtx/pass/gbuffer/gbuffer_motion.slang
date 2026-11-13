/*
* Copyright (c) 2022-2026, NVIDIA CORPORATION. All rights reserved.
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
module gbuffer_motion;

import "rtx/concept/camera/camera.slang";
import "rtx/utility/quaternion.slang";
#include "rtx/algorithm/rtxdi/rtxdi_temporal_history.h"
#include "rtx/utility/shader_types.h"

[ForceInline]
public vec2 calcMotionVectorForRayMiss(Camera camera, uvec2 pixelCoordinate)
{
  // Note: Slightly incorrect to be using the current frame's camera for deriving the previous NDC values if the resolution changes between
  // frames, but this case shouldn't really be a major concern.
  const vec3 rayDirection = cameraPixelCoordinateToDirection(camera, pixelCoordinate, false).worldDirection;
  // Note: 0 used for w component here to represent a pure direction quantity (as misses are purely directional).
  vec4 prevNDC = mul(camera.prevWorldToProjection, vec4(rayDirection, 0.0f));
  prevNDC /= prevNDC.w;

  const vec2 ndc = cameraPixelCoordinateToNDC(camera, pixelCoordinate);

  return cameraCalcMotionVectorPixel(camera, ndc, prevNDC.xy);
}

public struct VirtualHitProjection
{
  public float depth;
  public float linearViewZ;
  public vec2 screenSpaceMotion;
  public int cameraHistory;
};

// Projects the hit along the unjittered camera ray. A hit at the eye has infinite depth.
[ForceInline]
public VirtualHitProjection projectVirtualHit(Camera camera, bool forceStaticSceneMotionVectors, uvec2 pixelCoordinate, float virtualHitDistance, vec3 virtualMotion)
{
  const CameraDirections rayDirections = cameraPixelCoordinateToDirection(camera, pixelCoordinate, false);
  const vec3 viewHitPosition = rayDirections.viewDirection * virtualHitDistance;
  const vec2 ndc = cameraPixelCoordinateToNDC(camera, pixelCoordinate);

  VirtualHitProjection projection;
  projection.cameraHistory = kTemporalHistoryOrdinary;
  projection.depth = cameraViewPosToDepth(camera, viewHitPosition);
  projection.linearViewZ = viewHitPosition.z;

  // Forced static-scene motion reprojects the precise NDC and depth rather than the imprecise world position.
  if (forceStaticSceneMotionVectors)
  {
    projection.screenSpaceMotion = cameraCalcStaticSceneMotionVectorPixel(camera, ndc, projection.depth);
  }
  else
  {
    // Keep the hit and motion relative to the eye to preserve sub-pixel precision near the camera.
    const vec3 eyeMotion = cameraGetPreviousWorldPosition(camera) - cameraGetWorldPosition(camera);
    const vec3 previousEyeToHit = rayDirections.worldDirection * virtualHitDistance + (virtualMotion - eyeMotion);
    const vec4 prevClip = mul(camera.prevWorldToProjection, vec4(previousEyeToHit, 0.0f));
    const vec2 prevNDC = prevClip.w == 0.0f ? vec2(0.0f) : prevClip.xy / prevClip.w;
    projection.screenSpaceMotion = cameraCalcMotionVectorPixel(camera, ndc, prevNDC);
  }

  return projection;
}

[ForceInline]
public vec3 projectViewModelOpticalMotion(Camera camera, uvec2 pixel, float apparentHitDistance, vec4 accumulatedRotation,
  vec3 worldPosition, vec3 motion)
{
  // Unfold motion within the hand rig before applying the previous camera pose.
  const vec3 eye = cameraGetWorldPosition(camera);
  const vec3 previousEye = cameraGetPreviousWorldPosition(camera);
  const vec3 currentPoint = worldPosition - eye;
  const vec3 previousPoint = worldPosition - previousEye + motion;
  const vec3 previousPointInView = mul(mat3(camera.prevWorldToView), previousPoint);
  const vec3 localMotion = mul(mat3(camera.viewToWorld), previousPointInView) - currentPoint;
  const vec3 virtualOffset = cameraPixelCoordinateToDirection(camera, ivec2(pixel), false).worldDirection * apparentHitDistance;
  const vec3 virtualMotion = quaternionTransformVector(accumulatedRotation, localMotion, true);
  const vec3 previousVirtualOffset = mul(mat3(camera.prevViewToWorld),
    mul(mat3(camera.worldToView), virtualOffset + virtualMotion));
  return previousEye - eye + previousVirtualOffset - virtualOffset;
}

[ForceInline]
public vec2 projectViewModelOpticalMiss(Camera camera, uvec2 pixel, vec3 finalDirection, vec4 accumulatedRotation)
{
  const vec3 incident = cameraPixelCoordinateToDirection(camera, ivec2(pixel), false).worldDirection;
  const vec3 directionInPreviousView = mul(mat3(camera.prevWorldToView), finalDirection);
  const vec3 localMotion = mul(mat3(camera.viewToWorld), directionInPreviousView) - finalDirection;
  const vec3 previousLocalDirection = incident + quaternionTransformVector(accumulatedRotation, localMotion, true);
  const vec3 previousDirection = mul(mat3(camera.prevViewToWorld), mul(mat3(camera.worldToView), previousLocalDirection));
  const vec4 clip = mul(camera.prevWorldToProjection, vec4(previousDirection, 0.0f));
  return clip.w > 0.0f ? cameraCalcMotionVectorPixel(camera, cameraPixelCoordinateToNDC(camera, pixel), clip.xy / clip.w)
    : calcMotionVectorForRayMiss(camera, pixel);
}
