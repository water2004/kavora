/*
* Copyright (c) 2022-2023, NVIDIA CORPORATION. All rights reserved.
*
* Permission is hereby granted, free of charge, to any person obtaining a
* copy of this software and associated documentation files (the "Software"),
* to deal in the Software without restriction, including without limitation
* the rights to use, copy, modify, merge, publish, distribute, sublicense,
* and/or sell copies of the Software, and to permit persons to whom the
* Software is furnished to do so, subject to the following conditions:
*
* The above copyright notice and this permission notice shall be included in
* all copies or substantial portions of the Software.
*
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
* IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
* FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.  IN NO EVENT SHALL
* THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
* LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
* FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
* DEALINGS IN THE SOFTWARE.
*/
// NV-DXVK start: Optional driver executable representations.
#include <atomic>
#include <chrono>
#include <filesystem>
#include <fstream>
// NV-DXVK end

#include "dxvk_device.h"
#include "dxvk_objects.h"
#include "dxvk_pipemanager.h"
#include "dxvk_state_cache.h"

namespace dxvk {

  // NV-DXVK start: Optional driver executable representations.
  namespace {
    const std::string& pipelineRepresentationDumpPath() {
      static const std::string path = env::getEnvVar("RTX_PIPELINE_REPRESENTATION_DUMP_PATH");
      return path;
    }

    void dumpPipelineRepresentations(const Rc<vk::DeviceFn>& vkd,
      const VkPipelineExecutableInfoKHR& executableInfo, const std::string& shaderKey) {
      const auto& dumpDirectory = pipelineRepresentationDumpPath();
      if (dumpDirectory.empty()) {
        return;
      }

      const auto getRepresentations = reinterpret_cast<PFN_vkGetPipelineExecutableInternalRepresentationsKHR>(
        vkd->sym("vkGetPipelineExecutableInternalRepresentationsKHR"));
      if (getRepresentations == nullptr) {
        Logger::warn("Pipeline representations were requested, but the device exposes no query function.");
        return;
      }
      static const std::string session = str::format(GetCurrentProcessId(), "-",
        std::chrono::steady_clock::now().time_since_epoch().count());
      static std::atomic<uint64_t> sequence { 0 };
      uint32_t count = 0;
      VkResult status = getRepresentations(vkd->device(), &executableInfo, &count, nullptr);
      const std::string prefix = str::format(session, "-", sequence.fetch_add(1, std::memory_order_relaxed),
        "-", shaderKey, "-", executableInfo.pipeline, "-", executableInfo.executableIndex);
      Logger::info(str::format("PipelineRepresentations executable=", prefix, " count=", count, " status=", status));
      if (status != VK_SUCCESS || !count) {
        return;
      }

      std::vector<VkPipelineExecutableInternalRepresentationKHR> representations(count,
        VkPipelineExecutableInternalRepresentationKHR { VK_STRUCTURE_TYPE_PIPELINE_EXECUTABLE_INTERNAL_REPRESENTATION_KHR });
      status = getRepresentations(vkd->device(), &executableInfo, &count, representations.data());
      if (status != VK_SUCCESS) {
        Logger::warn(str::format("PipelineRepresentations executable=", prefix, " metadataStatus=", status));
        return;
      }
      std::vector<std::vector<char>> data(count);
      for (uint32_t index = 0; index < count; ++index) {
        data[index].resize(representations[index].dataSize);
        representations[index].pData = data[index].data();
      }
      status = getRepresentations(vkd->device(), &executableInfo, &count, representations.data());
      if (status != VK_SUCCESS) {
        Logger::warn(str::format("PipelineRepresentations executable=", prefix, " dataStatus=", status));
        return;
      }

      const std::filesystem::path directory(str::tows(dumpDirectory.c_str()));
      std::error_code error;
      std::filesystem::create_directories(directory, error);
      if (error) {
        Logger::warn(str::format("PipelineRepresentations directory=", dumpDirectory, " error=", error.message()));
        return;
      }
      for (uint32_t index = 0; index < count; ++index) {
        const auto& representation = representations[index];
        const std::string filename = str::format(prefix, "-", index, representation.isText ? ".txt" : ".bin");
        std::ofstream output(directory / filename, std::ios::binary | std::ios::trunc);
        output.write(data[index].data(), static_cast<std::streamsize>(representation.dataSize));
        output.close();
        if (!output) {
          Logger::warn(str::format("PipelineRepresentations failed to write ", filename));
          continue;
        }
        Logger::info(str::format("PipelineRepresentation executable=", prefix, " index=", index,
          " name=", representation.name, " description=", representation.description,
          " bytes=", representation.dataSize, " text=", representation.isText, " file=", filename));
      }
    }
  }
  // NV-DXVK end
  
