// SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
// SPDX-License-Identifier: MIT
#pragma once

#ifdef __cplusplus
namespace dxvk {
#endif

static const int kTemporalHistoryOrdinary = 0;
static const int kTemporalHistoryStaticCamera = 1;
static const int kTemporalHistoryMovingCamera = 2;
static const int kTemporalHistoryRejected = -1;

inline bool temporalHistoryUsable(bool cameraActive, float tag) {
  return !cameraActive || tag >= 0.0f;
}

inline bool temporalHistoryHasStaticCamera(bool cameraActive, float tag) {
  return cameraActive && tag == kTemporalHistoryStaticCamera;
}

inline bool temporalHistoryCanDiscardEnlarged(bool cameraActive, float tag) {
  return !cameraActive || tag == kTemporalHistoryOrdinary;
}

inline float temporalHistoryConfidence(bool usable, float previous, float increment) {
  if (!usable) {
    return 0.0f;
  }
  const float confidence = previous + increment;
  return confidence < 1.0f ? confidence : 1.0f;
}

#ifdef __cplusplus
}
#endif
