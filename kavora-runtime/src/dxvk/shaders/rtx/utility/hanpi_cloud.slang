// SPDX-FileCopyrightText: Copyright (c) 2026 AshenOneArt
// SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors
// SPDX-License-Identifier: LicenseRef-HanPi-Volume-Cloud
// HanPi Volume Cloud: https://github.com/AshenOneArt/HPVolumeCloud
// Adapted from VolumetricClouds.hlsl, revision 27e799914493de9fa527179312ed72a39d08e225.
// Full license: LICENSES/HanPi-Volume-Cloud.txt
module hanpi_cloud;

public static const float kHanPiConeRatio = 2.0f;
public static const float kHanPiHighLightRangeMeters = 3000.0f;

[ForceInline]
public float hanPiTypeValue(float3 values, float type)
{
  return type < 0.5f ? lerp(values.x, values.y, type * 2.0f)
    : lerp(values.y, values.z, (type - 0.5f) * 2.0f);
}

// EvaluateCloudProperties stretches the top through a Möbius transform.
[ForceInline]
public float hanPiLowProfileHeight(float height, float topScale, float stratus, float stratusHeightScale)
{
  const float heightForLut = height / (1.0f + (topScale - 1.0f) * height);
  return lerp(heightForLut, saturate(height / stratusHeightScale), stratus);
}

[ForceInline]
public float hanPiColumnSupport(float thickness, float topScale, float profileLimit)
{
  return thickness * saturate(profileLimit / max(1e-4f, 1.0f - (topScale - 1.0f) * profileLimit));
}

[ForceInline]
float hanPiRemap(float x, float a, float b)
{
  return saturate((x - a) / max(b - a, 1e-4f));
}

// EvaluateCloudProperties leaves the condensation base to the weather profile.
[ForceInline]
public float hanPiLowBodyShape(float base, float bottomNoiseFade)
{
  return lerp(1.0f, pow(abs(base), 0.6f), bottomNoiseFade);
}

[ForceInline]
public float hanPiLowCloudDensity(float baseShape, float profile, float coverage, float slabHeight,
  float billowy, float wispy, float detailStrength, float edgeSoftness, float wispReach, float wispEdgeWidth,
  float wispTopHeight, float wispTopHardness)
{
  const float threshold = 1.0f - coverage;
  const float erodedBillowy = hanPiRemap(baseShape, billowy * detailStrength, 1.0f) * profile;
  const float erodedWispy = hanPiRemap(baseShape, wispy * detailStrength, 1.0f) * profile;
  const float wispyThreshold = threshold - wispReach;
  const float densityBillowy = hanPiRemap(erodedBillowy, threshold, threshold + edgeSoftness);
  const float wispyT = saturate((slabHeight - wispTopHeight) / max(1.0f - wispTopHeight, 0.001f));
  const float densityWispy = hanPiRemap(erodedWispy, wispyThreshold, wispyThreshold + edgeSoftness)
    * pow(1.0f - wispyT, max(wispTopHardness * 10.0f, 0.01f));
  const float billowyByShape = smoothstep(0.0f, wispEdgeWidth, densityBillowy);
  return lerp(densityWispy, densityBillowy, billowyByShape);
}

[ForceInline]
public float hanPiConeFirstStep(float length, uint steps, float meterToWorld)
{
  const float first = length * (kHanPiConeRatio - 1.0f) / max(pow(kHanPiConeRatio, float(steps)) - 1.0f, 1e-4f);
  return max(first, 5.0f * meterToWorld);
}

public struct HanPiHighColumn
{
  public float bottom;
  public float top;
  public float softEdge;
  public float density;
};

// EvaluateHighCloudDensity drives the band from coverage and gives Ac cells
// both thinner edges and lower density. The band fits Kavora's separate shell.
[ForceInline]
public HanPiHighColumn hanPiHighColumn(float coverage, float cellFactor, float weight)
{
  const float softEdge = 0.075f;
  const float halfThickness = (0.5f - softEdge) * coverage;
  const float cellThickness = 0.5f + 0.5f * cellFactor;
  return { 0.5f - halfThickness, 0.5f + halfThickness * cellThickness,
    min(softEdge, 0.5f * halfThickness),
    hanPiRemap(coverage, 0.025f, 0.025f + max(0.001f, 0.35f * (1 - weight))) * cellFactor };
}

[ForceInline]
float hanPiBandRampIntegral(float height, float edge, float softness)
{
  const float t = saturate((height - edge + softness) / (2.0f * softness));
  return softness * t * t * t * (2.0f - t) + max(height - edge - softness, 0.0f);
}

[ForceInline]
public float hanPiHighBand(HanPiHighColumn column, float height, float footprint)
{
  const float edge = max(column.softEdge, 1e-6f);
  if (footprint <= 1e-5f)
  {
    return smoothstep(column.bottom - edge, column.bottom + edge, height)
      - smoothstep(column.top - edge, column.top + edge, height);
  }
  // Integrate HanPi's separated smoothstep edges across each radial cell.
  const float first = height - 0.5f * footprint;
  const float last = height + 0.5f * footprint;
  const float bottom = hanPiBandRampIntegral(last, column.bottom, edge) - hanPiBandRampIntegral(first, column.bottom, edge);
  const float top = hanPiBandRampIntegral(last, column.top, edge) - hanPiBandRampIntegral(first, column.top, edge);
  return saturate((bottom - top) / footprint);
}

[ForceInline]
public float hanPiHighPowder(float density)
{
  return saturate(2.0f * (1.0f - exp(-4.0f * density)));
}
