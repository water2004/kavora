// SPDX-FileCopyrightText: Copyright (c) 2025 NVIDIA CORPORATION. All rights reserved.
// SPDX-License-Identifier: MIT
module value_noise_sampling;

#include "rtx/utility/procedural_noise.h"

[ForceInline]
public float4 sampleLinearValueNoise(Sampler3D<float4> noise, float3 texelPosition)
{
  const float3 inverseResolution = float3(1.0f / VALUE_NOISE_RESOLUTION);
  return noise.SampleLevel(texelPosition * inverseResolution, 0) * float4(2.0f) - float4(1.0f);
}
