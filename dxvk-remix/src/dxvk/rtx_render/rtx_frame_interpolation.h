/*
 * Copyright (c) 2026, NVIDIA CORPORATION. All rights reserved.
 * SPDX-License-Identifier: MIT
 */
#pragma once

#include "rtx_camera.h"

namespace dxvk {
  // Immutable camera data consumed by NGX, without the renderer's matrix caches.
  struct RtxFrameGenerationCamera {
    Matrix4 viewToProjection;
    Matrix4 projectionToView;
    Matrix4 clipToPreviousClip;
    Vector2 jitter;
    Vector3 position;
    Vector3 up;
    Vector3 right;
    Vector3 direction;

    RtxFrameGenerationCamera() = default;

    explicit RtxFrameGenerationCamera(const RtCamera& camera)
      : viewToProjection(camera.getViewToProjection())
      , projectionToView(camera.getProjectionToView())
      , position(camera.getPosition())
      , up(camera.getUp())
      , right(camera.getRight())
      , direction(camera.getDirection()) {
      // Preserve NGX's single-precision conversion and multiplication order.
      const Matrix4 viewToWorld = camera.getViewToWorld();
      const Matrix4 previousWorldToView = camera.getPreviousWorldToView();
      const Matrix4 previousViewToProjection = camera.getPreviousViewToProjection();
      clipToPreviousClip = previousViewToProjection * previousWorldToView * viewToWorld * projectionToView;
      float offset[2];
      camera.getJittering(offset);
      jitter = Vector2(offset[0], offset[1]);
    }
  };
  static_assert(sizeof(RtxFrameGenerationCamera) <= 512, "Frame generation snapshots must remain compact");
}
