// SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors
// SPDX-License-Identifier: MIT
import "rtx/pass/clouds/cloud_lighting_bake.slang";

#include "rtx/pass/common_bindings.slangh"
#include "rtx/algorithm/scene_cloud_lighting.slangh"
#include "rtx/pass/clouds/cloud_lighting_binding_indices.h"

layout(binding = CLOUD_LIGHTING_BINDING_OUTPUT)
RWTexture3D<float4> CloudDirectLightOutput;

[shader("compute")]
[numthreads(8, 4, 2)]
void main(uint3 index : SV_DispatchThreadID)
{
  CloudDirectLightOutput[index] = buildCloudDirectLightCell(cb.volumeArgs.clouds, index,
    CloudWeatherSampler, CloudBaseSampler, CloudDetailSampler, sceneCloudLightingCache());
}
