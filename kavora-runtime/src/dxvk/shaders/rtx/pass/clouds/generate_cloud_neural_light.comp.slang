// SPDX-License-Identifier: MIT
#include "rtx/pass/clouds/cloud_neural_model.h"
#include "rtx/pass/common_bindings.slangh"
#include "rtx/algorithm/scene_cloud_lighting.slangh"
import "rtx/pass/cloud_parameters.slang";

CloudArgs neuralCloudArgs() { return cb.volumeArgs.clouds; }

layout(binding = CLOUD_NEURAL_OUTPUT_BINDING)
RWTexture3D<float4> NeuralCloudOutput;
layout(binding = CLOUD_NEURAL_WEIGHTS_BINDING)
ByteAddressBuffer NeuralCloudWeights;

#include "rtx/pass/clouds/cloud_neural_bake.slangh"
