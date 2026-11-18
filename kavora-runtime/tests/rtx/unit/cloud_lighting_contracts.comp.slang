// SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
// SPDX-License-Identifier: MIT
import "rtx/algorithm/clouds.slang";
import "rtx/algorithm/cloud_lighting_cache.slang";
import "rtx/pass/cloud_parameters.slang";
ConstantBuffer<CloudArgs> Clouds;
Sampler3D<float>.Handle Sun;
Sampler3D<float4> UnusedLocalLight;

CloudLightingCache directionalCache()
{
  const Sampler3D<float> depth = Sampler3D<float>(Sun);
  return { depth, depth, UnusedLocalLight, UnusedLocalLight };
}
StructuredBuffer<float4> Origins;
StructuredBuffer<float4> Directions;
RWStructuredBuffer<float4> Results;
uint Count;

[shader("compute")]
[numthreads(64, 1, 1)]
void depths(uint index : SV_DispatchThreadID)
{
  if (index >= Count)
  {
    return;
  }
  const CloudLightingCache cache = directionalCache();
  const float3 origin = Origins[index].xyz;
  const float3 endpoint = origin + Directions[index].xyz * Origins[index].w;
  float depth;
  const bool found = cloudCachedLightDepth(Clouds, cache, 0, origin, endpoint, depth);
  Results[index] = float4(depth, found ? 1.0f : 0.0f, 0, 0);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void cascadeDepths(uint index : SV_DispatchThreadID)
{
  if (index >= Count)
  {
    return;
  }
  const CloudLightingCache cache = directionalCache();
  const uint cascade = uint(Directions[index].w);
  const float3 origin = Origins[index].xyz;
  const float3 endpoint = origin + Directions[index].xyz * Origins[index].w;
  float depth;
  const bool found = cloudCachedLightDepth(Clouds, cache, 0, origin, endpoint, depth, cascade);
  const bool contained = cloudLightCascadeContains(Clouds, 0, cascade, origin, endpoint);
  Results[index] = float4(depth, found ? 1.0f : 0.0f, cloudLightingFarWeight(Clouds, origin),
    contained ? 1.0f : 0.0f);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void grids(uint index : SV_DispatchThreadID)
{
  if (index >= Count)
  {
    return;
  }
  const float4 input = Origins[index];
  const CloudRayGrid grid = createCloudRayGrid(float2(input.x, input.y), input.z, input.w, uint(Directions[index].x));
  float maximumError = 0;
  float previous = grid.interval.x;
  for (uint cell = 0; cell < grid.cells; cell++)
  {
    const float middle = grid.sampleAt(cell);
    maximumError = max(maximumError, abs(float(grid.cellAt(middle)) - float(cell)));
    const float boundary = grid.boundary(cell + 1);
    if (boundary <= previous)
    {
      maximumError = 100000;
    }
    previous = boundary;
  }
  Results[index] = float4(grid.cells, previous, maximumError, grid.boundary(1) - input.x);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void lightIndices(uint index : SV_DispatchThreadID)
{
  if (index >= Count)
  {
    return;
  }
  Results[index] = float4(cloudCachedLightIndex(Clouds, Origins[index].xyz), 0, 0, 0);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void energyParts(uint index : SV_DispatchThreadID)
{
  if (index >= Count)
  {
    return;
  }
  const CloudLightingCache cache = directionalCache();
  const float3 origin = Origins[index].xyz;
  const float3 middle = origin + Directions[index].xyz * (Origins[index].w * Directions[index].w);
  const float3 endpoint = origin + Directions[index].xyz * Origins[index].w;
  float local;
  float external;
  float total;
  const bool a = cloudCachedLightDepth(Clouds, cache, 0, origin, middle, local);
  const bool b = cloudCachedLightDepth(Clouds, cache, 0, middle, endpoint, external);
  const bool c = cloudCachedLightDepth(Clouds, cache, 0, origin, endpoint, total);
  Results[index] = float4(local, external, total, (a && b && c) ? 1.0f : 0.0f);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void reusedGrid(uint index : SV_DispatchThreadID)
{
  if (index >= Count)
  {
    return;
  }
  const CloudLightingCache cache = directionalCache();
  const uint cascade = uint(Directions[index].w);
  const float3 origin = Origins[index].xyz;
  const float3 endpoint = origin + Directions[index].xyz * Origins[index].w;
  const CloudLightGrid grid = cloudLightGrid(Clouds, 0, cascade);
  float referenceDepth;
  float reusedDepth;
  const bool referenceFound = cloudCachedLightDepth(Clouds, cache, 0, origin, endpoint,
    referenceDepth, cascade);
  const bool reusedFound = cloudCachedLightDepthInGrid(Clouds, cache, 0, grid, origin, endpoint,
    reusedDepth, cascade);
  const bool referenceContains = cloudLightCascadeContains(Clouds, 0, cascade, origin, endpoint);
  const bool reusedContains = cloudLightCascadeContainsInGrid(grid, origin, endpoint);
  Results[index] = float4(abs(referenceDepth - reusedDepth),
    referenceFound ? 1.0f : 0.0f, reusedFound ? 1.0f : 0.0f,
    referenceContains == reusedContains ? 1.0f : 0.0f);
}

float testCloudPhase(float cosine, float g)
{
  const float denominator = max(1.0f + g * g - 2.0f * g * cosine, 0.001f);
  return (1.0f - g * g) / (4.0f * 3.14159265359f * denominator * sqrt(denominator));
}

[shader("compute")]
[numthreads(64, 1, 1)]
void rayPhases(uint index : SV_DispatchThreadID)
{
  if (index >= Count)
  {
    return;
  }
  const float3 direction = normalize(Directions[index].xyz);
  const CloudRayPhase phase = createCloudRayPhase(Clouds, direction);
  float2 expected = 0;
  [unroll]
  for (uint light = 0; light < 2; light++)
  {
    bool active = any(Clouds.lights[light].irradiance > 0);
    if (Clouds.lights[light].hasRadianceProfile != 0)
    {
      [unroll]
      for (uint sample = 0; sample < 4; sample++)
      {
        active = active || any(Clouds.lights[light].radianceProfile[sample].rgb > 0);
      }
    }
    if (active)
    {
      const float cosine = dot(direction, Clouds.lights[light].directionToLight);
      expected[light] = 0.85f * testCloudPhase(cosine, Clouds.anisotropy)
        + 0.15f * testCloudPhase(cosine, -0.25f);
    }
  }
  Results[index] = float4(abs(phase.phase - expected), 0, 0);
}
