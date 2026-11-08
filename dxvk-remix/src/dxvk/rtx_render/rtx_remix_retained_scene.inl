/*
* Copyright (c) 2026, NVIDIA CORPORATION. All rights reserved.
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

// Included inside rtx_remix_api.cpp's anonymous namespace after DrawInstance.

// Public handle validity belongs to the calling thread under s_mutex. The owned
// draw templates belong to the CS thread and follow the device command order.
std::unordered_set<uint64_t> s_retainedSceneHandles;
std::unordered_map<uint64_t, std::unique_ptr<dxvk::ExternalDrawState>> s_retainedSceneInstances;
std::unordered_multimap<remixapi_MeshHandle, uint64_t> s_retainedSceneMeshes;
uint64_t s_nextRetainedSceneId = 1;

void replayRetainedSceneInstances(dxvk::DxvkContext* ctx) {
  auto* rtxContext = static_cast<dxvk::RtxContext*>(ctx);
  for (const auto& entry : s_retainedSceneInstances) {
    // Submission mutates camera, geometry, and GPU-instancing data. The template
    // owns its extension payloads and stays untouched for the next frame.
    dxvk::ExternalDrawState frameState = *entry.second;
    rtxContext->commitExternalGeometryToRT(frameState);
  }
}

void removeRetainedSceneMesh(dxvk::DxvkContext* ctx, remixapi_MeshHandle mesh) {
  auto& scene = ctx->getCommonObjects()->getSceneManager();
  const auto range = s_retainedSceneMeshes.equal_range(mesh);
  for (auto it = range.first; it != range.second; ++it) {
    scene.removeExternalSceneInstance(it->second);
  }
}

void unlinkRetainedSceneMesh(remixapi_MeshHandle mesh, uint64_t id) {
  const auto range = s_retainedSceneMeshes.equal_range(mesh);
  for (auto it = range.first; it != range.second; ++it) {
    if (it->second == id) {
      s_retainedSceneMeshes.erase(it);
      return;
    }
  }
}

void clearRetainedSceneInstances(dxvk::DxvkContext* ctx) {
  auto& scene = ctx->getCommonObjects()->getSceneManager();
  for (const auto& entry : s_retainedSceneInstances) {
    scene.removeExternalSceneInstance(entry.first);
  }
  s_retainedSceneInstances.clear();
  s_retainedSceneMeshes.clear();
}

void resetRetainedSceneHandles() {
  s_retainedSceneHandles.clear();
  // IDs are never reused, including after shutdown. A stale handle cannot name
  // an instance created by a subsequent runtime session.
}

remixapi_ErrorCode REMIXAPI_CALL remixapi_SceneInstanceAdd(
  const remixapi_InstanceInfo* info,
  remixapi_SceneInstanceHandle* out_handle) {
  ScopedCpuProfileZoneN("Remix API SceneInstanceAdd");
  auto* device = tryAsDxvk();
  if (!device) {
    return REMIXAPI_ERROR_CODE_REMIX_DEVICE_WAS_NOT_REGISTERED;
  }
  if (!info || info->sType != REMIXAPI_STRUCT_TYPE_INSTANCE_INFO || !info->mesh || !out_handle) {
    return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
  }

  auto state = convert::toRtDrawState(*info);
  std::lock_guard lock { s_mutex };
  if (s_nextRetainedSceneId == (std::numeric_limits<uint64_t>::max)()) {
    return REMIXAPI_ERROR_CODE_GENERAL_FAILURE;
  }
  const uint64_t id = s_nextRetainedSceneId++;
  state->retainedId = id;
  s_retainedSceneHandles.insert(id);
  try {
    device->EmitCs([id, state = std::move(state)](dxvk::DxvkContext*) mutable {
      s_retainedSceneMeshes.emplace(state->mesh, id);
      s_retainedSceneInstances.emplace(id, std::move(state));
    });
  } catch (...) {
    s_retainedSceneHandles.erase(id);
    throw;
  }
  *out_handle = reinterpret_cast<remixapi_SceneInstanceHandle>(id);
  return REMIXAPI_ERROR_CODE_SUCCESS;
}

remixapi_ErrorCode REMIXAPI_CALL remixapi_SceneInstanceUpdate(
  remixapi_SceneInstanceHandle handle,
  const remixapi_InstanceInfo* info) {
  ScopedCpuProfileZoneN("Remix API SceneInstanceUpdate");
  auto* device = tryAsDxvk();
  if (!device) {
    return REMIXAPI_ERROR_CODE_REMIX_DEVICE_WAS_NOT_REGISTERED;
  }
  if (!handle || !info || info->sType != REMIXAPI_STRUCT_TYPE_INSTANCE_INFO || !info->mesh) {
    return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
  }

  auto state = convert::toRtDrawState(*info);
  const uint64_t id = reinterpret_cast<uint64_t>(handle);
  std::lock_guard lock { s_mutex };
  if (s_retainedSceneHandles.count(id) == 0) {
    return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
  }
  state->retainedId = id;
  device->EmitCs([id, state = std::move(state)](dxvk::DxvkContext* ctx) mutable {
    auto found = s_retainedSceneInstances.find(id);
    if (found != s_retainedSceneInstances.end()) {
      state->retainedRevision = found->second->retainedRevision + 1;
      if (found->second->mesh != state->mesh) {
        ctx->getCommonObjects()->getSceneManager().removeExternalSceneInstance(id);
        unlinkRetainedSceneMesh(found->second->mesh, id);
        s_retainedSceneMeshes.emplace(state->mesh, id);
      }
      found->second = std::move(state);
    }
  });
  return REMIXAPI_ERROR_CODE_SUCCESS;
}

remixapi_ErrorCode REMIXAPI_CALL remixapi_SceneInstanceRemove(remixapi_SceneInstanceHandle handle) {
  ScopedCpuProfileZoneN("Remix API SceneInstanceRemove");
  auto* device = tryAsDxvk();
  if (!device) {
    return REMIXAPI_ERROR_CODE_REMIX_DEVICE_WAS_NOT_REGISTERED;
  }
  const uint64_t id = reinterpret_cast<uint64_t>(handle);
  std::lock_guard lock { s_mutex };
  if (!handle || s_retainedSceneHandles.count(id) == 0) {
    return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
  }
  device->EmitCs([id](dxvk::DxvkContext* ctx) {
    ctx->getCommonObjects()->getSceneManager().removeExternalSceneInstance(id);
    const auto found = s_retainedSceneInstances.find(id);
    if (found != s_retainedSceneInstances.end()) {
      unlinkRetainedSceneMesh(found->second->mesh, id);
    }
    s_retainedSceneInstances.erase(id);
  });
  s_retainedSceneHandles.erase(id);
  return REMIXAPI_ERROR_CODE_SUCCESS;
}

remixapi_ErrorCode REMIXAPI_CALL remixapi_UpdateMaterial(
  remixapi_MaterialHandle handle,
  const remixapi_MaterialInfo* info) {
  auto* device = tryAsDxvk();
  if (!device) {
    return REMIXAPI_ERROR_CODE_REMIX_DEVICE_WAS_NOT_REGISTERED;
  }
  if (!handle || !info || info->sType != REMIXAPI_STRUCT_TYPE_MATERIAL_INFO) {
    return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
  }
  std::lock_guard lock { s_mutex };
  device->EmitCs([handle,
                  data = convert::toRtMaterialWithoutTexturePreload(*info),
                  source = convert::makePreloadSource(*info)](dxvk::DxvkContext* ctx) {
    auto& assets = ctx->getCommonObjects()->getSceneManager().getAssetReplacer();
    assets->updateExternalMaterial(handle, convert::toRtMaterialFinalized(*ctx, data, source));
  });
  return REMIXAPI_ERROR_CODE_SUCCESS;
}

