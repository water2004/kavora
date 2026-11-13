// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION
// SPDX-License-Identifier: MIT
module gbuffer_guide_math;

// These calculations are independent of G-buffer resources and pass bindings.

[ForceInline]
public float guideMathRoughnessFactor(float perceptualRoughness, float multiplier, float offset)
{
  return multiplier * pow(1.0f / (perceptualRoughness + 0.1f), offset);
}

[ForceInline]
public float guideMathOpacity(bool isOpaque, float opaqueOpacity)
{
  return isOpaque ? opaqueOpacity : 1.0f;
}

[ForceInline]
public float3 guideMathAdjustBaseReflectivity(float3 baseReflectivity, float opacity)
{
  return max(baseReflectivity, float3(1.0f - opacity));
}

[ForceInline]
public float3 guideMathScaleSpecularAlbedo(
  float3 specularAlbedo, float perceptualRoughness, float multiplier, float offset)
{
  return specularAlbedo * guideMathRoughnessFactor(perceptualRoughness, multiplier, offset);
}
