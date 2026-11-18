// SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors
// SPDX-License-Identifier: MIT
import "rtx/pass/cloud_parameters.slang";
import "rtx/algorithm/clouds.slang";
import "rtx/algorithm/cloud_lighting_cache.slang";

ConstantBuffer<CloudArgs> Clouds;
Sampler2D<float4>.Handle Weather;
Sampler3D<float4>.Handle Base;
Sampler3D<float4>.Handle Detail;
RWTexture3D<float> LightOutput;
uint LightIndex;

[shader("compute")]
[numthreads(8, 8, 1)]
void buildLight(uint2 column : SV_DispatchThreadID)
{
  buildCloudLightPrefix(Clouds, cloudDensityParameters(Clouds), column, LightIndex,
    Sampler2D<float4>(Weather), Sampler3D<float4>(Base), Sampler3D<float4>(Detail), LightOutput);
}

#if defined(CLOUD_PREVIEW_HAS_LOCAL_LIGHT)
Sampler3D<float>.Handle CloudSunDepth;
Sampler3D<float>.Handle CloudMoonDepth;
RWTexture3D<float4> LocalLightOutput;

[shader("compute")]
[numthreads(8, 8, 1)]
void buildLocalLight(uint3 index : SV_DispatchThreadID)
{
  const CloudLightingCache cache = { Sampler3D<float>(CloudSunDepth), Sampler3D<float>(CloudMoonDepth), Sampler3D<float4>(Base) };
  buildCloudLocalLightColumn(Clouds, index.xy, index.z, Sampler2D<float4>(Weather),
    Sampler3D<float4>(Base), Sampler3D<float4>(Detail), cache, LocalLightOutput);
}
#endif
