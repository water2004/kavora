// SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors
// SPDX-License-Identifier: MIT
import "rtx/algorithm/clouds.slang";
import "rtx/algorithm/cloud_neural_scattering.slang";
import "rtx/pass/cloud_parameters.slang";
import "rtx/utility/cloud_density.slang";
#include "rtx/pass/clouds/cloud_neural_scattering.h"
#include "rtx/pass/common_bindings.slangh"
#include "rtx/algorithm/scene_cloud_lighting.slangh"

layout(binding = CLOUD_NEURAL_OUTPUT_BINDING)
RWTexture3D<float4> NeuralCloudOutput;
layout(binding = CLOUD_NEURAL_WEIGHTS_BINDING)
StructuredBuffer<float> NeuralCloudWeights;

static const uint kLaneCount = 8;
static const uint kWidth = CLOUD_NEURAL_WIDTH;
static const uint kInputs = CLOUD_NEURAL_INPUTS;
static const uint kDirections = CLOUD_NEURAL_DIRECTIONS;
static const uint kHiddenStride = (kWidth + 1) * kWidth;
static const uint kHead = (kInputs + 1) * kWidth + (CLOUD_NEURAL_HIDDEN_LAYERS - 1) * kHiddenStride;
static const uint kDirectionTable = kHead + kWidth + 1;
static const uint kProjection = kDirectionTable + 3 * kDirections;

groupshared float SpatialFeatures[kInputs];
groupshared float Activations[2][kDirections][kWidth];
groupshared float Prediction[kDirections];
groupshared float Coefficients[9];

float densityFeature(CloudDensityArgs density, float3 position, float footprint = 0, float heightFootprint = 0)
{
  return cloudDensity(density, position, CloudWeatherSampler, CloudBaseSampler, CloudDetailSampler,
    footprint, 0, heightFootprint);
}

void writeSpatialFeatures(CloudArgs args, float3 position, uint heightIndex, uint lane)
{
  const CloudDensityArgs density = cloudDensityParameters(args);
  const uint lightIndex = args.neuralScattering - 1;
  const float3 sun = args.lights[lightIndex].directionToLight;
  if (lane < 4)
  {
    // The trained pyramid averages a 128 x 64 x 128 grid over an 8 x 2 x 8 km box.
    const float footprint = lane == 0 ? 0.0f : float(1 << lane) * (62.5f * args.meterToWorld);
    SpatialFeatures[lane] = densityFeature(density, position, footprint, footprint * 0.5f);
  }
  else if (lane == 4)
  {
    const float tau = cloudLightOpticalDepth(args, position, sun, 3.402823466e+38f,
      CloudWeatherSampler, CloudBaseSampler, CloudDetailSampler, sceneCloudLightingCache(), 0, 24.0f, int(lightIndex)).total;
    SpatialFeatures[4] = exp(-tau);
    SpatialFeatures[5] = tau / (1.0f + tau);
    SpatialFeatures[6] = float(heightIndex) / (CLOUD_NEURAL_HEIGHT - 1);
  }
  else
  {
    SpatialFeatures[lane + 5] = densityFeature(density, position + sun * args.meterToWorld
      * (lane == 5 ? 150.0f : lane == 6 ? 500.0f : 1200.0f));
  }
}

float silu(float x)
{
  return x / (1.0f + exp(-x));
}

