// SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
// SPDX-License-Identifier: MIT
#include "rtx/pass/frame_generation/foreground_guide.h"

[[vk::binding(FG_FOREGROUND_INPUT)]] Texture2D<uint2> ForegroundGuide;
[[vk::binding(FG_SCENE_MOTION_INPUT)]] Texture2D<float2> SceneMotion;
[[vk::binding(FG_SCENE_DEPTH_INPUT)]] Texture2D<float> SceneDepth;
[[vk::binding(FG_MOTION_OUTPUT)]] RWTexture2D<float2> Motion;
[[vk::binding(FG_DEPTH_OUTPUT)]] RWTexture2D<float> Depth;

[shader("compute")]
[numthreads(16, 8, 1)]
void main(uint2 pixel : SV_DispatchThreadID) {
  uint2 extent;
  ForegroundGuide.GetDimensions(extent.x, extent.y);
  if (any(pixel >= extent)) return;
  const uint2 guide = ForegroundGuide.Load(int3(pixel, 0));
  if (guide.y != FG_FOREGROUND_INVALID_DEPTH) {
    Motion[pixel] = float2(f16tof32(guide.x & 0xffffu), f16tof32(guide.x >> 16));
    Depth[pixel] = asfloat(guide.y);
  } else {
    Motion[pixel] = SceneMotion.Load(int3(pixel, 0));
    Depth[pixel] = SceneDepth.Load(int3(pixel, 0));
  }
}
