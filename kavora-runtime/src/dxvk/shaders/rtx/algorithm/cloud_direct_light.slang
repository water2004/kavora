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

public CloudDirectLightGrid cloudDirectLightGrid(CloudArgs args, bool farField)
{
  const float cellSize = args.shapeScale * (farField ? 0.25f : 0.0625f);
  const float2 wind = cloudLightHorizontal(args, args.windOffset - args.groundReference);
  // The lattice follows the advected density field between camera positions.
  return { wind + floor(-wind / cellSize + 0.5f) * cellSize, cellSize };
}

public uint cloudDirectLightHeightSamples(uint layer, bool farField)
{
  return layer == 1 ? CLOUD_DIRECT_LIGHT_HIGH_SAMPLES
    : farField ? CLOUD_DIRECT_LIGHT_LOW_FAR_SAMPLES : CLOUD_DIRECT_LIGHT_LOW_NEAR_SAMPLES;
}

public uint cloudDirectLightHeightOffset(uint layer, bool farField)
{
  return layer == 1 ? CLOUD_DIRECT_LIGHT_LOW_HEIGHT + (farField ? CLOUD_DIRECT_LIGHT_HIGH_SAMPLES : 0)
    : farField ? CLOUD_DIRECT_LIGHT_LOW_NEAR_SAMPLES : 0;
}

float cloudDirectLightWeight(float2 uv)
{
  const float2 edge = abs(uv - 0.5f);
  return 1.0f - smoothstep(0.375f, 0.5f, max(edge.x, edge.y));
}

float4 readCloudDirectLight(Sampler3D<float4> field, float2 uv, float height, uint layer, bool farField)
{
  const float count = float(cloudDirectLightHeightSamples(layer, farField));
  const float slice = clamp(height * count, 0.5f, count - 0.5f);
  const float y = (cloudDirectLightHeightOffset(layer, farField) + slice) / CLOUD_DIRECT_LIGHT_PACKED_HEIGHT;
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
  const CloudDirectLightGrid nearGrid = cloudDirectLightGrid(args, false);
  const float cellSize = max(nearGrid.cellSize, thickness / float(cloudDirectLightHeightSamples(layer, false)));
  // Short segments retain the resolved shadow detail when the viewer enters a cloud.
  const float distanceWeight = smoothstep(4.0f, 8.0f, distance / cellSize);
  if (distanceWeight == 0)
  {
    return 0;
  }

  const float height = (altitude - bottom) / thickness;
  const float2 horizontal = cloudLightHorizontal(args, position - args.groundReference);
  const float2 nearUV = nearGrid.uv(horizontal);
  const float nearWeight = cloudDirectLightWeight(nearUV);
  if (nearWeight == 1)
  {
    weight = distanceWeight;
    return readCloudDirectLight(field, nearUV, height, layer, false);
  }

  const float2 farUV = cloudDirectLightGrid(args, true).uv(horizontal);
  weight = distanceWeight * cloudDirectLightWeight(farUV);
  if (weight == 0)
  {
    return 0;
  }
  const float4 farLight = readCloudDirectLight(field, farUV, height, layer, true);
  return nearWeight > 0 ? lerp(farLight, readCloudDirectLight(field, nearUV, height, layer, false), nearWeight) : farLight;
}
