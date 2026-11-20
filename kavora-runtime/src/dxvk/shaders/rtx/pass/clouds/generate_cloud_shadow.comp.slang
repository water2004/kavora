/* SPDX-License-Identifier: MIT */
import "rtx/algorithm/cloud_shadow.slang";
#include "rtx/pass/common_bindings.slangh"
#include "rtx/pass/clouds/cloud_lighting_binding_indices.h"

layout(binding = CLOUD_LIGHTING_BINDING_OUTPUT)
RWTexture2DArray<float> CloudShadowOutput;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 index : SV_DispatchThreadID)
{
  CloudShadowOutput[index] = bakeCloudShadow(cb.volumeArgs.clouds, index.xy, index.z,
    CloudWeatherSampler, CloudBaseSampler, CloudDetailSampler);
}
