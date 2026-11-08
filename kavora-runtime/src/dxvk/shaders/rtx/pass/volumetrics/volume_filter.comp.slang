/* SPDX-License-Identifier: MIT */

#include "rtx/pass/common_bindings.slangh"
#include "rtx/pass/volumetrics/volume_filter_binding_indices.h"

layout(binding = VOLUME_FILTER_INPUT_Y)
Sampler3D<float4> IrradianceY;
layout(binding = VOLUME_FILTER_INPUT_CO_CG)
Sampler3D<float2> IrradianceCoCg;
layout(rgba16f, binding = VOLUME_FILTER_OUTPUT_Y)
RWTexture3D<float4> FilteredIrradianceY;
layout(rg16f, binding = VOLUME_FILTER_OUTPUT_CO_CG)
RWTexture3D<float2> FilteredIrradianceCoCg;

[shader("compute")]
[numthreads(8, 4, 4)]
void main(uint3 index : SV_DispatchThreadID)
{
  uint3 dimensions = uint3(cb.volumeArgs.froxelGridDimensions, cb.volumeArgs.froxelDepthSlices);
  uint volume = index.x / dimensions.x;
  if (volume >= cb.volumeArgs.numActiveFroxelVolumes || any(index.yz >= dimensions.yz))
  {
    return;
  }
  float3 center = float3(index.x % dimensions.x, index.y, index.z) + 0.5f;
  float3 inverseDimensions = 1.0f / float3(dimensions);
  float4 y = 0;
  float2 cocg = 0;
  // Eight trilinear reads implement the separable [1, 2, 1] reconstruction kernel.
  [unroll]
  for (uint tap = 0; tap < 8; tap++)
  {
    float3 offset = float3(tap & 1, (tap >> 1) & 1, (tap >> 2) & 1) - 0.5f;
    float3 uvw = clamp(center + offset, 0.5f, float3(dimensions) - 0.5f) * inverseDimensions;
    uvw.x = (uvw.x + float(volume)) * cb.volumeArgs.inverseNumFroxelVolumes;
    y += IrradianceY.SampleLevel(uvw, 0);
    cocg += IrradianceCoCg.SampleLevel(uvw, 0);
  }
  FilteredIrradianceY[index] = y * 0.125f;
  FilteredIrradianceCoCg[index] = cocg * 0.125f;
}
