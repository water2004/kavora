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

// Exercise command submission and its assertions in Release test builds.
#undef NDEBUG
#include <cassert>
#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

#include <remix/remix_c.h>

#include "../../../src/dxvk/dxvk_scoped_annotation.h"

// Exercise the API command queue with a consuming submission target. This keeps
// lifetime and command-order tests independent of a GPU and a D3D9 device.
namespace dxvk {
  struct ExternalDrawState {
    remixapi_MeshHandle mesh {};
    uint64_t retainedId = 0;
    uint64_t retainedRevision = 1;
    float translation = 0;
    std::vector<float> bones;
  };

  struct MaterialData {
    float emissiveIntensity = 0;
  };

  struct AssetReplacer {
    std::unordered_map<remixapi_MaterialHandle, MaterialData> materials;

    void updateExternalMaterial(remixapi_MaterialHandle handle, MaterialData&& data) {
      if (materials.count(handle)) {
        materials[handle] = std::move(data);
      }
    }
  };

  struct SceneManager {
    std::vector<uint64_t> removed;
    std::unordered_map<uint64_t, ExternalDrawState> visible;
    std::unique_ptr<AssetReplacer> assets = std::make_unique<AssetReplacer>();

    void removeExternalSceneInstance(uint64_t id) {
      removed.push_back(id);
      visible.erase(id);
    }

    std::unique_ptr<AssetReplacer>& getAssetReplacer() {
      return assets;
    }
  };

  struct CommonObjects {
    SceneManager scene;

    SceneManager& getSceneManager() {
      return scene;
    }
  };

  class DxvkContext {
  public:
    CommonObjects common;

    CommonObjects* getCommonObjects() {
      return &common;
    }
  };

  struct RtxContext : DxvkContext {
    void commitExternalGeometryToRT(ExternalDrawState& state) {
      auto& scene = common.scene;
      scene.visible[state.retainedId] = state;
      state.bones.clear();
      state.translation = -1;
    }
  };

  struct D3D9DeviceEx {
    struct Command {
      virtual ~Command() = default;
      virtual void run(DxvkContext*) = 0;
    };

    template<typename F>
    struct CapturedCommand : Command {
      F function;

      explicit CapturedCommand(F&& input) : function(std::move(input)) { }

      void run(DxvkContext* ctx) override {
        function(ctx);
      }
    };

    std::vector<std::unique_ptr<Command>> commands;
    bool failNext = false;

    template<typename F>
    void EmitCs(F&& function) {
      if (failNext) {
        failNext = false;
        throw std::bad_alloc();
      }
      commands.push_back(std::make_unique<CapturedCommand<F>>(std::move(function)));
    }

    void flush(DxvkContext* ctx) {
      for (auto& command : commands) {
        command->run(ctx);
      }
      commands.clear();
    }
  };
}

namespace {
  std::mutex s_mutex;
  dxvk::D3D9DeviceEx* s_device = nullptr;

  dxvk::D3D9DeviceEx* tryAsDxvk() {
    return s_device;
  }

  namespace convert {
    std::shared_ptr<int> textureSource;
    int finalizedTextureValue = 0;
    std::unique_ptr<dxvk::ExternalDrawState> toRtDrawState(const remixapi_InstanceInfo& info) {
      auto state = std::make_unique<dxvk::ExternalDrawState>();
      state->mesh = info.mesh;
      state->translation = info.transform.matrix[0][3];
      auto* bones = static_cast<remixapi_InstanceInfoBoneTransformsEXT*>(info.pNext);
      if (bones) {
        for (uint32_t i = 0; i < bones->boneTransforms_count; ++i) {
          state->bones.push_back(bones->boneTransforms_values[i].matrix[0][3]);
        }
      }
      return state;
    }

    dxvk::MaterialData toRtMaterialWithoutTexturePreload(const remixapi_MaterialInfo& info) {
      return { info.emissiveIntensity };
    }

    std::shared_ptr<int> makePreloadSource(const remixapi_MaterialInfo&) {
      return textureSource;
    }

    dxvk::MaterialData toRtMaterialFinalized(dxvk::DxvkContext&, const dxvk::MaterialData& data, const std::shared_ptr<int>& source) {
      finalizedTextureValue = source ? *source : 0;
      return data;
    }
  }

#include "../../../src/dxvk/rtx_render/rtx_remix_retained_scene.inl"
}