  DxvkPipelineManager::DxvkPipelineManager(
          DxvkDevice*         device,
          DxvkRenderPassPool* passManager)
  : m_device    (device),
    m_cache     (new DxvkPipelineCache(device->vkd())) {
    std::string useStateCache = env::getEnvVar("DXVK_STATE_CACHE");
    
    if (useStateCache != "0" && device->config().enableStateCache)
      m_stateCache = new DxvkStateCache(device, this, passManager);
  }
  
  
  DxvkPipelineManager::~DxvkPipelineManager() {
    
  }
  
  
  DxvkComputePipeline* DxvkPipelineManager::createComputePipeline(
    const DxvkComputePipelineShaders& shaders) {
    if (shaders.cs == nullptr)
      return nullptr;
    
    std::lock_guard<dxvk::mutex> lock(m_mutex);
    
    auto pair = m_computePipelines.find(shaders);
    if (pair != m_computePipelines.end())
      return &pair->second;
    
    auto iter = m_computePipelines.emplace(
      std::piecewise_construct,
      std::tuple(shaders),
      std::tuple(this, shaders));
    return &iter.first->second;
  }
  
  
  DxvkGraphicsPipeline* DxvkPipelineManager::createGraphicsPipeline(
    const DxvkGraphicsPipelineShaders& shaders) {
    if (shaders.vs == nullptr)
      return nullptr;
    
    std::lock_guard<dxvk::mutex> lock(m_mutex);
    
    auto pair = m_graphicsPipelines.find(shaders);
    if (pair != m_graphicsPipelines.end())
      return &pair->second;
    
    auto iter = m_graphicsPipelines.emplace(
      std::piecewise_construct,
      std::tuple(shaders),
      std::tuple(this, shaders));
    return &iter.first->second;
  }


  DxvkRaytracingPipeline* DxvkPipelineManager::createRaytracingPipeline(
    const DxvkRaytracingPipelineShaders& shaders) {
    if (shaders.groups.empty())
      return nullptr;

    std::lock_guard<dxvk::mutex> lock(m_mutex);

    auto pair = m_raytracingPipelines.find(shaders);
    if (pair != m_raytracingPipelines.end())
      return &pair->second;

    auto iter = m_raytracingPipelines.emplace(
      std::piecewise_construct,
      std::tuple(shaders),
      std::tuple(this, shaders));
    return &iter.first->second;
  }
  

  void DxvkPipelineManager::registerShader(
    const Rc<DxvkShader>&         shader,
// NV-DXVK start
    bool                          isRemixShader) {
// NV-DXVK end
    if (m_stateCache != nullptr)
// NV-DXVK start
      m_stateCache->registerShader(shader, isRemixShader);
// NV-DXVK end
  }

  // NV-DXVK start: compile raytracing shaders on shader compilation threads
  namespace WAR4000939 {
    extern bool shouldApply(const Rc<DxvkDevice>& device);
  }
  void DxvkPipelineManager::registerRaytracingShaders(
    const DxvkRaytracingPipelineShaders& shaders) {
    if (m_stateCache != nullptr)
      m_stateCache->registerRaytracingShaders(shaders);
    else {
      // WAR: when pipelines are not compiled on the compilation threadpool
      // we need to frontload the OMM pipeline compiles in-place due to driver bug.
      if (WAR4000939::shouldApply(m_device) &&
          0 != (shaders.pipelineFlags & VK_PIPELINE_CREATE_RAY_TRACING_OPACITY_MICROMAP_BIT_EXT)) {
        createRaytracingPipeline(shaders)->compilePipeline();
      }
    }
  }
  // NV-DXVK end

  DxvkPipelineCount DxvkPipelineManager::getPipelineCount() const {
    DxvkPipelineCount result;
    result.numComputePipelines  = m_numComputePipelines.load();
    result.numGraphicsPipelines = m_numGraphicsPipelines.load();
    return result;
  }


  bool DxvkPipelineManager::isCompilingShaders() const {
    return m_stateCache != nullptr
        && m_stateCache->isCompilingShaders();
  }

// NV-DXVK start
  uint32_t DxvkPipelineManager::remixShaderCompilationCount() const {
    return m_stateCache != nullptr ? m_stateCache->remixShaderCompilationCount() : 0;
  }
// NV-DXVK end


  // NV-DXVK start: Optional driver executable statistics.
  VkPipelineCreateFlags DxvkPipelineManager::pipelineCaptureFlags() const {
    if (!m_device->config().enablePipelineStatistics ||
        !m_device->features().khrPipelineExecutableProperties.pipelineExecutableInfo) {
      return 0;
    }
    VkPipelineCreateFlags flags = VK_PIPELINE_CREATE_CAPTURE_STATISTICS_BIT_KHR;
    if (!pipelineRepresentationDumpPath().empty()) {
      flags |= VK_PIPELINE_CREATE_CAPTURE_INTERNAL_REPRESENTATIONS_BIT_KHR;
    }
    return flags;
  }

