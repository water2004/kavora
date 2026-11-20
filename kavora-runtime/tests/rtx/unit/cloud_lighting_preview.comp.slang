// SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors
// SPDX-License-Identifier: MIT
import "rtx/pass/cloud_parameters.slang";
import "rtx/algorithm/clouds.slang";
import "rtx/algorithm/cloud_lighting_cache.slang";
import "rtx/pass/clouds/cloud_lighting_bake.slang";

ConstantBuffer<CloudArgs> Clouds;
Sampler2DArray<float4>.Handle Weather;
Sampler3D<float4>.Handle Base;
Sampler3D<float4>.Handle Detail;
RWTexture3D<float2> CloudLocalLightOutput;

[shader("compute")]
[numthreads(8, 8, 1)]
void buildLocalLight(uint3 index : SV_DispatchThreadID)
{
  buildCloudLocalLightColumn(Clouds, index.xy, index.z, Sampler2DArray<float4>(Weather),
    Sampler3D<float4>(Base), Sampler3D<float4>(Detail), CloudLocalLightOutput);
}