[shader("compute")]
[numthreads(8, CLOUD_NEURAL_DIRECTIONS, 1)]
void main(uint3 cell : SV_GroupID, uint3 thread : SV_GroupThreadID, uint lane : SV_GroupIndex)
{
  const CloudArgs args = cb.volumeArgs.clouds;
  const float3 sun = args.lights[args.neuralScattering - 1].directionToLight;
  if (thread.y == 0)
  {
    writeSpatialFeatures(args, cloudNeuralPosition(args, cell), cell.y, thread.x);
  }
  else if (lane == kLaneCount)
  {
    const CloudDensityArgs density = cloudDensityParameters(args);
    const float3 position = cloudNeuralPosition(args, cell);
    SpatialFeatures[13] = densityFeature(density, position - sun * (300.0f * args.meterToWorld));
    SpatialFeatures[14] = densityFeature(density, position + args.up * (500.0f * args.meterToWorld));
  }
  GroupMemoryBarrierWithGroupSync();

  const uint angle = thread.y;
  const uint directionOffset = kDirectionTable + angle * 3;
  const float3 direction = float3(NeuralCloudWeights[directionOffset], NeuralCloudWeights[directionOffset + 1],
    NeuralCloudWeights[directionOffset + 2]);
  const float cosine = clamp(dot(direction, cloudNeuralFrame(args, sun)), -1.0f, 1.0f);
  // The feature is the checkpoint's HG evaluation. Host direct lighting retains its own phase.
  const float g = 0.8f;
  const float invRoot = rsqrt(1.0f + g * g - 2.0f * g * cosine);
  const float phase = ((1.0f - g * g) / (4.0f * 3.14159265359f)) * invRoot * invRoot * invRoot;

  [unroll]
  for (uint neuron = thread.x; neuron < kWidth; neuron += kLaneCount)
  {
    float sum = NeuralCloudWeights[neuron];
    [unroll]
    for (uint input = 0; input < kInputs; input++)
    {
      const float feature = input == 7 ? cosine : input == 8 ? phase : input == 9 ? direction.y : SpatialFeatures[input];
      sum = mad(feature, NeuralCloudWeights[(input + 1) * kWidth + neuron], sum);
    }
    Activations[0][angle][neuron] = silu(sum);
  }
  GroupMemoryBarrierWithGroupSync();

  for (uint layer = 1; layer < CLOUD_NEURAL_HIDDEN_LAYERS; layer++)
  {
    const uint offset = (kInputs + 1) * kWidth + (layer - 1) * kHiddenStride;
    const uint source = (layer - 1) & 1;
    for (uint neuron = thread.x; neuron < kWidth; neuron += kLaneCount)
    {
      float sum = NeuralCloudWeights[offset + neuron];
      for (uint input = 0; input < kWidth; input++)
      {
        sum = mad(Activations[source][angle][input], NeuralCloudWeights[offset + (input + 1) * kWidth + neuron], sum);
      }
      Activations[layer & 1][angle][neuron] = silu(sum);
    }
    GroupMemoryBarrierWithGroupSync();
  }

  if (thread.x == 0)
  {
    float sum = NeuralCloudWeights[kHead];
    for (uint input = 0; input < kWidth; input++)
    {
      sum = mad(Activations[(CLOUD_NEURAL_HIDDEN_LAYERS - 1) & 1][angle][input], NeuralCloudWeights[kHead + 1 + input], sum);
    }
    Prediction[angle] = 0.25f * (max(sum, 0.0f) + log(1.0f + exp(-abs(sum))));
  }
  GroupMemoryBarrierWithGroupSync();

  if (lane < 9)
  {
    float coefficient = 0;
    for (uint sample = 0; sample < kDirections; sample++)
    {
      coefficient = mad(Prediction[sample], NeuralCloudWeights[kProjection + lane * kDirections + sample], coefficient);
    }
    Coefficients[lane] = coefficient;
  }
  GroupMemoryBarrierWithGroupSync();

  if (lane < CLOUD_NEURAL_BANDS)
  {
    const uint first = lane * 4;
    float4 coefficients = float4(Coefficients[first], 0, 0, 0);
    if (lane < 2)
    {
      coefficients.yzw = float3(Coefficients[first + 1], Coefficients[first + 2], Coefficients[first + 3]);
    }
    NeuralCloudOutput[cell + uint3(0, lane * CLOUD_NEURAL_HEIGHT, 0)] = coefficients;
  }
}
