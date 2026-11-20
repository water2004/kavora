/* SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
module cloud_density;

import "rtx/utility/revelation_cloud.slang";

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
  public float wetness;
  public float2 highMapOrigin;
  public float2 highLookupOrigin;
  public float highBaseHeight;
  public float highThickness;
  public float highCoverage;
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

// Return the mean and central variance of a clamped, uniform-distribution ramp.
// Central moments retain constant fields without subtracting nearly equal squares.
[ForceInline]
public float2 cloudThresholdStatistics(float mean, float variance, float threshold, float rampWidth)
{
  const float value = mean - threshold;
  const float width = sqrt(3.0f * max(variance, 0.0f));
  const float ramp = max(rampWidth, 1e-4f);
  if (width <= 1e-4f)
  {
    return float2(saturate(value / ramp), 0);
  }
  if (value + width <= 0)
  {
    return 0;
  }
  if (value - width >= ramp)
  {
    return float2(1, 0);
  }
  if (value - width >= 0 && value + width <= ramp)
  {
    return float2(value / ramp, variance / (ramp * ramp));
  }
  if (value + width <= ramp)
  {
    const float tail = value + width;
    const float fraction = tail / (2.0f * width);
    const float maximum = tail / ramp;
    return float2(0.5f * fraction * maximum,
      fraction * maximum * maximum * (1.0f / 3.0f - 0.25f * fraction));
  }
  if (value - width >= 0)
  {
    const float tail = ramp - value + width;
    const float fraction = tail / (2.0f * width);
    const float maximum = tail / ramp;
    return float2(1.0f - 0.5f * fraction * maximum,
      fraction * maximum * maximum * (1.0f / 3.0f - 0.25f * fraction));
  }
  const float linearMass = ramp / (2.0f * width);
  const float upperMass = saturate((value + width - ramp) / (2.0f * width));
  const float lowerMass = saturate(1.0f - linearMass - upperMass);
  const float resultMean = upperMass + 0.5f * linearMass;
  const float lowerDelta = resultMean;
  const float upperDelta = 1.0f - resultMean;
  const float linearDelta = 0.5f - resultMean;
  const float resultVariance = lowerMass * lowerDelta * lowerDelta
    + upperMass * upperDelta * upperDelta
    + linearMass * (linearDelta * linearDelta + 1.0f / 12.0f);
  return float2(saturate(resultMean), max(resultVariance, 0.0f));
}

// Raw-moment variance uses two explicitly rounded operations on every target.
[ForceInline]
float cloudRawVariance(float mean, float secondMoment)
{
  precise float square = mean * mean;
  precise float difference = secondMoment - square;
  return max(difference, 0.0f);
}

[ForceInline]
public float2 cloudThresholdMoments(float mean, float secondMoment, float threshold, float rampWidth)
{
  const float2 statistics = cloudThresholdStatistics(mean, cloudRawVariance(mean, secondMoment),
    threshold, rampWidth);
  return float2(statistics.x, statistics.x * statistics.x + statistics.y);
}

[ForceInline]
public float cloudDensity(CloudDensityArgs args, float3 position, Sampler2DArray<float4> weatherSampler,
  Sampler3D<float4> baseSampler, Sampler3D<float4> detailSampler, uint layer)
{
  const float altitude = cloudAltitude(args, position);
  const RevelationField field = { weatherSampler, baseSampler, detailSampler };
  const float inverseScale = 1000.0 / args.shapeScale;
  if (layer == 1)
  {
    if (altitude < args.highBaseHeight || altitude > args.highBaseHeight + args.highThickness)
    {
      return 0;
    }
    const float2 horizontal = args.up.z > 0.5 ? position.xy : position.xz;
    return revelationHighDensity(field, horizontal * inverseScale, args.highMapOrigin, args.highLookupOrigin,
      args.highCoverage);
  }

  const float heightFraction = (altitude - args.baseHeight) / args.thickness;
  if (heightFraction <= 0 || heightFraction >= 1)
  {
    return 0;
  }
  const float3 p = (position - args.windOffset) * inverseScale;
  const float3 local = args.up.z > 0.5 ? p.xzy : p;
  // The source's Cartesian Y is planet-relative. Reduce its constant origin
  // (6,371,000 m + 256 m) modulo the 2,000 m alligator volume.
  const float3 rayPos = local + float3(0, 1256.0, 0);
  return revelationLowDensity(field, rayPos, heightFraction, args.thickness * inverseScale, args.coverage, args.wetness);
}
