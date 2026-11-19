// SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors
// SPDX-License-Identifier: MIT
#include <array>
#include <cstring>
#include <vector>

#include <glm/gtc/packing.hpp>

#include <cloud_neural_sun.bin.h>

#include "dxvk_context.h"
#include "dxvk_device.h"
#include "rtx/pass/clouds/cloud_neural_model.h"
#include "rtx_cloud_neural_model.h"

namespace dxvk {

  namespace {
    constexpr size_t kWeightCount = CLOUD_NEURAL_DIRECTIONS_OFFSET + CLOUD_NEURAL_TABLE_FLOATS;
    static_assert(sizeof(___cloud_neural_sun) == kWeightCount * sizeof(float));
    constexpr uint32_t kLayerCount = CLOUD_NEURAL_HIDDEN_LAYERS + 1;
    static_assert(kLayerCount * 2 * sizeof(uint32_t) <= CLOUD_NEURAL_TENSOR_TABLE_OFFSET);

    Rc<DxvkBuffer> uploadWeights(DxvkContext& ctx, const void* data, size_t size) {
      DxvkBufferCreateInfo info = {};
      info.size = size;
      info.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
      info.stages = VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
      info.access = VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT;
      auto buffer = ctx.getDevice()->createBuffer(info, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
        DxvkMemoryStats::Category::RTXBuffer, "Cloud neural weights");
      ctx.writeToBuffer(buffer, 0, info.size, data);
      return buffer;
    }

    std::vector<uint8_t> convertTensorWeights(const vk::DeviceFn& vkd) {
      const auto convert = reinterpret_cast<PFN_vkConvertCooperativeVectorMatrixNV>(vkd.sym("vkConvertCooperativeVectorMatrixNV"));
      if (!convert) {
        return {};
      }
      std::array<float, kWeightCount> source;
      std::memcpy(source.data(), ___cloud_neural_sun, sizeof(source));
      std::vector<uint8_t> packed(CLOUD_NEURAL_TENSOR_TABLE_OFFSET + CLOUD_NEURAL_TABLE_FLOATS * sizeof(float));
      std::memcpy(packed.data() + CLOUD_NEURAL_TENSOR_TABLE_OFFSET, source.data() + CLOUD_NEURAL_DIRECTIONS_OFFSET,
        CLOUD_NEURAL_TABLE_FLOATS * sizeof(float));

      constexpr uint32_t width = CLOUD_NEURAL_WIDTH;
      std::array<uint16_t, width * width> matrix;
      std::array<uint16_t, width> bias;
      size_t layerStart = 0;
      for (uint32_t layer = 0; layer < kLayerCount; layer++) {
        const bool first = layer == 0;
        const bool last = layer == CLOUD_NEURAL_HIDDEN_LAYERS;
        const uint32_t inputCount = first ? CLOUD_NEURAL_INPUTS : width;
        const uint32_t outputCount = last ? 1 : width;
        const uint32_t columns = first ? CLOUD_NEURAL_TENSOR_INPUTS : width;
        const uint32_t rows = last ? CLOUD_NEURAL_TENSOR_OUTPUTS : width;

        bias.fill(0);
        matrix.fill(0);
        for (uint32_t output = 0; output < outputCount; output++) {
          bias[output] = glm::packHalf1x16(source[layerStart + output]);
          for (uint32_t input = 0; input < inputCount; input++) {
            matrix[output * columns + input] = glm::packHalf1x16(source[layerStart + (input + 1) * outputCount + output]);
          }
        }
        layerStart += (inputCount + 1) * outputCount;

        size_t matrixSize = 0;
        VkConvertCooperativeVectorMatrixInfoNV info { VK_STRUCTURE_TYPE_CONVERT_COOPERATIVE_VECTOR_MATRIX_INFO_NV };
        info.pDstSize = &matrixSize;
        info.srcComponentType = VK_COMPONENT_TYPE_FLOAT16_KHR;
        info.dstComponentType = VK_COMPONENT_TYPE_FLOAT16_KHR;
        info.numRows = rows;
        info.numColumns = columns;
        info.srcLayout = VK_COOPERATIVE_VECTOR_MATRIX_LAYOUT_ROW_MAJOR_NV;
        info.srcStride = columns * sizeof(uint16_t);
        info.dstLayout = VK_COOPERATIVE_VECTOR_MATRIX_LAYOUT_INFERENCING_OPTIMAL_NV;
        if (convert(vkd.device(), &info) != VK_SUCCESS || !matrixSize) {
          return {};
        }
        constexpr size_t alignment = CLOUD_NEURAL_TENSOR_ALIGNMENT;
        const size_t matrixOffset = align(packed.size(), alignment);
        const size_t biasOffset = align(matrixOffset + matrixSize, alignment);
        packed.resize(align(biasOffset + rows * sizeof(uint16_t), alignment));
        info.srcSize = rows * info.srcStride;
        info.srcData.hostAddress = matrix.data();
        info.dstData.hostAddress = packed.data() + matrixOffset;
        if (convert(vkd.device(), &info) != VK_SUCCESS) {
          return {};
        }
        std::memcpy(packed.data() + biasOffset, bias.data(), rows * sizeof(uint16_t));
        const std::array<uint32_t, 2> offsets { uint32_t(matrixOffset), uint32_t(biasOffset) };
        std::memcpy(packed.data() + layer * sizeof(offsets), offsets.data(), sizeof(offsets));
      }
      return packed;
    }
  }

  bool CloudNeuralModel::prepareTensorWeights(DxvkContext& ctx) {
    if (m_tensorWeights.ptr()) {
      return true;
    }
    if (m_tensorConversionFailed || ctx.getDevice()->features().cooperativeVectorFp16Components < CLOUD_NEURAL_WIDTH) {
      return false;
    }
    const auto packed = convertTensorWeights(*ctx.getDevice()->vkd().ptr());
    if (packed.empty()) {
      m_tensorConversionFailed = true;
      Logger::warn("Cloud neural scattering: Vulkan cooperative-vector weight conversion unavailable");
      return false;
    }
    m_tensorWeights = uploadWeights(ctx, packed.data(), packed.size());
    Logger::info(str::format("Cloud neural scattering: FP16 cooperative vectors, ", packed.size(), " bytes of weights and projection data"));
    return true;
  }

  const Rc<DxvkBuffer>& CloudNeuralModel::weights(DxvkContext& ctx, bool tensor) {
    if (tensor) {
      return m_tensorWeights;
    }
    if (!m_scalarWeights.ptr()) {
      m_scalarWeights = uploadWeights(ctx, ___cloud_neural_sun, sizeof(___cloud_neural_sun));
    }
    return m_scalarWeights;
  }

}
