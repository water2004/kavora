/* SPDX-License-Identifier: MIT */
#pragma once

#ifdef __cplusplus
#include <algorithm>
#include <cmath>
namespace dxvk {
#endif

// Mean of exp(-max(height - baseHeight, 0) / falloff) over a line segment.
inline float heightFogSegmentDensity(float fromHeight, float toHeight, float baseHeight, float falloff) {
#ifdef __cplusplus
  using std::abs;
  using std::clamp;
  using std::exp;
  using std::max;
  using std::min;
#endif
  if (falloff <= 0.0f) {
    return 1.0f;
  }
  const float low = min(fromHeight, toHeight) - baseHeight;
  const float high = max(fromHeight, toHeight) - baseHeight;
  if (high <= 0.0f) {
    return 1.0f;
  }
  const float span = high - low;
  if (span < falloff * 0.001f) {
    return exp(-max((low + high) * 0.5f, 0.0f) / falloff);
  }
  const float below = clamp(-low, 0.0f, span);
  const float x = (span - below) / falloff;
  // Avoid cancellation for segments nearly parallel to the height plane.
  const float oneMinusExp = x < 0.01f ? x * (1.0f - x * (0.5f - x / 6.0f)) : 1.0f - exp(-x);
  const float integral = below + falloff * exp(-max(low, 0.0f) / falloff) * oneMinusExp;
  return clamp(integral / span, 0.0f, 1.0f);
}

#ifdef __cplusplus
}
#endif
