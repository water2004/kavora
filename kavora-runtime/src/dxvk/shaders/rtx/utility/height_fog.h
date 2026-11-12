/* SPDX-License-Identifier: MIT */
#pragma once

#ifdef __cplusplus
#include <algorithm>
#include <cmath>
namespace dxvk {
#endif

// Integral on one side of the density peak, starting at the nearer endpoint.
inline float heightFogSideIntegral(float nearestHeight, float span, float falloff) {
#ifdef __cplusplus
  using std::exp;
#endif
  if (falloff <= 0.0f || span <= 0.0f) {
    return span;
  }
  const float x = span / falloff;
  // Use the series near zero to avoid cancellation.
  const float integral = x < 0.01f
    ? span * (1.0f - x * (0.5f - x / 6.0f))
    : falloff * (1.0f - exp(-x));
  return exp(-nearestHeight / falloff) * integral;
}

// Mean density of two exponential profiles joined at their common peak.
// A zero scale means constant density on that side.
inline float heightFogSegmentDensity(float fromHeight, float toHeight, float baseHeight,
  float upperFalloff, float lowerFalloff = 0.0f) {
#ifdef __cplusplus
  using std::abs;
  using std::clamp;
  using std::exp;
  using std::max;
  using std::min;
#endif
  if (upperFalloff <= 0.0f && lowerFalloff <= 0.0f) {
    return 1.0f;
  }
  const float low = min(fromHeight, toHeight) - baseHeight;
  const float high = max(fromHeight, toHeight) - baseHeight;
  const float span = high - low;
  if (span == 0.0f) {
    const float falloff = low < 0.0f ? lowerFalloff : upperFalloff;
    return falloff > 0.0f ? exp(-abs(low) / falloff) : 1.0f;
  }
  const float below = clamp(-low, 0.0f, span);
  const float integral = heightFogSideIntegral(max(-high, 0.0f), below, lowerFalloff)
    + heightFogSideIntegral(max(low, 0.0f), span - below, upperFalloff);
  return clamp(integral / span, 0.0f, 1.0f);
}

#ifdef __cplusplus
}
#endif
