// SPDX-FileCopyrightText: Copyright (c) 2023 NVIDIA CORPORATION. All rights reserved.
// SPDX-License-Identifier: MIT
module depth_slice;

[ForceInline]
public float viewZToDepthSlice(uint depthSlices, float depthSliceDistributionExponent, float maxDistance,
  float nearPlane, float viewZ, bool invertViewZ)
{
  const float viewZSign = invertViewZ ? -1.0f : 1.0f;
  const float positiveViewZ = viewZ * viewZSign - nearPlane;
  return pow(saturate(positiveViewZ / maxDistance), 1.0f / depthSliceDistributionExponent) * depthSlices;
}

// Coordinates may fall outside the texture. The caller selects the sampling policy.
[ForceInline]
public float3 translatedWorldPositionToFroxelUVW(float4x4 translatedWorldToView, float4x4 translatedWorldToProjection,
  bool invertViewZ, uint depthSlices, float depthSliceDistributionExponent, float maxDistance, float nearPlane,
  float3 translatedWorldPosition)
{
  const float viewZ = mul(translatedWorldToView, float4(translatedWorldPosition, 1.0f)).z;
  const float depthSlice = viewZToDepthSlice(
    depthSlices, depthSliceDistributionExponent, maxDistance, nearPlane, viewZ, invertViewZ);
  const float4 projectionPosition = mul(translatedWorldToProjection, float4(translatedWorldPosition, 1.0f));
  const float2 ndc = projectionPosition.xy / projectionPosition.w;
  const float2 froxelUV = ndc * float2(0.5f, -0.5f) + float2(0.5f, 0.5f);
  const float froxelW = depthSlice / depthSlices;
  return float3(froxelUV, froxelW);
}

[ForceInline]
public float3 virtualFroxelUVWToPhysicalFroxelUVW(float3 froxelUVW, uint froxelVolume,
  float minFilteredRadianceU, float maxFilteredRadianceU, float inverseNumFroxelVolumes)
{
  // Clamp to the edge texel centers to avoid filtering across adjacent volumes.
  froxelUVW.x = clamp(froxelUVW.x, minFilteredRadianceU, maxFilteredRadianceU);
  froxelUVW.x = (froxelUVW.x + float(froxelVolume)) * inverseNumFroxelVolumes;
  return froxelUVW;
}
