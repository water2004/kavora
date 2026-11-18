/* SPDX-License-Identifier: MIT */
import "rtx/algorithm/clouds.slang";
import "rtx/algorithm/cloud_lighting_cache.slang";
import "rtx/pass/cloud_parameters.slang";
#include "rtx/pass/common_bindings.slangh"
#include "rtx/pass/clouds/cloud_lighting_binding_indices.h"
#include "rtx/pass/clouds/cloud_lighting_cache.h"

layout(binding = CLOUD_LIGHTING_BINDING_OUTPUT)
RWTexture3D<float4> CloudLocalLightOutput;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint2 index : SV_DispatchThreadID)
{
  if (any(index >= CLOUD_LOCAL_LIGHT_RESOLUTION))
  {
    return;
  }
  const CloudArgs args = cb.volumeArgs.clouds;
  const float2 uv = (float2(index) + 0.5f) / float(CLOUD_LOCAL_LIGHT_RESOLUTION);
  const float3 position = cloudLocalLightPosition(args, float3(uv.x, 0.5f, uv.y));
  const CloudLocalColumn column = prepareCloudLocalColumn(args, position, CloudWeatherSampler, CloudBaseSampler, CloudDetailSampler);
  for (uint y = 0; y < CLOUD_LOCAL_LIGHT_HEIGHT_SAMPLES; y++)
  {
    const float h = (float(y) + 0.5f) / float(CLOUD_LOCAL_LIGHT_HEIGHT_SAMPLES);
    const CloudLocalDepth depth = evaluateCloudLocalColumn(column, args.baseHeight + h * args.thickness, args.shapeScale);
    CloudLocalLightOutput[uint3(index.x, y, index.y)] = float4(depth.vertical, depth.lightProjection);
  }
}
