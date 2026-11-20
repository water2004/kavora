/* SPDX-License-Identifier: MIT */
#define RAY_PIPELINE 1
import "rtx/utility/cloud_density.slang";
import "rtx/algorithm/fog_froxel_sampling.slang";

#include "rtx/pass/common_binding_indices.h"
#include "rtx/pass/common_bindings.slangh"
#include "rtx/algorithm/scene_air.slangh"
#include "rtx/pass/gbuffer/gbuffer_binding_indices.h"
#include "rtx/utility/shader_types.slangh"
#include "rtx/utility/brdf.slangh"

layout(binding = GBUFFER_BINDING_LINEAR_WRAP_SAMPLER)
SamplerState LinearWrapSampler;
layout(binding = GBUFFER_BINDING_VOLUME_FILTERED_RADIANCE_Y_INPUT)
Sampler3D<float4> VolumeFilteredRadianceY;
layout(binding = GBUFFER_BINDING_VOLUME_FILTERED_RADIANCE_CO_CG_INPUT)
Sampler3D<float2> VolumeFilteredRadianceCoCg;
layout(binding = GBUFFER_BINDING_SKYPROBE)
SamplerCube<float3> SkyProbe;

#include "rtx/pass/gbuffer/atmosphere_call.slangh"
import "rtx/algorithm/atmosphere.slang";
#include "rtx/algorithm/scene_atmosphere.slangh"

[shader("callable")]
void main(inout AtmosphereCallData data)
{
  const AtmosphereSegment segment = resolveSceneAtmosphere(data.origin, data.direction, data.distance,
    data.volumeHint, data.mediumExtinction, data.allowFarTail, data.cloudAngularFootprint);
  data.radiance = segment.radiance;
  data.transmittance = segment.transmittance;
  data.cloudMoments = segment.cloudMoments;
}
