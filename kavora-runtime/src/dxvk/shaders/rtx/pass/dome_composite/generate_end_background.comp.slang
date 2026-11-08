/* SPDX-License-Identifier: LGPL-3.0-or-later */
#include "rtx/pass/dome_composite/caustica_end.slangh"

layout(r11f_g11f_b10f, binding = 0)
RWTexture2D<float4> EndBackground;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint2 pixel : SV_DispatchThreadID) {
  const uint2 extent = uint2(4096, 2048);
  if (any(pixel >= extent)) {
    return;
  }
  const float2 uv = (float2(pixel) + .5f) / float2(extent);
  const float theta = uv.y * pi;
  const float phi = (uv.x - .5f) * twoPi;
  const float3 direction = float3(sin(theta) * sin(phi), cos(theta), sin(theta) * cos(phi));
  EndBackground[pixel] = float4(endStarfield(direction, 0) + endNebula(direction, 0), 1);
}
