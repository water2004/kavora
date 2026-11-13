// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION. All rights reserved.
// SPDX-License-Identifier: MIT
module display_color_grade;

import "rtx/utility/color_transfer.slang";

// Receives linear post-tonemap RGB and returns linear graded RGB.
// The pass owns transfer encoding, dithering and the output alpha.
[ForceInline]
public float3 gradeDisplayColor(float3 color, float3 balance, float contrast, float saturation)
{
  color *= balance;
  color = saturate(contrast * (color - 0.18f) + 0.18f);
  return lerp(calcBt709Luminance(color).xxx, color, saturation);
}

// The inversion works in display values even when the caller exports a linear image.
[ForceInline]
public float3 invertDisplayColor(float3 linearColor, float amount, uint mode, float threshold)
{
  const float3 display = linearToGamma(saturate(linearColor));
  const float3 negative = 1.0f - display;
  const bool showNegative = mode == 0 || dot(display, float3(1.0f / 3.0f)) < threshold;
  return lerp(display, showNegative ? negative : float3(0.0f), amount);
}
