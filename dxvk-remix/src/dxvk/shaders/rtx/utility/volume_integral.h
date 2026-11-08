/* SPDX-License-Identifier: MIT */
#pragma once

#ifdef __cplusplus
#include "cpu_gpu_compat.h"
namespace dxvk {
#endif

// The largest float denotes a ray miss rather than a finite integration length.
inline float volumeTransmittance(float extinction, float distance) {
  if (extinction == 0.0f || distance == 0.0f) {
    return 1.0f;
  }
  if (distance >= 3.402823466e+38f) {
    return 0.0f;
  }
#ifdef __cplusplus
  return std::exp(-extinction * distance);
#else
  precise float opticalDepth = extinction * distance;
  return exp(-opticalDepth);
#endif
}

#ifndef __cplusplus
float3 volumeTransmittance(float3 extinction, float distance) {
  return float3(volumeTransmittance(extinction.x, distance),
    volumeTransmittance(extinction.y, distance), volumeTransmittance(extinction.z, distance));
}
#endif

// Integrates a constant source against extinction over one ray segment.
inline float volumeSourceIntegral(float extinction, float distance, float transmittance) {
  const float opticalDepth = extinction * distance;
  return opticalDepth < 0.01f
    ? distance * (1.0f - opticalDepth * (0.5f - opticalDepth / 6.0f))
    : (1.0f - transmittance) / extinction;
}

#ifdef __cplusplus
}
#include "cpu_gpu_compat_undef.h"
#endif
