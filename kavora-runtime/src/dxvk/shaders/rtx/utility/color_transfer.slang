// SPDX-FileCopyrightText: Copyright (c) 2023 NVIDIA CORPORATION. All rights reserved.
// SPDX-License-Identifier: MIT
module color_transfer;

public static const float3 kBt709LuminanceWeights = float3(0.2126f, 0.7152f, 0.0722f);
public static const float kSRGBGamma = 2.2f;

[ForceInline]
public float calcBt709Luminance(float3 linearColor)
{
  return dot(linearColor, kBt709LuminanceWeights);
}

[ForceInline]
public float3 linearToGamma(float3 linearColor)
{
  return pow(linearColor, float3(1.0f / kSRGBGamma));
}

[ForceInline]
public float3 gammaToLinear(float3 gammaColor)
{
  return pow(gammaColor, float3(kSRGBGamma));
}
