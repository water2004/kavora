/*
 * Copyright (c) 2026 RTX Remix Minecraft contributors.
 * SPDX-License-Identifier: MIT
 */
#include <algorithm>
#include <cmath>
#include <cstring>
#include <filesystem>
#include <limits>
#include <map>
#include <mutex>
#include <string>

#include "rtx_remix_api_config.h"
#include "rtx_option.h"
#include "rtx_option_manager.h"
#include "../../util/util_string.h"

namespace dxvk::remixapi_config {
  namespace {
    struct LayerEntry {
      std::string path;
      uint32_t priority;
      RtxOptionLayer* layer;
      uint64_t references;
    };

    std::mutex s_mutex;
    std::map<uint64_t, LayerEntry> s_layers;
    std::map<std::string, std::string> s_pendingVariables;
    uint64_t s_nextHandle = 1;

    uint64_t handleId(remixapi_ConfigLayerHandle handle) {
      return reinterpret_cast<uint64_t>(handle);
    }

    remixapi_ErrorCode resultCode(DynamicOptionResult result) {
      switch (result) {
      case DynamicOptionResult::Success:
        return REMIXAPI_ERROR_CODE_SUCCESS;
      case DynamicOptionResult::InvalidArgument:
        return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
      default:
        return REMIXAPI_ERROR_CODE_GENERAL_FAILURE;
      }
    }

    const RtxOptionLayer* writeLayer(const RtxOptionImpl& option) {
      return (option.getFlags() & RtxOptionFlags::NoSave)
        ? RtxOptionLayer::getDerivedLayer() : RtxOptionLayer::getUserLayer();
    }
  }

  remixapi_ErrorCode acquireLayer(const char* path, float priority, float threshold, remixapi_ConfigLayerHandle* handle) {
    if (!path || !path[0] || !handle || !std::isfinite(priority) || !std::isfinite(threshold)) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }
    *handle = nullptr;
    std::error_code error;
    const auto fullPath = std::filesystem::absolute(std::filesystem::u8path(path), error).lexically_normal();
    if (error) {
      return REMIXAPI_ERROR_CODE_GENERAL_FAILURE;
    }
    const std::string normalizedPath(str::utf8Bytes(fullPath.u8string()));
    // Clamp before the float-to-integer conversion, which is undefined for out-of-range values.
    const uint32_t effectivePriority = static_cast<uint32_t>(std::round(std::clamp(priority,
      static_cast<float>(kMinDynamicRtxOptionLayerPriority), static_cast<float>(kMaxDynamicRtxOptionLayerPriority))));

