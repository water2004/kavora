/* SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
module cloud_density;

public struct CloudDensityArgs
{
  public float3 groundReference;
  public float3 up;
  public float planetRadius;
  public float3 windOffset;
  public float shapeScale;
  public float baseHeight;
  public float thickness;
  public float coverage;
  public float weather;
  public float thunder;
  public float night;
}

[ForceInline]
public float cloudAltitude(CloudDensityArgs args, float3 position)
{
  const float3 p = position - args.groundReference;
  const float h = dot(p, args.up);
  const float horizontalSquared = max(0.0f, dot(p, p) - h * h);
  const float radius = args.planetRadius;
  const float radial = sqrt((radius + h) * (radius + h) + horizontalSquared);
  return (h * (2.0f * radius + h) + horizontalSquared) / (radial + radius);
}

[ForceInline]
public float3 cloudCoordinates(CloudDensityArgs args, float3 position)
{
  const float3 drift = position - args.windOffset;
  const float3 local = args.up.z > 0.5f ? drift.xzy : drift;
  return float3(local.x, cloudAltitude(args, position), local.z) / args.shapeScale;
}

[ForceInline]
public float3 cloudColumn(CloudDensityArgs args, float2 position, Sampler2D<float4> weatherSampler)
{
  const float2 weather = weatherSampler.SampleLevel(position / 16.0f, 0).rg;
  const float bias = (args.coverage - 0.45f) * 0.9f - 0.18f + 0.51f * args.weather
    + 0.15f * args.thunder - 0.17f * args.night * (1.0f - args.weather);
  const float coverage = saturate(weather.x + bias);
  const float type = saturate(weather.y * (1.0f - 0.4f * args.weather) + 0.85f * args.thunder);
  const float thickness = args.thickness * ((140.0f + 460.0f * smoothstep(0.1f, 0.6f, type)
    + 900.0f * smoothstep(0.72f, 1.0f, type)) / 1500.0f);
  return float3(coverage, type, thickness);
}

[ForceInline]
public float cumulusDensity(CloudDensityArgs args, float3 position, Sampler2D<float4> weatherSampler,
  Sampler3D<float4> baseSampler, Sampler3D<float4> detailSampler)
{
  const float3 p = cloudCoordinates(args, position);
  const float heightAboveBase = p.y * args.shapeScale - args.baseHeight;
  if (heightAboveBase <= 0 || heightAboveBase >= args.thickness)
  {
    return 0;
  }
  const float3 column = cloudColumn(args, p.xz, weatherSampler);
  const float height = heightAboveBase / column.z;
  if (column.x <= 0 || height >= 1)
  {
    return 0;
  }
  const float profile = smoothstep(0.0f, 0.1f, height) * (1.0f - smoothstep(lerp(0.3f, 0.75f, column.y), 1.0f, height));
  const float anvil = smoothstep(0.75f, 1.0f, column.y) * smoothstep(0.8f, 0.9f, height)
    * (1.0f - smoothstep(0.95f, 1.0f, height));
  const float coverage = saturate(column.x * (1.0f - 0.35f * height * (1.0f - column.y)) + 0.5f * anvil * column.x);
  // A negative bound guarantees zero density for every possible noise shape.
  // Leave a margin at zero for differences in floating-point evaluation.
  if (profile - 1.0f + coverage < -1e-6f)
  {
    return 0;
  }
  const float4 base = baseSampler.SampleLevel(p, 0);
  const float lowFrequency = dot(base.gba, float3(0.625f, 0.25f, 0.125f));
  const float shape = saturate((base.r - lowFrequency + 1.0f) / (2.0f - lowFrequency));
  const float density = saturate((shape * profile - 1.0f + coverage) / max(coverage, 1e-4f)) * coverage;
  if (density <= 0)
  {
    return 0;
  }
  const float3 detail = detailSampler.SampleLevel(p * 4.0f, 0).rgb;
  const float billows = dot(detail, float3(0.625f, 0.25f, 0.125f));
  const float erosion = lerp(billows, 1.0f - billows, saturate(height * 4.0f)) * 0.35f;
  return saturate((density - erosion) / (1.0f - erosion));
}
