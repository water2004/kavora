// SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors
// SPDX-License-Identifier: MIT
module cloud_neural_scattering;

import "rtx/pass/cloud_parameters.slang";
import "rtx/algorithm/cloud_lighting_cache.slang";
#include "rtx/pass/clouds/cloud_neural_scattering.h"

float cloudNeuralCellSize(CloudArgs args)
{
  return 8.0f * args.shapeScale / CLOUD_NEURAL_RESOLUTION;
}

public float3 cloudNeuralFrame(CloudArgs args, float3 vector)
{
  return args.up.z > 0.5f ? vector.xzy : vector;
}

float2 cloudNeuralGridCenter(CloudArgs args)
{
  const float2 wind = cloudNeuralFrame(args, args.windOffset - args.groundReference).xz;
  const float cellSize = cloudNeuralCellSize(args);
  return wind + floor(-wind / cellSize) * cellSize;
}

public float3 cloudNeuralPosition(CloudArgs args, uint3 index)
{
  const float2 horizontal = cloudNeuralGridCenter(args)
    + (float2(index.xz) + 0.5f - 0.5f * CLOUD_NEURAL_RESOLUTION) * cloudNeuralCellSize(args);
  const float altitude = args.baseHeight + args.thickness * float(index.y) / (CLOUD_NEURAL_HEIGHT - 1);
  return cloudLightPositionAtAltitude(args, horizontal, altitude);
}

// Polynomial basis and ordering used by the checkpoint's SH projection.
public struct CloudNeuralBasis
{
  public float4 linear;
  public float4 quadratic;
  public float zonal;
};

public CloudNeuralBasis cloudNeuralBasis(float3 direction)
{
  const float3 squared = direction * direction;
  return {
    float4(1, direction),
    float4(direction * direction.yzx, squared.x - squared.y),
    3.0f * squared.z - 1.0f
  };
}

public float cloudNeuralDiffuse(CloudArgs args, Sampler3D<float4> field, float3 position, float altitude,
  float3 direction, float analyticDiffuse)
{
  const float2 horizontal = cloudNeuralFrame(args, position - args.groundReference).xz - cloudNeuralGridCenter(args);
  const float2 uv = horizontal / (cloudNeuralCellSize(args) * CLOUD_NEURAL_RESOLUTION) + 0.5f;
  const float2 distance = abs(uv - 0.5f);
  const float weight = 1.0f - smoothstep(0.375f, 0.5f, max(distance.x, distance.y));
  if (weight <= 0)
  {
    return analyticDiffuse;
  }
  const float height = saturate((altitude - args.baseHeight) / args.thickness);
  const float v = (0.5f + height * (CLOUD_NEURAL_HEIGHT - 1)) / (CLOUD_NEURAL_HEIGHT * CLOUD_NEURAL_BANDS);
  const float4 linear = field.SampleLevel(float3(uv.x, v, uv.y), 0);
  const float4 quadratic = field.SampleLevel(float3(uv.x, v + 1.0f / CLOUD_NEURAL_BANDS, uv.y), 0);
  const float zonal = field.SampleLevel(float3(uv.x, v + 2.0f / CLOUD_NEURAL_BANDS, uv.y), 0).x;
  const CloudNeuralBasis basis = cloudNeuralBasis(direction);
  const float prediction = max(0.0f, dot(linear, basis.linear) + dot(quadratic, basis.quadratic) + zonal * basis.zonal);
  return lerp(analyticDiffuse, prediction, weight);
}