    std::lock_guard<std::mutex> lock(s_mutex);
    for (auto& [id, entry] : s_layers) {
      if (entry.path == normalizedPath && entry.priority == effectivePriority) {
        if (entry.references == std::numeric_limits<uint64_t>::max()) {
          return REMIXAPI_ERROR_CODE_GENERAL_FAILURE;
        }
        ++entry.references;
        *handle = reinterpret_cast<remixapi_ConfigLayerHandle>(id);
        return REMIXAPI_ERROR_CODE_SUCCESS;
      }
    }
    Config config;
    if (!Config::getOptionLayerConfig(normalizedPath, config) || !s_nextHandle) {
      return REMIXAPI_ERROR_CODE_GENERAL_FAILURE;
    }
    const std::string name = "API: " + normalizedPath;
    const Config emptyConfig;
    RtxOptionLayer* layer = RtxOptionManager::acquireLayer(normalizedPath,
      RtxOptionLayerKey { effectivePriority, name }, 0.0f, std::clamp(threshold, 0.0f, 1.0f), false, &emptyConfig);
    {
      // Install the config only after disabling its empty layer, so threshold zero cannot apply it early.
      std::lock_guard<std::mutex> updateLock(RtxOptionImpl::getUpdateMutex());
      layer->requestEnabled(false);
      layer->resolvePendingRequests();
      layer->applyPendingChanges();
      layer->setConfig(config);
    }
    const uint64_t id = s_nextHandle++;
    s_layers.emplace(id, LayerEntry { normalizedPath, effectivePriority, layer, 1 });
    *handle = reinterpret_cast<remixapi_ConfigLayerHandle>(id);
    return REMIXAPI_ERROR_CODE_SUCCESS;
  }

  bool validateLayer(remixapi_ConfigLayerHandle handle) {
    std::lock_guard<std::mutex> lock(s_mutex);
    return s_layers.find(handleId(handle)) != s_layers.end();
  }

  remixapi_ErrorCode setLayerBlending(remixapi_ConfigLayerHandle handle, float strength, float threshold) {
    if (!std::isfinite(strength) || !std::isfinite(threshold)) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }
    std::lock_guard<std::mutex> lock(s_mutex);
    const auto it = s_layers.find(handleId(handle));
    if (it == s_layers.end()) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }
    std::lock_guard<std::mutex> updateLock(RtxOptionImpl::getUpdateMutex());
    RtxOptionLayer& layer = *it->second.layer;
    layer.requestEnabled(true);
    layer.requestBlendStrength(std::clamp(strength, 0.0f, 1.0f));
    layer.requestBlendThreshold(std::clamp(threshold, 0.0f, 1.0f));
    return REMIXAPI_ERROR_CODE_SUCCESS;
  }

  remixapi_ErrorCode releaseLayer(remixapi_ConfigLayerHandle handle) {
    std::lock_guard<std::mutex> lock(s_mutex);
    const auto it = s_layers.find(handleId(handle));
    if (it == s_layers.end()) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }
    if (!--it->second.references) {
      RtxOptionManager::releaseLayer(it->second.layer);
      s_layers.erase(it);
    }
    return REMIXAPI_ERROR_CODE_SUCCESS;
  }

  void releaseAllLayers() {
    std::lock_guard<std::mutex> lock(s_mutex);
    for (const auto& [id, entry] : s_layers) {
      RtxOptionManager::releaseLayer(entry.layer);
    }
    s_layers.clear();
    s_pendingVariables.clear();
  }

  remixapi_ErrorCode getResolved(remixapi_ConfigVariableResolvedInfo* info) {
    if (!info || info->sType != REMIXAPI_STRUCT_TYPE_CONFIG_VARIABLE_RESOLVED_INFO || !info->key || !info->key[0]) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }
    if (!RtxOptionImpl::isInitialized()) {
      return REMIXAPI_ERROR_CODE_NOT_INITIALIZED;
    }
    std::string value;
    const remixapi_ErrorCode result = resultCode(RtxOptionManager::getResolvedValueText(info->key, value));
    if (result != REMIXAPI_ERROR_CODE_SUCCESS) {
      return result;
    }
    if (value.size() >= std::numeric_limits<uint32_t>::max()) {
      return REMIXAPI_ERROR_CODE_GENERAL_FAILURE;
    }
    const uint32_t capacity = info->valueSize;
    info->valueSize = static_cast<uint32_t>(value.size() + 1);
    if (!info->value) {
      return REMIXAPI_ERROR_CODE_SUCCESS;
    }
    if (capacity < info->valueSize) {
      return REMIXAPI_ERROR_CODE_BUFFER_TOO_SMALL;
    }
    std::memcpy(info->value, value.c_str(), info->valueSize);
    return REMIXAPI_ERROR_CODE_SUCCESS;
  }

  remixapi_ErrorCode setVariable(const char* key, const char* value) {
    if (!key || !key[0] || !value) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }
    const std::string name(key);
    RtxOptionImpl* option = RtxOptionImpl::getOptionByFullName(name);
    if (!option || option->isDynamic()) {
      return resultCode(RtxOptionManager::queueDynamicValue(name, value));
    }
    std::lock_guard<std::mutex> lock(s_mutex);
    std::lock_guard<std::mutex> updateLock(RtxOptionImpl::getUpdateMutex());
    const bool initialized = RtxOptionImpl::isInitialized();
    const RtxOptionLayer* target = initialized ? writeLayer(*option) : nullptr;
    if (!option->readOptionText(value, target)) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }
    if (!initialized) {
      s_pendingVariables.insert_or_assign(name, value);
    } else if (!target) {
      return REMIXAPI_ERROR_CODE_NOT_INITIALIZED;
    } else if (target == RtxOptionLayer::getUserLayer()) {
      RtxOptionManager::noteExplicitUserWrite(option);
    }
    return REMIXAPI_ERROR_CODE_SUCCESS;
  }

  void applyPendingVariables() {
    std::lock_guard<std::mutex> lock(s_mutex);
    std::lock_guard<std::mutex> updateLock(RtxOptionImpl::getUpdateMutex());
    if (!RtxOptionImpl::isInitialized()) {
      return;
    }
    for (const auto& [key, value] : s_pendingVariables) {
      if (RtxOptionImpl* option = RtxOptionImpl::getOptionByFullName(key)) {
        const auto* target = writeLayer(*option);
        if (option->readOptionText(value, target) && target == RtxOptionLayer::getUserLayer()) {
          RtxOptionManager::noteExplicitUserWrite(option);
        }
      }
    }
    s_pendingVariables.clear();
  }
}
