// SPDX-License-Identifier: MIT
import "rtx/algorithm/cloud_lighting_cache.slang";
import "rtx/pass/cloud_parameters.slang";

ConstantBuffer<CloudArgs> Clouds;
Sampler2DArray<float4>.Handle Weather;
Sampler3D<float4>.Handle Base;
Sampler3D<float4>.Handle Detail;
Sampler2D<float>.Handle Phase;
Sampler3D<float2>.Handle CloudLocalLight;
RWTexture3D<float4> CloudNeuralLightOutput;
#define NeuralCloudOutput CloudNeuralLightOutput
ByteAddressBuffer NeuralCloudWeights;
#define CloudWeatherSampler Sampler2DArray<float4>(Weather)
#define CloudBaseSampler Sampler3D<float4>(Base)
#define CloudDetailSampler Sampler3D<float4>(Detail)
#define CloudPhaseSampler Sampler2D<float>(Phase)

CloudArgs neuralCloudArgs() { return Clouds; }
CloudLightingCache sceneCloudLightingCache()
{
  return { Sampler3D<float2>(CloudLocalLight), Sampler3D<float4>(Base) };
}
#include "rtx/pass/clouds/cloud_neural_bake.slangh"
