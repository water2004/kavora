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
RWTexture3D<float2> LocalLightOutput;

[shader("compute")]
[numthreads(8, 8, 1)]
void buildLocalLight(uint3 index : SV_DispatchThreadID)
{
  buildCloudLocalLightColumn(Clouds, index.xy, index.z, Sampler2D<float4>(Weather),
    Sampler3D<float4>(Base), Sampler3D<float4>(Detail), LocalLightOutput);
}

#if defined(CLOUD_PREVIEW_HAS_DIRECT_LIGHT)
Sampler3D<float2>.Handle CloudLocalLight;
RWTexture3D<float4> DirectLightOutput;

[shader("compute")]
[numthreads(8, 4, 2)]
void buildDirectLight(uint3 index : SV_DispatchThreadID)
{
  const Sampler3D<float2> local = Sampler3D<float2>(CloudLocalLight);
  const Sampler3D<float4> unused = Sampler3D<float4>(Base);
  const CloudLightingCache cache = { Sampler3D<float>(CloudSunDepth), Sampler3D<float>(CloudMoonDepth), local, unused, unused };
  DirectLightOutput[index] = buildCloudDirectLightCell(Clouds, index, Sampler2D<float4>(Weather),
    Sampler3D<float4>(Base), Sampler3D<float4>(Detail), cache);
}
#endif
#endif
