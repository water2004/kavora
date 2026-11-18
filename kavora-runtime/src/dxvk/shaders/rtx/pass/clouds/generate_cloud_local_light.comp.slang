/* SPDX-License-Identifier: MIT */
import "rtx/algorithm/clouds.slang";
#include "rtx/pass/common_bindings.slangh"
#include "rtx/algorithm/scene_cloud_lighting.slangh"
#include "rtx/pass/clouds/cloud_lighting_binding_indices.h"
#include "rtx/pass/clouds/cloud_lighting_cache.h"

layout(binding = CLOUD_LIGHTING_BINDING_OUTPUT)
RWTexture3D<float4> CloudLocalLightOutput;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 index : SV_DispatchThreadID)
{
  if (any(index.xy >= CLOUD_LOCAL_LIGHT_RESOLUTION))
  {
    return;
  }
  buildCloudLocalLightColumn(cb.volumeArgs.clouds, index.xy, index.z,
    CloudWeatherSampler, CloudBaseSampler, CloudDetailSampler, sceneCloudLightingCache(), CloudLocalLightOutput);
}
