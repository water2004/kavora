/* SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
module cloud_density;

import "rtx/utility/hanpi_cloud.slang";

#include "rtx/pass/cloud_noise.h"
#include "rtx/utility/cloud_noise.slangh"

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

[ForceInline]
public float3 cloudCoordinates(CloudDensityArgs args, float3 position)
{
  const float3 drift = position - args.windOffset;
  const float3 local = args.up.z > 0.5f ? drift.xzy : drift;
  const float2 horizontal = local.xz / args.shapeScale;
  // All noise volumes share this period. Wrap before adding the billow phase
  // so translating the weather origin does not change texture interpolation.
  const float period = float(kCloudWeatherPeriod);
  const float2 wrapped = horizontal - period * floor(horizontal / period + 0.5f);
  return float3(wrapped.x, cloudAltitude(args, position) / args.shapeScale, wrapped.y);
}

[ForceInline]
float cloudNoiseLod(float footprint, float period, float resolution)
{
  return max(0.0f, log2(max(footprint * resolution / period, 1.0f)));
}

// Interpolate mip levels in float32 to retain smooth changes in the footprint.
[ForceInline]
float4 cloudVolumeSample(Sampler3D<float4> volume, float3 uv, float lod, uint resolution)
{
  const float level = min(floor(lod), log2(float(resolution)));
  const float fraction = saturate(lod - level);
  const float4 a = volume.SampleLevel(uv, level);
  if (fraction <= 0)
  {
    return a;
  }
  return lerp(a, volume.SampleLevel(uv, level + 1.0f), fraction);
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

public struct CloudColumn
{
  public float coverage;
  public float type;
  public float stratus;
  public float topScale;
  public float radialDistance;
  public float cellThickness;
  public float softness;
}

[ForceInline]
float2 weatherCoordinates(float2 position)
{
  return frac(position / float(kCloudWeatherPeriod) + 0.5f);
}

[ForceInline]
float4 cloudMapSample(Sampler2DArray<float4> map, float2 uv, uint layer, float lod, uint resolution = kCloudWeatherResolution)
{
  const float last = log2(float(resolution));
  const float baseMip = log2(float(kCloudHighWeatherResolution) / float(resolution));
  const float level = clamp(floor(lod), 0.0f, last);
  const float next = min(level + 1, last);
  const float blend = saturate(lod - level);
  // Each profile mip has its own half-texel clamp. Weather tiles wrap.
  const float inset = layer == 2 ? 0.5f * exp2(level) / resolution : 0;
  const float2 firstUV = layer == 2 ? clamp(uv, inset, 1 - inset) : uv;
  const float4 first = map.SampleLevel(float3(firstUV, layer), level + baseMip);
  if (blend == 0 || level == next)
    return first;
  const float nextInset = min(0.5f, 2 * inset);
  const float2 nextUV = layer == 2 ? clamp(uv, nextInset, 1 - nextInset) : uv;
  return lerp(first, map.SampleLevel(float3(nextUV, layer), next + baseMip), blend);
}

[ForceInline]
public CloudColumn cloudColumn(CloudDensityArgs args, float2 position, Sampler2DArray<float4> weatherSampler,
  float footprint = 0)
{
  const float lod = cloudNoiseLod(footprint, args.shapeScale * kCloudWeatherPeriod, kCloudWeatherResolution);
  const float2 uv = weatherCoordinates(position);
  const float4 low = cloudMapSample(weatherSampler, uv, 0, lod);
  const float softness = cloudMapSample(weatherSampler, uv, 1, lod).b;
  const float bias = (args.coverage - 0.45f) * 0.9f + 0.51f * args.weather
    + 0.15f * args.thunder - 0.17f * args.night * (1.0f - args.weather);
  const float raw = saturate(low.r + bias);
  CloudColumn column;
  column.stratus = low.b;
  column.coverage = raw * lerp(1.0f, low.a, column.stratus);
  column.type = saturate(low.g + args.thunder * (1.0f - low.g));
  column.topScale = 1.0f + raw;
  column.radialDistance = saturate(2.0f * length(uv - 0.5f));
  column.cellThickness = lerp(1.0f, low.a, column.stratus);
  column.softness = max(0.001f, 0.20f * (1.0f - softness));
  return column;
}

[ForceInline]
public float cloudColumnThickness(CloudDensityArgs args, CloudColumn column, Sampler2DArray<float4> weather)
{
  const float band = column.type == 0 ? 0.125f : (column.type <= 0.5f ? 0.5f : 0.875f);
  const float limit = cloudMapSample(weather, float2(band, column.radialDistance), 2, 0).a;
  return hanPiColumnSupport(args.thickness, column.topScale, limit);
}

[ForceInline]
float highCloudDensity(CloudDensityArgs args, float3 p, Sampler2DArray<float4> weatherSampler,
  float footprint, float heightFootprint)
{
  if (args.highThickness <= 0)
  {
    return 0;
  }
  const float height = (p.y * args.shapeScale - args.highBaseHeight) / args.highThickness;
  const float profileFootprint = heightFootprint / args.highThickness;
  if (args.highCoverage <= 0 || height + 0.5f * profileFootprint <= 0 || height - 0.5f * profileFootprint >= 1)
  {
    return 0;
  }
  const float weatherLod = cloudNoiseLod(footprint, args.shapeScale * float(kCloudHighWeatherPeriod), float(kCloudHighWeatherResolution));
  const float4 weather = cloudMapSample(weatherSampler, weatherCoordinates(p.xz), 1, weatherLod, kCloudHighWeatherResolution);
  const float coverage = saturate(args.highCoverage);
  const float mask = weather.r * coverage * (2.0f - coverage);
  const HanPiHighColumn column = hanPiHighColumn(mask, weather.g, weather.a);
  return column.density * hanPiHighBand(column, height, profileFootprint);
}

[ForceInline]
public float cloudDensityInColumn(CloudDensityArgs args, float3 coordinates, CloudColumn column,
  Sampler2DArray<float4> weatherSampler, Sampler3D<float4> baseSampler, Sampler3D<float4> detailSampler, float footprint, float heightFootprint,
  bool probe = false, float bodyMipOffset = 0.0f)
{
  const float heightAboveBase = coordinates.y * args.shapeScale - args.baseHeight;
  if (args.thickness <= 0 || column.coverage <= 0 || heightAboveBase + 0.5f * heightFootprint <= 0
    || heightAboveBase - 0.5f * heightFootprint >= args.thickness)
  {
    return 0;
  }

  const float slabHeight = saturate(heightAboveBase / args.thickness);
  const float profileHeight = hanPiLowProfileHeight(slabHeight, column.topScale, column.stratus, 0.30f);
  const float profileLod = min(log2(float(kCloudWeatherResolution)), cloudNoiseLod(heightFootprint,
    args.thickness, kCloudWeatherResolution));
  const float3 profiles = cloudMapSample(weatherSampler, float2(profileHeight, column.radialDistance), 2, profileLod).rgb;
  const float profile = lerp(hanPiTypeValue(profiles, column.type), profiles.r, column.stratus);
  if (profile <= 0)
  {
    return 0;
  }

  const float bodyPeriod = float(kCloudBasePeriod);
  const float3 shapePosition = float3(coordinates.x, heightAboveBase / args.shapeScale, coordinates.z);
  const float lod = cloudNoiseLod(footprint, args.shapeScale * bodyPeriod, float(kCloudBaseResolution)) + bodyMipOffset;
  const float4 body = cloudVolumeSample(baseSampler, shapePosition / bodyPeriod, lod, kCloudBaseResolution);
  const float bottomNoiseFade = saturate(profileHeight / 0.04f);
  const float baseShape = hanPiLowBodyShape(body.r, bottomNoiseFade);
  const float wispReach = 0.06f * profile;
  const float edge = column.softness;
  const float edgeSoftness = sqrt(edge * edge + 3.0f * body.g * profile * profile);

  if (probe)
  {
    // Expand the cheap probe by its unresolved body support.
    const float upper = hanPiLowBodyShape(body.r + sqrt(3.0f * max(body.g, 0.0f)), bottomNoiseFade);
    return hanPiLowCloudDensity(upper, profile, column.coverage, slabHeight,
      0, 0, 0, edgeSoftness, wispReach, 0.28f, 0.52f, 1.0f);
  }
  if (baseShape * profile + wispReach <= 1.0f - column.coverage)
  {
    return 0;
  }

  const float detailPeriod = bodyPeriod * 0.0625f;
  const float detailLod = cloudNoiseLod(footprint, args.shapeScale * detailPeriod, float(kCloudDetailResolution));
  const float4 detail = cloudVolumeSample(detailSampler, shapePosition * float3(1, -1, 1) / detailPeriod, detailLod, kCloudDetailResolution);
  const float billowy = dot(detail.ba, float2(0.65f, 0.35f)) * bottomNoiseFade;
  const float wispy = dot(detail.rg, float2(0.65f, 0.35f)) * bottomNoiseFade;
  const float strength = lerp(hanPiTypeValue(float3(0.18f, 0.145f, 0.11f), column.type), 0.08f, column.stratus);
  const float density = hanPiLowCloudDensity(baseShape, profile, column.coverage, slabHeight, billowy, wispy,
    strength, edgeSoftness, wispReach, 0.28f, 0.52f, 1.0f);
  return density * column.cellThickness * (0.65f + 0.35f * column.coverage);
}

[ForceInline]
public float cloudDensity(CloudDensityArgs args, float3 position, Sampler2DArray<float4> weatherSampler,
  Sampler3D<float4> baseSampler, Sampler3D<float4> detailSampler, float footprint = 0, uint layer = 0,
  float heightFootprint = 0, bool probe = false, float bodyMipOffset = 0.0f)
{
  const float3 p = cloudCoordinates(args, position);
  if (layer == 1)
  {
    return highCloudDensity(args, p, weatherSampler, footprint, heightFootprint);
  }
  const float heightAboveBase = p.y * args.shapeScale - args.baseHeight;
  if (heightAboveBase + 0.5f * heightFootprint <= 0 || heightAboveBase - 0.5f * heightFootprint >= args.thickness)
  {
    return 0;
  }
  const CloudColumn column = cloudColumn(args, p.xz, weatherSampler, footprint);
  return cloudDensityInColumn(args, p, column, weatherSampler, baseSampler, detailSampler, footprint, heightFootprint, probe, bodyMipOffset);
}
