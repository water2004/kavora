/* SPDX-License-Identifier: MIT */
#include "rtx/utility/shader_types.h"
#include "rtx/utility/math.slangh"

layout(r16f, binding = 0)
RWTexture2D<float> StarField;

uint skyHash(uint x) {
  x ^= x >> 16;
  x *= 0x7feb352du;
  x ^= x >> 15;
  x *= 0x846ca68bu;
  return x ^ (x >> 16);
}

float skyRandom(uint x) {
  return float(skyHash(x) >> 8) * (1.0f / 16777216.0f);
}

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint2 pixel : SV_DispatchThreadID) {
  const uint2 extent = uint2(4096, 2048);
  if (any(pixel >= extent)) return;
  // Density and the point-spread kernel both account for spherical area.
  const int2 cell = int2(pixel / 16);
  float stars = 0;
  for (int dy = -1; dy <= 1; dy++) {
    for (int dx = -1; dx <= 1; dx++) {
      const int2 neighbour = cell + int2(dx, dy);
      if (neighbour.y < 0 || neighbour.y >= 128) continue;
      const uint key = skyHash(uint((neighbour.x + 256) % 256) + uint(neighbour.y) * 256u);
      const float latitudeScale = sin((float(neighbour.y) + 0.5f) * pi / 128.0f);
      const float density = 0.35f * latitudeScale;
      if (skyRandom(key) > density) continue;
      const float2 center = (float2(neighbour) + float2(skyRandom(key + 1), skyRandom(key + 2))) * 16.0f;
      const float2 delta = float2(pixel) + 0.5f - center;
      const float2 variance = float2(0.0225f / max(latitudeScale * latitudeScale, 1e-4f), 0.0225f) + 0.125f;
      const float flux = 0.003f + 1.6f * pow(skyRandom(key + 4), 12.0f);
      const float normalization = 0.1475f / (latitudeScale * sqrt(variance.x * variance.y));
      stars += flux * normalization * exp(-0.5f * dot(delta * delta, 1.0f / variance));
    }
  }
  StarField[pixel] = stars;
}
