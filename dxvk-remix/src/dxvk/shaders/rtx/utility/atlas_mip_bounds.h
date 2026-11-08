/* SPDX-License-Identifier: MIT */
#pragma once

#ifdef __cplusplus
#include <algorithm>
#include <cmath>
#endif

namespace AtlasSampling {
#ifdef __cplusplus
  using std::ceil;
  using std::exp2;
  using std::floor;
  using std::log2;
  using std::max;
  using std::min;
#endif

  // Displacement is expressed relative to one texture tile. Tangent generation
  // removes the common UV scale, so an atlas tile must restore that scale.
  inline float displacementScale(float minU, float minV, float maxU, float maxV) {
    return maxU > minU && maxV > minV ? min(maxU - minU, maxV - minV) : 1.f;
  }

  // A globally generated atlas mip is usable only where its complete base-level
  // texel footprint is inside the sprite. Padding does not imply mip alignment.
  inline float maximumMip(float minU, float minV, float maxU, float maxV, float width, float height) {
    float extent = min((maxU - minU) * width, (maxV - minV) * height);
    float mip = floor(log2(max(extent, 1.f)));
    float cell = exp2(mip);
    if (ceil(minU * width / cell) >= floor(maxU * width / cell)
        || ceil(minV * height / cell) >= floor(maxV * height / cell)) {
      mip = max(mip - 1.f, 0.f);
    }
    return mip;
  }

  inline float firstCenter(float minimum, float textureSize, float mip) {
    float cell = exp2(mip);
    return (ceil(minimum * textureSize / cell) + 0.5f) * cell / textureSize;
  }

  inline float lastCenter(float maximum, float textureSize, float mip) {
    float cell = exp2(mip);
    return (floor(maximum * textureSize / cell) - 0.5f) * cell / textureSize;
  }
}