int main() {
  dxvk::D3D9DeviceEx device;
  dxvk::RtxContext context;
  auto& scene = context.common.scene;
  remixapi_InstanceInfo info {};
  info.sType = REMIXAPI_STRUCT_TYPE_INSTANCE_INFO;
  info.mesh = reinterpret_cast<remixapi_MeshHandle>(41);
  remixapi_SceneInstanceHandle first {};
  assert(remixapi_SceneInstanceAdd(&info, &first) == REMIXAPI_ERROR_CODE_REMIX_DEVICE_WAS_NOT_REGISTERED);
  s_device = &device;
  assert(remixapi_SceneInstanceAdd(nullptr, &first) == REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS);
  assert(remixapi_SceneInstanceAdd(&info, nullptr) == REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS);

  remixapi_Transform bone {};
  bone.matrix[0][3] = 7;
  remixapi_InstanceInfoBoneTransformsEXT bones {};
  bones.sType = REMIXAPI_STRUCT_TYPE_INSTANCE_INFO_BONE_TRANSFORMS_EXT;
  bones.boneTransforms_count = 1;
  bones.boneTransforms_values = &bone;
  info.pNext = &bones;
  info.transform.matrix[0][3] = 1;
  assert(remixapi_SceneInstanceAdd(&info, &first) == REMIXAPI_ERROR_CODE_SUCCESS);
  info.transform.matrix[0][3] = 2;
  bone.matrix[0][3] = 99;
  assert(s_retainedSceneInstances.empty());
  device.flush(&context);
  const uint64_t firstId = reinterpret_cast<uint64_t>(first);
  assert(s_retainedSceneInstances.at(firstId)->translation == 1);
  assert(s_retainedSceneInstances.at(firstId)->bones[0] == 7);
  replayRetainedSceneInstances(&context);
  replayRetainedSceneInstances(&context);
  assert(scene.visible.at(firstId).translation == 1);
  assert(scene.visible.at(firstId).bones[0] == 7);
  assert(scene.visible.at(firstId).retainedRevision == 1);

  remixapi_SceneInstanceHandle second {};
  assert(remixapi_SceneInstanceAdd(&info, &second) == REMIXAPI_ERROR_CODE_SUCCESS);
  assert(first != second);
  device.flush(&context);
  replayRetainedSceneInstances(&context);
  assert(scene.visible.size() == 2);
  assert(s_retainedSceneMeshes.count(info.mesh) == 2);

  assert(remixapi_SceneInstanceUpdate(first, &info) == REMIXAPI_ERROR_CODE_SUCCESS);
  replayRetainedSceneInstances(&context);
  assert(scene.visible.at(firstId).translation == 1);
  assert(scene.visible.at(firstId).retainedRevision == 1);
  const size_t oldRemovals = scene.removed.size();
  device.flush(&context);
  assert(scene.removed.size() == oldRemovals);
  replayRetainedSceneInstances(&context);
  assert(scene.visible.at(firstId).translation == 2);
  assert(scene.visible.at(firstId).bones[0] == 99);
  assert(scene.visible.at(firstId).retainedRevision == 2);

  auto oldMesh = info.mesh;
  info.mesh = reinterpret_cast<remixapi_MeshHandle>(42);
  assert(remixapi_SceneInstanceUpdate(first, &info) == REMIXAPI_ERROR_CODE_SUCCESS);
  device.flush(&context);
  assert(scene.removed.back() == firstId);
  assert(scene.visible.count(firstId) == 0);
  replayRetainedSceneInstances(&context);
  assert(scene.visible.at(firstId).mesh == info.mesh);
  assert(scene.visible.at(firstId).retainedRevision == 3);
  assert(s_retainedSceneMeshes.count(oldMesh) == 1);
  assert(s_retainedSceneMeshes.count(info.mesh) == 1);

  removeRetainedSceneMesh(&context, oldMesh);
  assert(scene.visible.count(reinterpret_cast<uint64_t>(second)) == 0);
  assert(scene.visible.count(firstId) == 1);
  assert(s_retainedSceneInstances.size() == 2);
  device.failNext = true;
  try {
    remixapi_SceneInstanceRemove(first);
    assert(false);
  } catch (const std::bad_alloc&) {
    assert(s_retainedSceneHandles.count(firstId) == 1);
  }
  assert(remixapi_SceneInstanceRemove(first) == REMIXAPI_ERROR_CODE_SUCCESS);
  assert(remixapi_SceneInstanceRemove(first) == REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS);
  assert(remixapi_SceneInstanceUpdate(first, &info) == REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS);
  device.flush(&context);
  assert(scene.visible.count(firstId) == 0);
  assert(s_retainedSceneInstances.count(firstId) == 0);
  assert(s_retainedSceneMeshes.count(info.mesh) == 0);
  const size_t removalsAfterDestroy = scene.removed.size();
  removeRetainedSceneMesh(&context, info.mesh);
  assert(scene.removed.size() == removalsAfterDestroy);

  auto materialHandle = reinterpret_cast<remixapi_MaterialHandle>(71);
  scene.assets->materials.emplace(materialHandle, dxvk::MaterialData { 1 });
  remixapi_MaterialInfo material {};
  material.sType = REMIXAPI_STRUCT_TYPE_MATERIAL_INFO;
  material.hash = 999;
  material.emissiveIntensity = 2;
  assert(remixapi_UpdateMaterial(nullptr, &material) == REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS);
  convert::textureSource = std::make_shared<int>(321);
  std::weak_ptr<int> textureLifetime = convert::textureSource;
  assert(remixapi_UpdateMaterial(materialHandle, &material) == REMIXAPI_ERROR_CODE_SUCCESS);
  convert::textureSource.reset();
  assert(!textureLifetime.expired());
  material.emissiveIntensity = 100;
  device.flush(&context);
  assert(scene.assets->materials.at(materialHandle).emissiveIntensity == 2);
  assert(convert::finalizedTextureValue == 321);
  assert(textureLifetime.expired());
  assert(scene.assets->materials.count(reinterpret_cast<remixapi_MaterialHandle>(999)) == 0);

  resetRetainedSceneHandles();
  clearRetainedSceneInstances(&context);
  assert(s_retainedSceneInstances.empty());
  assert(s_retainedSceneMeshes.empty());
  assert(scene.visible.empty());
  assert(remixapi_SceneInstanceRemove(second) == REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS);
  const auto handlesBeforeFailure = s_retainedSceneHandles.size();
  device.failNext = true;
  try {
    remixapi_SceneInstanceHandle failed {};
    remixapi_SceneInstanceAdd(&info, &failed);
    assert(false);
  } catch (const std::bad_alloc&) {
    assert(s_retainedSceneHandles.size() == handlesBeforeFailure);
  }
  remixapi_SceneInstanceHandle nextSession {};
  assert(remixapi_SceneInstanceAdd(&info, &nextSession) == REMIXAPI_ERROR_CODE_SUCCESS);
  assert(nextSession != first && nextSession != second);
  device.flush(&context);
  resetRetainedSceneHandles();
  clearRetainedSceneInstances(&context);
  s_device = nullptr;
}
