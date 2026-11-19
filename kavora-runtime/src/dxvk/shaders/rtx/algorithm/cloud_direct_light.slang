// SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors
// SPDX-License-Identifier: MIT
module cloud_direct_light;

import "rtx/pass/cloud_parameters.slang";
import "rtx/algorithm/cloud_lighting_cache.slang";

#include "rtx/pass/clouds/cloud_lighting_cache.h"

public struct CloudDirectLightGrid
{
  public float2 center;
  public float cellSize;

  public float2 uv(float2 horizontal)
  {
    return (horizontal - center) / (CLOUD_DIRECT_LIGHT_RESOLUTION * cellSize) + 0.5f;
  }

  public float2 position(uint2 pixel)
  {
    return center + (float2(pixel) + 0.5f - 0.5f * CLOUD_DIRECT_LIGHT_RESOLUTION) * cellSize;
  }
};

public CloudDirectLightGrid cloudDirectLightGrid(CloudArgs args, uint cascade)
{
  const float viewRange = max(args.maxDistance, args.highMaxDistance);
  const float cellSize = cascade == 0 ? args.shapeScale / 16.0f
    : cascade == 1 ? args.shapeScale / 4.0f
    : max(0.5f * args.shapeScale, (1.5f / CLOUD_DIRECT_LIGHT_RESOLUTION) * viewRange);
  const float2 wind = cloudLightHorizontal(args, args.windOffset - args.groundReference);
  // The lattice follows the advected density field between camera positions.
  return { wind + floor(-wind / cellSize + 0.5f) * cellSize, cellSize };
}

public uint cloudDirectLightHeightSamples(uint layer, uint cascade)
{
  return layer == 1 ? CLOUD_DIRECT_LIGHT_HIGH_SAMPLES
    : cascade == 0 ? CLOUD_DIRECT_LIGHT_LOW_NEAR_SAMPLES
    : cascade == 1 ? CLOUD_DIRECT_LIGHT_LOW_MIDDLE_SAMPLES : CLOUD_DIRECT_LIGHT_LOW_FAR_SAMPLES;
}

public uint cloudDirectLightHeightOffset(uint layer, uint cascade)
{
  return layer == 1 ? CLOUD_DIRECT_LIGHT_LOW_HEIGHT + cascade * CLOUD_DIRECT_LIGHT_HIGH_SAMPLES
    : cascade == 0 ? 0
    : cascade == 1 ? CLOUD_DIRECT_LIGHT_LOW_NEAR_SAMPLES
    : CLOUD_DIRECT_LIGHT_LOW_NEAR_SAMPLES + CLOUD_DIRECT_LIGHT_LOW_MIDDLE_SAMPLES;
}

float cloudDirectLightWeight(float2 uv)
{
  const float2 edge = abs(uv - 0.5f);
  return 1.0f - smoothstep(0.375f, 0.5f, max(edge.x, edge.y));
}

float4 readCloudDirectLight(Sampler3D<float4> field, float2 uv, float height, uint layer, uint cascade)
{
  const float count = float(cloudDirectLightHeightSamples(layer, cascade));
  const float slice = clamp(height * count, 0.5f, count - 0.5f);
  const float y = (cloudDirectLightHeightOffset(layer, cascade) + slice) / CLOUD_DIRECT_LIGHT_PACKED_HEIGHT;
  return field.SampleLevel(float3(uv.x, y, uv.y), 0);
}

// Each light stores total and outside-column transmittance. Filter transmission
// itself so unresolved clear paths still contribute their incoming energy.
public float4 sampleCloudDirectLight(CloudArgs args, Sampler3D<float4> field, float3 position,
  float altitude, float distance, uint layer, out float weight)
{
  weight = 0;
  const float bottom = layer == 0 ? args.baseHeight : args.highBaseHeight;
  const float thickness = layer == 0 ? args.thickness : args.highThickness;
  const float cellSize = max(args.shapeScale / 16.0f, thickness / float(cloudDirectLightHeightSamples(layer, 0)));
  // Resolve small shadow structures around the observer.
  const float distanceWeight = smoothstep(4.0f, 8.0f, distance / cellSize);
  if (distanceWeight == 0)
  {
    return 0;
  }

  const float height = (altitude - bottom) / thickness;
  const float2 horizontal = cloudLightHorizontal(args, position - args.groundReference);
  float remaining = 1.0f;
  float4 energy = 0;
  [unroll]
  for (uint cascade = 0; cascade < CLOUD_DIRECT_LIGHT_CASCADES; cascade++)
  {
    const float2 uv = cloudDirectLightGrid(args, cascade).uv(horizontal);
    const float coverage = cloudDirectLightWeight(uv);
    if (coverage > 0)
    {
      energy += (remaining * coverage) * readCloudDirectLight(field, uv, height, layer, cascade);
      remaining *= 1.0f - coverage;
      if (remaining == 0)
      {
        break;
      }
    }
  }
  const float covered = 1.0f - remaining;
  weight = distanceWeight * covered;
  return energy / max(covered, 1e-6f);
}
