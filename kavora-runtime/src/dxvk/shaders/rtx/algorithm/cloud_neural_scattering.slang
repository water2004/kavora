// SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors
// SPDX-License-Identifier: MIT
module cloud_neural_scattering;

import "rtx/pass/cloud_parameters.slang";
import "rtx/algorithm/cloud_lighting_cache.slang";
#include "rtx/pass/clouds/cloud_neural_scattering.h"

float cloudNeuralCellSize(CloudArgs args, uint layer)
{
  return 2.0f * cloudLocalLightRadius(args, layer) / CLOUD_NEURAL_RESOLUTION;
}

public float3 cloudNeuralFrame(CloudArgs args, float3 vector)
{
  return args.up.z > 0.5f ? vector.xzy : vector;
}

float2 cloudNeuralGridCenter(CloudArgs args, float cellSize)
{
  const float2 wind = cloudNeuralFrame(args, args.windOffset - args.groundReference).xz;
  return wind + floor(-wind / cellSize) * cellSize;
}

public uint cloudNeuralRowOffset(uint layer)
{
  return layer == 0 ? 0 : CLOUD_NEURAL_HEIGHT;
}

public uint cloudNeuralHeightSamples(uint layer)
{
  return layer == 0 ? CLOUD_NEURAL_HEIGHT : CLOUD_NEURAL_HIGH_HEIGHT;
}

public float3 cloudNeuralPosition(CloudArgs args, uint2 index, uint layer, float altitude)
{
  const float cellSize = cloudNeuralCellSize(args, layer);
  const float2 horizontal = cloudNeuralGridCenter(args, cellSize)
    + (float2(index) + 0.5f - 0.5f * CLOUD_NEURAL_RESOLUTION) * cellSize;
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
  float3 direction, uint layer, uint light)
{
  const float cellSize = cloudNeuralCellSize(args, layer);
  const float2 horizontal = cloudNeuralFrame(args, position - args.groundReference).xz
    - cloudNeuralGridCenter(args, cellSize);
  const float2 uv = horizontal / (cellSize * CLOUD_NEURAL_RESOLUTION) + 0.5f;
  const float bottom = layer == 0 ? args.baseHeight : args.highBaseHeight;
  const float thickness = layer == 0 ? args.thickness : args.highThickness;
  const float height = saturate((altitude - bottom) / thickness);
  const float row = cloudNeuralRowOffset(layer) + 0.5f + height * float(cloudNeuralHeightSamples(layer) - 1);
  const float band = 1.0f / (2.0f * CLOUD_NEURAL_BANDS);
  const float v = band * (float(light * CLOUD_NEURAL_BANDS) + row / CLOUD_NEURAL_COLUMN_HEIGHT);
  const float4 linear = field.SampleLevel(float3(uv.x, v, uv.y), 0);
  const float4 quadratic = field.SampleLevel(float3(uv.x, v + band, uv.y), 0);
  const float zonal = field.SampleLevel(float3(uv.x, v + 2.0f * band, uv.y), 0).x;
  const CloudNeuralBasis basis = cloudNeuralBasis(direction);
  return max(0.0f, dot(linear, basis.linear) + dot(quadratic, basis.quadratic) + zonal * basis.zonal);
}
