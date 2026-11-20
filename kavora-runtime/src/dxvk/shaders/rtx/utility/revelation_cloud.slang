/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 HaringPro
 * SPDX-License-Identifier: Apache-2.0
 * Port of Revelation's Shape.glsl to Slang and Kavora's texture descriptors.
 * https://github.com/HaringPro/Revelation/blob/d5911393856d9dd97375b46a1a088e6b8fddd755/shaders/lib/atmosphere/clouds/Shape.glsl
 */
module revelation_cloud;

static const float kCloudDensityEpsilon = 0.001;

public struct RevelationField
{
  public Sampler2DArray<float4> map;
  public Sampler3D<float4> baseNoise;
  public Sampler3D<float4> curlNoise;
};

float linearstep(float low, float high, float x)
{
  return saturate((x - low) / (high - low));
}

public float2 revelationRotate(float2 position)
{
  const float goldenAngle = 2.39996322973;
  const float c = cos(goldenAngle);
  const float s = sin(goldenAngle);
  return float2(c * position.x - s * position.y, s * position.x + c * position.y);
}

public float revelationLowDensity(RevelationField field, float3 rayPos, float heightFraction,
  float thickness, float coverage, float wetness)
{
  const float2 cloudMap = field.map.SampleLevel(float3(rayPos.xz / 64000.0, 0), 0).rg;
  const float2 stepEdge = lerp(float2(0.7, 1.0) - coverage * 0.5,
    float2(0.1, 0.3), wetness * wetness);
  coverage = linearstep(stepEdge.x, stepEdge.y, cloudMap.x);
  if (coverage < 0.1)
  {
    return 0;
  }
  const float localCoverage = field.map.SampleLevel(float3(rayPos.xz / 512000.0 + 0.75, 0), 2).b;
  coverage *= linearstep(stepEdge.x, stepEdge.y * 0.8, localCoverage);

  heightFraction = saturate(heightFraction / cloudMap.x);
  // Iris defaults to linear filtering and edge clamp for this raw LUT.
  const float2 profileUV = clamp(float2(cloudMap.y, heightFraction), 0.5 / 32.0, 1.0 - 0.5 / 32.0);
  const float gradient = field.map.SampleLevel(float3(profileUV, 0), 5).x;
  const float dimensionalProfile = gradient * coverage;
  if (dimensionalProfile < 0.05)
  {
    return 0;
  }

  const float3 windDir = float3(cos(radians(20.0)), 0.5, sin(radians(20.0)));
  float3 noisePos = rayPos - windDir * heightFraction * thickness * 0.3;
  noisePos.y += dot(noisePos.xz, float2(0.2, 0.3));
  noisePos *= 1.0 / 2000.0;
  const float3 curlNoise = field.curlNoise.SampleLevel(noisePos * 2.0, 0).xyz;
  noisePos += curlNoise * gradient * (1.0 - coverage) * 0.3;

  const float noise = field.baseNoise.SampleLevel(noisePos, 0).x;
  const float erosion = (1.0 - noise * gradient) * 0.75;
  float density = dimensionalProfile - erosion;
  if (density < kCloudDensityEpsilon)
  {
    return 0;
  }
  const float heightFade = smoothstep(0.1, 0.5, heightFraction);
  density *= lerp(1.0, rsqrt(density), dimensionalProfile);
  return density * lerp(0.1, 1.0, heightFade);
}

public float revelationHighDensity(RevelationField field, float2 local, float2 mapOrigin,
  float2 lookupOrigin, float coverage)
{
  const float2 rayPos = local + mapOrigin;
  const float4 cloudMap = field.map.SampleLevel(float3(rayPos / 786000.0, 0), 1);
  const float localCoverage = cloudMap.w;
  if (localCoverage < kCloudDensityEpsilon)
  {
    return 0;
  }
  // Cirrus, cirrostratus and cirrocumulus are all enabled in Revelation.
  float3 types = saturate(cloudMap.xyz * (1.0 + coverage) - 0.5);
  types.z *= 2.0;
  types = types * types * types;
  if (dot(types, float3(1)) < 1e-6)
  {
    return 0;
  }

  const float2 curl = field.map.SampleLevel(float3(rayPos / 24000.0, 1), 1).xy;
  // Rotation is linear. Reduce its translation in double precision at the
  // world origin before evaluating the time-dependent repeating lookup.
  const float2 uv = revelationRotate(local * 0.001 + curl) * 0.05 + lookupOrigin - localCoverage * 0.1;
  float3 shapes = field.map.SampleLevel(float3(uv * 2.0, 1), 0).xyz;
  shapes = lerp(shapes * shapes, field.map.SampleLevel(float3(uv, 1), 0).xyz, cloudMap.xyz);
  return saturate(dot(shapes, types) * (localCoverage * localCoverage * 8.0));
}