  void DxvkPipelineManager::logPipelineStatistics(VkPipeline pipeline, const DxvkShader& shader) const {
    if (!pipelineCaptureFlags()) {
      return;
    }

    const auto vkd = m_device->vkd();
    const std::string key = shader.getShaderKey().toString();
    VkPipelineInfoKHR pipelineInfo = { VK_STRUCTURE_TYPE_PIPELINE_INFO_KHR };
    pipelineInfo.pipeline = pipeline;
    uint32_t executableCount = 0;
    VkResult status = vkd->vkGetPipelineExecutablePropertiesKHR(vkd->device(), &pipelineInfo, &executableCount, nullptr);
    if (status != VK_SUCCESS) {
      Logger::warn(str::format("PipelineStatistics shader=", key, " propertyCountStatus=", status));
      return;
    }
    Logger::info(str::format("PipelineExecutableSummary shader=", key, " debugName=", shader.debugName(),
      " pipeline=", pipeline, " count=", executableCount));
    if (!executableCount) {
      return;
    }
    std::vector<VkPipelineExecutablePropertiesKHR> executables(executableCount,
      VkPipelineExecutablePropertiesKHR { VK_STRUCTURE_TYPE_PIPELINE_EXECUTABLE_PROPERTIES_KHR });
    status = vkd->vkGetPipelineExecutablePropertiesKHR(vkd->device(), &pipelineInfo, &executableCount, executables.data());
    if (status != VK_SUCCESS) {
      Logger::warn(str::format("PipelineStatistics shader=", key, " propertiesStatus=", status));
      return;
    }
    for (uint32_t index = 0; index < executableCount; ++index) {
      const auto& executable = executables[index];
      Logger::info(str::format("PipelineExecutable shader=", key, " debugName=", shader.debugName(),
        " pipeline=", pipeline, " index=", index, " stages=", executable.stages,
        " subgroupSize=", executable.subgroupSize, " name=", executable.name,
        " description=", executable.description));

      VkPipelineExecutableInfoKHR executableInfo = { VK_STRUCTURE_TYPE_PIPELINE_EXECUTABLE_INFO_KHR };
      executableInfo.pipeline = pipeline;
      executableInfo.executableIndex = index;
      dumpPipelineRepresentations(vkd, executableInfo, key);
      uint32_t statisticCount = 0;
      status = vkd->vkGetPipelineExecutableStatisticsKHR(vkd->device(), &executableInfo, &statisticCount, nullptr);
      if (status != VK_SUCCESS) {
        Logger::warn(str::format("PipelineStatistics shader=", key, " index=", index, " countStatus=", status));
        continue;
      }
      std::vector<VkPipelineExecutableStatisticKHR> statistics(statisticCount,
        VkPipelineExecutableStatisticKHR { VK_STRUCTURE_TYPE_PIPELINE_EXECUTABLE_STATISTIC_KHR });
      status = vkd->vkGetPipelineExecutableStatisticsKHR(vkd->device(), &executableInfo, &statisticCount, statistics.data());
      if (status != VK_SUCCESS) {
        Logger::warn(str::format("PipelineStatistics shader=", key, " index=", index, " statisticsStatus=", status));
        continue;
      }
      for (uint32_t statisticIndex = 0; statisticIndex < statisticCount; ++statisticIndex) {
        const auto& statistic = statistics[statisticIndex];
        std::string value;
        switch (statistic.format) {
          case VK_PIPELINE_EXECUTABLE_STATISTIC_FORMAT_BOOL32_KHR:
            value = statistic.value.b32 ? "true" : "false";
            break;
          case VK_PIPELINE_EXECUTABLE_STATISTIC_FORMAT_INT64_KHR:
            value = std::to_string(statistic.value.i64);
            break;
          case VK_PIPELINE_EXECUTABLE_STATISTIC_FORMAT_UINT64_KHR:
            value = std::to_string(statistic.value.u64);
            break;
          case VK_PIPELINE_EXECUTABLE_STATISTIC_FORMAT_FLOAT64_KHR:
            value = std::to_string(statistic.value.f64);
            break;
          default:
            value = "unsupported";
            break;
        }
        Logger::info(str::format("PipelineStatistics shader=", key, " index=", index,
          " name=", statistic.name, " format=", statistic.format, " value=", value,
          " description=", statistic.description));
      }
    }
  }
  // NV-DXVK end

  void DxvkPipelineManager::stopWorkerThreads() const {
    if (m_stateCache != nullptr)
      m_stateCache->stopWorkerThreads();
  }

}

// NV-DXVK start
// See declaration in dxvk_pipemanager.h.
uint32_t remixinternal_GetShaderCompilationCount() {
  dxvk::DxvkDevice* pDevice = dxvk::g_dxvkDeviceNative;
  if (pDevice == nullptr) {
    return UINT32_MAX;
  }
  return pDevice->getCommon()->pipelineManager().remixShaderCompilationCount();
}
// NV-DXVK end
