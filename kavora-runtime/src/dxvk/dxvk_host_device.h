/*
 * Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: MIT
 */
#pragma once

#include "dxvk_device_info.h"
#include "dxvk_extensions.h"
#include "../util/util_error.h"
#include "../util/util_string.h"

#include <cstddef>
#include <cstring>
#include <vector>

namespace dxvk {

  struct DxvkHostInstanceInfo {
    VkInstance instance;
    VkPhysicalDevice physicalDevice;
    const DxvkNameSet* extensions;
  };

  /** Requests made by a host that owns the resulting Vulkan device. */
  struct DxvkHostDeviceCreateInfo {
    VkDeviceCreateInfo application;
    uint32_t graphicsFamily;
    DxvkNameSet enabledExtensions;
    DxvkNameList extensionNames;
    VkPhysicalDeviceDynamicRenderingFeatures dynamicRendering = {
      VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DYNAMIC_RENDERING_FEATURES };
    VkPhysicalDeviceMultiDrawFeaturesEXT multiDraw = {
      VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MULTI_DRAW_FEATURES_EXT };
    bool hasDynamicRendering = false;
    bool hasMultiDraw = false;
    bool ownershipTransferred = false;

    template<typename T>
    static void mergeFlags(T& destination, const T& source, size_t first, size_t last) {
      // Feature structures contain contiguous VkBool32 members in this range.
      for (size_t offset = first; offset <= last; offset += sizeof(VkBool32)) {
        VkBool32 a, b;
        std::memcpy(&a, reinterpret_cast<const char*>(&destination) + offset, sizeof(a));
        std::memcpy(&b, reinterpret_cast<const char*>(&source) + offset, sizeof(b));
        a = a || b;
        std::memcpy(reinterpret_cast<char*>(&destination) + offset, &a, sizeof(a));
      }
    }

    void mergeFeatures(DxvkDeviceFeatures& features) {
      if (application.pEnabledFeatures) {
        mergeFlags(features.core.features, *application.pEnabledFeatures,
          offsetof(VkPhysicalDeviceFeatures, robustBufferAccess),
          offsetof(VkPhysicalDeviceFeatures, inheritedQueries));
      }
      for (auto* item = static_cast<const VkBaseInStructure*>(application.pNext); item; item = item->pNext) {
        switch (item->sType) {
          case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2: {
            const auto& source = reinterpret_cast<const VkPhysicalDeviceFeatures2*>(item)->features;
            mergeFlags(features.core.features, source,
              offsetof(VkPhysicalDeviceFeatures, robustBufferAccess),
              offsetof(VkPhysicalDeviceFeatures, inheritedQueries));
          } break;
          case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES:
            mergeFlags(features.vulkan11Features, *reinterpret_cast<const VkPhysicalDeviceVulkan11Features*>(item),
              offsetof(VkPhysicalDeviceVulkan11Features, storageBuffer16BitAccess),
              offsetof(VkPhysicalDeviceVulkan11Features, shaderDrawParameters));
            break;
          case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES:
            mergeFlags(features.vulkan12Features, *reinterpret_cast<const VkPhysicalDeviceVulkan12Features*>(item),
              offsetof(VkPhysicalDeviceVulkan12Features, samplerMirrorClampToEdge),
              offsetof(VkPhysicalDeviceVulkan12Features, subgroupBroadcastDynamicId));
            break;
          case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SYNCHRONIZATION_2_FEATURES:
            features.khrSynchronization2.synchronization2 = features.khrSynchronization2.synchronization2 || reinterpret_cast<const VkPhysicalDeviceSynchronization2Features*>(item)->synchronization2;
            break;
          case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VERTEX_ATTRIBUTE_DIVISOR_FEATURES_EXT: {
            const auto& source = *reinterpret_cast<const VkPhysicalDeviceVertexAttributeDivisorFeaturesEXT*>(item);
            features.extVertexAttributeDivisor.vertexAttributeInstanceRateDivisor = features.extVertexAttributeDivisor.vertexAttributeInstanceRateDivisor || source.vertexAttributeInstanceRateDivisor;
            features.extVertexAttributeDivisor.vertexAttributeInstanceRateZeroDivisor = features.extVertexAttributeDivisor.vertexAttributeInstanceRateZeroDivisor || source.vertexAttributeInstanceRateZeroDivisor;
          } break;
          case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DYNAMIC_RENDERING_FEATURES:
            hasDynamicRendering = true;
            dynamicRendering.dynamicRendering = dynamicRendering.dynamicRendering || reinterpret_cast<const VkPhysicalDeviceDynamicRenderingFeatures*>(item)->dynamicRendering;
            break;
          case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MULTI_DRAW_FEATURES_EXT:
            hasMultiDraw = true;
            multiDraw.multiDraw = multiDraw.multiDraw || reinterpret_cast<const VkPhysicalDeviceMultiDrawFeaturesEXT*>(item)->multiDraw;
            break;
          case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR:
            mergeFlags(features.khrAccelerationStructureFeatures, *reinterpret_cast<const VkPhysicalDeviceAccelerationStructureFeaturesKHR*>(item),
              offsetof(VkPhysicalDeviceAccelerationStructureFeaturesKHR, accelerationStructure),
              offsetof(VkPhysicalDeviceAccelerationStructureFeaturesKHR, descriptorBindingAccelerationStructureUpdateAfterBind));
            break;
          case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_FEATURES_KHR:
            mergeFlags(features.khrDeviceRayTracingPipelineFeatures, *reinterpret_cast<const VkPhysicalDeviceRayTracingPipelineFeaturesKHR*>(item),
              offsetof(VkPhysicalDeviceRayTracingPipelineFeaturesKHR, rayTracingPipeline),
              offsetof(VkPhysicalDeviceRayTracingPipelineFeaturesKHR, rayTraversalPrimitiveCulling));
            break;
          case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_QUERY_FEATURES_KHR:
            features.khrRayQueryFeatures.rayQuery = features.khrRayQueryFeatures.rayQuery || reinterpret_cast<const VkPhysicalDeviceRayQueryFeaturesKHR*>(item)->rayQuery;
            break;
          case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PIPELINE_EXECUTABLE_PROPERTIES_FEATURES_KHR:
            features.khrPipelineExecutableProperties.pipelineExecutableInfo = features.khrPipelineExecutableProperties.pipelineExecutableInfo ||
              reinterpret_cast<const VkPhysicalDevicePipelineExecutablePropertiesFeaturesKHR*>(item)->pipelineExecutableInfo;
            break;
          case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_METERING_FEATURES_NV:
            features.nvPresentMetering.presentMetering = features.nvPresentMetering.presentMetering ||
              reinterpret_cast<const VkPhysicalDevicePresentMeteringFeaturesNV*>(item)->presentMetering;
            break;
          default:
            throw DxvkError(str::format("Unsupported host Vulkan feature structure: ", uint32_t(item->sType)));
        }
      }
    }

    void appendFeatures(VkDeviceCreateInfo& info) {
      if (hasDynamicRendering) {
        dynamicRendering.pNext = const_cast<void*>(info.pNext);
        info.pNext = &dynamicRendering;
      }
      if (hasMultiDraw) {
        multiDraw.pNext = const_cast<void*>(info.pNext);
        info.pNext = &multiDraw;
      }
    }
  };
}
