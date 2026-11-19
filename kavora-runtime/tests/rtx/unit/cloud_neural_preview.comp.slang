// SPDX-License-Identifier: MIT
import "rtx/algorithm/cloud_lighting_cache.slang";
import "rtx/pass/cloud_parameters.slang";

ConstantBuffer<CloudArgs> Clouds;
Sampler2DArray<float4>.Handle Weather;
Sampler3D<float4>.Handle Base;
Sampler3D<float4>.Handle Detail;
Sampler3D<float>.Handle CloudSunDepth;
Sampler3D<float>.Handle CloudMoonDepth;
Sampler3D<float2>.Handle CloudLocalLight;
Sampler3D<float4>.Handle DirectLightInput;
RWTexture3D<float4> NeuralCloudOutput;
StructuredBuffer<float> NeuralCloudWeights;
#define CloudWeatherSampler Sampler2DArray<float4>(Weather)
#define CloudBaseSampler Sampler3D<float4>(Base)
#define CloudDetailSampler Sampler3D<float4>(Detail)

CloudArgs neuralCloudArgs() { return Clouds; }
CloudLightingCache sceneCloudLightingCache()
{
  const Sampler3D<float4> direct = Sampler3D<float4>(DirectLightInput);
  return { Sampler3D<float>(CloudSunDepth), Sampler3D<float>(CloudMoonDepth),
    Sampler3D<float2>(CloudLocalLight), direct, direct };
}
#include "rtx/pass/clouds/cloud_neural_bake.slangh"
