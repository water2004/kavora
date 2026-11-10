/*
* Copyright (c) 2021-2026, NVIDIA CORPORATION. All rights reserved.
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

#define RTX_REMIX_PNEXT_CHECK_STRUCTS

#include <unordered_map>

#include "rtx_asset_data_manager.h"
#include "rtx_asset_replacer.h"
#include "rtx_light_manager.h"
#include "rtx_objectpicking.h"
#include "rtx_option.h"
#include "rtx_option_manager.h"
#include "rtx_globals.h"
#include "rtx_options.h"
#include "rtx_debug_view.h"
#include "rtx_remix_texture_updates.h"
#include "rtx_remix_texture_mips.h"
#include "rtx_remix_mesh_indices.h"
#include "rtx_remix_mesh_vertices.h"
#include "rtx_geometry_utils.h"

#include "../dxvk_device.h"
#include "rtx_texture_manager.h"
#include "rtx_semaphore.h"
#include "rtx_remix_texture_data.h"
#include "rtx_remix_api_config.h"
#include "rtx_ngx_wrapper.h"

#include <remix/remix_c.h>
#include "rtx_remix_pnext.h"

#include "../dxvk_image.h"

#include "../../util/util_math.h"
#include "../../util/util_env.h"
#include "../../util/util_vector.h"
#include "../../util/util_string.h"
#include "../../util/util_sentry.h"

#include "../../d3d9/d3d9_swapchain.h"
#include "../../d3d9/d3d9_interface.h"
#include "rtx_nsight_capture.h"

#include <windows.h>

#include <optional>
#include <memory>
#include <unordered_map>
#include <unordered_set>
#include <limits>
#include <atomic>
#include <cmath>

namespace dxvk {
  HRESULT CreateD3D9(
          bool           Extended,
          IDirect3D9Ex** ppDirect3D9Ex,
          bool           WithExternalSwapchain,
          bool           WithDrawCallConversion,
          bool           WithRemixAPI,
          const DxvkHostInstanceInfo* host = nullptr);

  void InitializeD3D9Runtime();

  extern bool g_allowSrgbConversionForOutput;
  extern bool g_combineGuiInFinalColor;
  extern bool g_forceKeepObjectPickingImage;
  extern bool g_remixApiEditorModeEnabled;   // defined in rtx_scene_manager.cpp

  extern std::array<uint8_t, 3> g_customHighlightColor;
}

namespace dxvk {
  // Because DrawCallState/LegacyMaterialData hide needed fields as private
  struct RemixAPIPrivateAccessor {
    static std::unique_ptr<ExternalDrawState> toRtDrawState(const remixapi_InstanceInfo& info);
  };
}

namespace {
  uint64_t s_apiVersion{ 0 };
  IDirect3D9Ex* s_dxvkD3D9 { nullptr };
  dxvk::D3D9DeviceEx* s_dxvkDevice { nullptr };
  dxvk::Com<IDirect3D9Ex> s_hostD3D9;
  bool s_hostVulkanPrepared = false;
  dxvk::mutex s_mutex {};
  std::unordered_map<remixapi_LightHandle, bool> s_apiLightKinds;


  dxvk::D3D9DeviceEx* tryAsDxvk() {
    return s_dxvkDevice;
  }


  #include "rtx_remix_textures.inl"

  void replayRetainedSceneInstances(dxvk::DxvkContext* ctx);
  void clearRetainedSceneInstances(dxvk::DxvkContext* ctx);
  void removeRetainedSceneMesh(dxvk::DxvkContext* ctx, remixapi_MeshHandle mesh);
  void resetRetainedSceneHandles();

  // from rtx_mod_usd.cpp
  XXH64_hash_t hack_getNextGeomHash() {
    static uint64_t s_id = UINT64_MAX;
    std::lock_guard lock { s_mutex };
    --s_id;
    return XXH64(&s_id, sizeof(s_id), 0);
  }


  template<typename T>
  size_t sizeInBytes(const T* values, size_t count) {
    return sizeof(T) * count;
  }


  bool isHResultAliasedWithRemixErrorCode(HRESULT hr) {
    switch (hr) {
    case REMIXAPI_ERROR_CODE_HRESULT_NO_REQUIRED_GPU_FEATURES:
    case REMIXAPI_ERROR_CODE_HRESULT_DRIVER_VERSION_BELOW_MINIMUM:
    case REMIXAPI_ERROR_CODE_HRESULT_DXVK_INSTANCE_EXTENSION_FAIL:
    case REMIXAPI_ERROR_CODE_HRESULT_VK_CREATE_INSTANCE_FAIL:
    case REMIXAPI_ERROR_CODE_HRESULT_VK_CREATE_DEVICE_FAIL: return true;
    default: return false;
    }
  }


  namespace convert {
    using namespace dxvk;

    std::string tostr(const remixapi_MaterialHandle& h) {
      static_assert(sizeof h == sizeof uint64_t);
      return std::to_string(reinterpret_cast<uint64_t>(h));
    }

    Matrix4 tomat4(const remixapi_Transform& transform) {
      const auto& m = transform.matrix;
      return Matrix4 {
        m[0][0], m[1][0], m[2][0], 0.f,
        m[0][1], m[1][1], m[2][1], 0.f,
        m[0][2], m[1][2], m[2][2], 0.f,
        m[0][3], m[1][3], m[2][3], 1.f
      };
    }

    Vector2 tovec2(const remixapi_Float2D& v) {
      return Vector2{ v.x, v.y };
    }

    Vector3 tovec3(const remixapi_Float3D& v) {
      return Vector3 { v.x, v.y, v.z };
    }

    Vector3d tovec3d(const remixapi_Float3D& v) {
      return Vector3d{ v.x, v.y, v.z };
    }

    std::vector<float> toAnimatedFloat1D(const remixapi_AnimatedFloat1D& animated) {
      std::vector<float> result;
      if (animated.pData && animated.numberElements > 0) {
        result.reserve(animated.numberElements);
        for (uint32_t i = 0; i < animated.numberElements; ++i) {
          result.push_back(animated.pData[i]);
        }
      }
      return result;
    }

    std::vector<vec2> toAnimatedFloat2D(const remixapi_AnimatedFloat2D& animated) {
      std::vector<vec2> result;
      if (animated.pData && animated.numberElements > 0) {
        result.reserve(animated.numberElements);
        for (uint32_t i = 0; i < animated.numberElements; ++i) {
          result.push_back(vec2(animated.pData[i].x, animated.pData[i].y));
        }
      }
      return result;
    }

    std::vector<vec3> toAnimatedFloat3D(const remixapi_AnimatedFloat3D& animated) {
      std::vector<vec3> result;
      if (animated.pData && animated.numberElements > 0) {
        result.reserve(animated.numberElements);
        for (uint32_t i = 0; i < animated.numberElements; ++i) {
          result.push_back(vec3(animated.pData[i].x, animated.pData[i].y, animated.pData[i].z));
        }
      }
      return result;
    }

    std::vector<vec4> toAnimatedFloat4D(const remixapi_AnimatedFloat4D& animated) {
      std::vector<vec4> result;
      if (animated.pData && animated.numberElements > 0) {
        result.reserve(animated.numberElements);
        for (uint32_t i = 0; i < animated.numberElements; ++i) {
          result.push_back(vec4(animated.pData[i].x, animated.pData[i].y, animated.pData[i].z, animated.pData[i].w));
        }
      }
      return result;
    }

    constexpr bool tobool(remixapi_Bool b) {
      return !!b;
    }

    std::filesystem::path topath(remixapi_Path p) {
      if (!p) {
        return {};
      }
      return p;
    }

    // --

    struct PreloadSource {
      std::shared_ptr<ApiTexture> albedoTexture;
      std::shared_ptr<ApiTexture> normalTexture;
      std::shared_ptr<ApiTexture> tangentTexture;
      std::shared_ptr<ApiTexture> emissiveTexture;
      std::shared_ptr<ApiTexture> transmittanceTexture;
      std::shared_ptr<ApiTexture> roughnessTexture;
      std::shared_ptr<ApiTexture> metallicTexture;
      std::shared_ptr<ApiTexture> heightTexture;
      std::shared_ptr<ApiTexture> subsurfaceTransmittanceTexture;
      std::shared_ptr<ApiTexture> subsurfaceThicknessTexture;
      std::shared_ptr<ApiTexture> subsurfaceSingleScatteringAlbedoTexture;
      std::shared_ptr<ApiTexture> subsurfaceRadiusTexture;
      std::shared_ptr<ApiTexture> vertexColorMaskTexture;
    };

    PreloadSource makePreloadSource(const remixapi_MaterialInfo& info) {
      // TODO: C++20 designated initializers
      if (auto extOpaque = pnext::find<remixapi_MaterialInfoOpaqueEXT>(&info)) {
        auto extSubsurface = pnext::find<remixapi_MaterialInfoOpaqueSubsurfaceEXT>(&info);
        auto vertexMask = pnext::find<remixapi_MaterialInfoVertexColorMaskEXT>(&info);
        return PreloadSource {
          lookupApiTexture(info.albedoTexture),   // albedoTexture;
          lookupApiTexture(info.normalTexture),   // normalTexture;
          lookupApiTexture(info.tangentTexture),  // tangentTexture;
          lookupApiTexture(info.emissiveTexture), // emissiveTexture;
          {},                           // transmittanceTexture;
          lookupApiTexture(extOpaque->roughnessTexture),  // roughnessTexture;
          lookupApiTexture(extOpaque->metallicTexture),   // metallicTexture;
          lookupApiTexture(extOpaque->heightTexture),     // heightTexture;
          lookupApiTexture(extSubsurface ? extSubsurface->subsurfaceTransmittanceTexture          : nullptr), // subsurfaceTransmittanceTexture;
          lookupApiTexture(extSubsurface ? extSubsurface->subsurfaceThicknessTexture              : nullptr), // subsurfaceThicknessTexture;
          lookupApiTexture(extSubsurface ? extSubsurface->subsurfaceSingleScatteringAlbedoTexture : nullptr), // subsurfaceSingleScatteringAlbedoTexture;
          lookupApiTexture(s_apiVersion >= REMIXAPI_VERSION_MAKE(0, 5, 1) && extSubsurface ? extSubsurface->subsurfaceRadiusTexture : nullptr), // subsurfaceRadiusTexture;
          lookupApiTexture(vertexMask ? vertexMask->texture : nullptr),
        };
      }
      if (auto extTranslucent = pnext::find<remixapi_MaterialInfoTranslucentEXT>(&info)) {
        return PreloadSource {
          lookupApiTexture(info.albedoTexture),   // albedoTexture;
          lookupApiTexture(info.normalTexture),   // normalTexture;
          lookupApiTexture(info.tangentTexture),  // tangentTexture;
          lookupApiTexture(info.emissiveTexture), // emissiveTexture;
          lookupApiTexture(extTranslucent->transmittanceTexture), // transmittanceTexture;
          {}, // roughnessTexture;
          {}, // metallicTexture;
          {}, // heightTexture;
          {}, // subsurfaceTransmittanceTexture;
          {}, // subsurfaceThicknessTexture;
          {}, // subsurfaceSingleScatteringAlbedoTexture;
          {}, // subsurfaceRadiusTexture;
        };
      }
      if (auto extPortal = pnext::find<remixapi_MaterialInfoPortalEXT>(&info)) {
        return PreloadSource {
          lookupApiTexture(info.albedoTexture),   // albedoTexture;
          lookupApiTexture(info.normalTexture),   // normalTexture;
          lookupApiTexture(info.tangentTexture),  // tangentTexture;
          lookupApiTexture(info.emissiveTexture), // emissiveTexture;
          {}, // transmittanceTexture;
          {}, // roughnessTexture;
          {}, // metallicTexture;
          {}, // heightTexture;
          {}, // subsurfaceTransmittanceTexture;
          {}, // subsurfaceThicknessTexture;
          {}, // subsurfaceSingleScatteringAlbedoTexture;
          {}, // subsurfaceRadiusTexture;
        };
      }
      return {};
    }

    MaterialData toRtMaterialFinalized(dxvk::DxvkContext& ctx, const MaterialData& materialWithoutPreload, const PreloadSource& preload) {
      auto preloadTexture = [&ctx](const std::shared_ptr<ApiTexture>& texture)->TextureRef {
        return resolveApiTexture(ctx, texture);
      };

      switch (materialWithoutPreload.getType()) {
      case MaterialDataType::Opaque:
      {
        const auto& src = materialWithoutPreload.getOpaqueMaterialData();
        return MaterialData { OpaqueMaterialData{
          preloadTexture(preload.albedoTexture),
          preloadTexture(preload.normalTexture),
          preloadTexture(preload.tangentTexture),
          preloadTexture(preload.heightTexture),
          preloadTexture(preload.roughnessTexture),
          preloadTexture(preload.metallicTexture),
          preloadTexture(preload.emissiveTexture),
          preloadTexture(preload.subsurfaceTransmittanceTexture),
          preloadTexture(preload.subsurfaceThicknessTexture),
          preloadTexture(preload.subsurfaceSingleScatteringAlbedoTexture),
          preloadTexture(preload.subsurfaceRadiusTexture),
          TextureRef(),
          preloadTexture(preload.vertexColorMaskTexture),
          src.getAnisotropyConstant(),
          src.getEmissiveIntensity(),
          src.getAlbedoConstant(),
          src.getOpacityConstant(),
          src.getRoughnessConstant(),
          src.getMetallicConstant(),
          src.getEmissiveColorConstant(),
          src.getEnableEmission(),
          src.getSpriteSheetRows(),
          src.getSpriteSheetCols(),
          src.getSpriteSheetFPS(),
          src.getEnableThinFilm(),
          src.getAlphaIsThinFilmThickness(),
          src.getThinFilmThicknessConstant(),
          src.getUseLegacyAlphaState(),
          src.getBlendEnabled(),
          src.getBlendType(),
          src.getInvertedBlend(),
          src.getAlphaTestType(),
          src.getAlphaTestReferenceValue(),
          src.getDisplaceIn(),
          src.getDisplaceOut(),
          src.getSubsurfaceTransmittanceColor(),
          src.getSubsurfaceMeasurementDistance(),
          src.getSubsurfaceSingleScatteringAlbedo(),
          src.getSubsurfaceVolumetricAnisotropy(),
          src.getSubsurfaceDiffusionProfile(),
          src.getSubsurfaceRadius(),
          src.getSubsurfaceRadiusScale(),
          src.getSubsurfaceMaxSampleRadius(),
          src.getFilterMode(),
          src.getWrapModeU(),
          src.getWrapModeV(),
          src.getEnableDLSSControlMask(),
          src.getDLSSControlMaskIntensity(),
          src.getDLSSControlMaskToneStrength(),
          src.getDLSSControlMaskStructuralStrength(),
          src.getTextureScrollRate(),
          src.getTextureScrollAngle(),
          src.getTextureScale(),
          src.getTextureOffset(),
          src.getTextureRotation()
        } };
      }
      case MaterialDataType::Translucent: 
      {
        const auto& src = materialWithoutPreload.getTranslucentMaterialData();
        return MaterialData { TranslucentMaterialData {
          preloadTexture(preload.normalTexture),
          preloadTexture(preload.transmittanceTexture),
          preloadTexture(preload.emissiveTexture),
          src.getRefractiveIndex(),
          src.getTransmittanceColor(),
          src.getTransmittanceMeasurementDistance(),
          src.getEnableEmission(),
          src.getEmissiveIntensity(),
          src.getEmissiveColorConstant(),
          src.getSpriteSheetRows(),
          src.getSpriteSheetCols(),
          src.getSpriteSheetFPS(),
          src.getEnableThinWalled(),
          src.getThinWallThickness(),
          src.getEnableDiffuseLayer(),
          src.getSimulatedWater(),
          src.getFilterMode(),
          src.getWrapModeU(),
          src.getWrapModeV(),
          src.getTextureScrollRate(),
          src.getTextureScrollAngle(),
          src.getTextureScale(),
          src.getTextureOffset(),
          src.getTextureRotation()
        } };
      }
      case MaterialDataType::RayPortal:
      {
        const auto& src = materialWithoutPreload.getRayPortalMaterialData();
        return MaterialData { RayPortalMaterialData {
          preloadTexture(preload.emissiveTexture),
          {}, // unused
          src.getRayPortalIndex(),
          src.getSpriteSheetRows(),
          src.getSpriteSheetCols(),
          src.getSpriteSheetFPS(),
          src.getRotationSpeed(),
          src.getEnableEmission(),
          src.getEmissiveIntensity(),
          src.getFilterMode(),
          src.getWrapModeU(),
          src.getWrapModeV()
        } };
      }
      default: assert(0); return materialWithoutPreload;
      }
    }

    MaterialData toRtMaterialWithoutTexturePreload(const remixapi_MaterialInfo& info) {
      if (auto extOpaque = pnext::find<remixapi_MaterialInfoOpaqueEXT>(&info)) {
        auto extSubsurface = pnext::find<remixapi_MaterialInfoOpaqueSubsurfaceEXT>(&info);
        const bool hasDlssControlMask = s_apiVersion >= REMIXAPI_VERSION_MAKE(0, 6, 5);
        return MaterialData { OpaqueMaterialData {
          {},
          {},
          {},
          {},
          {},
          {},
          {},
          {},
          {},
          {},
          {},
          {},
          {}, // vertex color mask is preloaded on the CS thread
          extOpaque->anisotropy,
          info.emissiveIntensity,
          tovec3(extOpaque->albedoConstant),
          extOpaque->opacityConstant,
          extOpaque->roughnessConstant,
          extOpaque->metallicConstant,
          tovec3(info.emissiveColorConstant),
          info.emissiveIntensity > 0.f,
          info.spriteSheetRow,
          info.spriteSheetCol,
          info.spriteSheetFps,
          tobool(extOpaque->thinFilmThickness_hasvalue),
          tobool(extOpaque->alphaIsThinFilmThickness),
          extOpaque->thinFilmThickness_hasvalue ? extOpaque->thinFilmThickness_value : 200.f, // default OpaqueMaterial::ThinFilmThicknessConstant
          tobool(extOpaque->useDrawCallAlphaState), // OpaqueMaterial::UseLegacyAlphaState
          tobool(extOpaque->blendType_hasvalue),
          extOpaque->blendType_hasvalue ? static_cast<BlendType>(extOpaque->blendType_value) : BlendType::kAlpha,  // default OpaqueMaterial::BlendType
          tobool(extOpaque->invertedBlend),
          static_cast<AlphaTestType>(extOpaque->alphaTestType),
          extOpaque->alphaReferenceValue,
          extOpaque->displaceIn,
          (s_apiVersion >= REMIXAPI_VERSION_MAKE(0, 4, 2)) ? extOpaque->displaceOut : 0.f,
          extSubsurface ? tovec3(extSubsurface->subsurfaceTransmittanceColor) : Vector3{ 0.5f, 0.5f, 0.5f },
          extSubsurface ? extSubsurface->subsurfaceMeasurementDistance : 0.f,
          extSubsurface ? tovec3(extSubsurface->subsurfaceSingleScatteringAlbedo) : Vector3{ 0.5f, 0.5f, 0.5f },
          extSubsurface ? extSubsurface->subsurfaceVolumetricAnisotropy : 0.f,
          extSubsurface ? static_cast<bool>(extSubsurface->subsurfaceDiffusionProfile) : false,
          extSubsurface ? tovec3(extSubsurface->subsurfaceRadius) : Vector3{ 0.5f, 0.5f, 0.5f },
          extSubsurface ? extSubsurface->subsurfaceRadiusScale : 0.f,
          extSubsurface ? extSubsurface->subsurfaceMaxSampleRadius : 0.f,
          info.filterMode,
          info.wrapModeU,
          info.wrapModeV,
          hasDlssControlMask ? tobool(extOpaque->enableDlssControlMask) : true,
          hasDlssControlMask ? extOpaque->dlssControlMaskIntensity : 1.f,
          hasDlssControlMask ? extOpaque->dlssControlMaskToneStrength : 1.f,
          hasDlssControlMask ? extOpaque->dlssControlMaskStructuralStrength : 1.f,
          0.f,
          0.f,
          Vector2(1.f),
          Vector2(0.f),
          0.f,
        } };
      }
      if (auto extTranslucent = pnext::find<remixapi_MaterialInfoTranslucentEXT>(&info)) {
        return MaterialData { TranslucentMaterialData {
          {},
          {},
          {},
          extTranslucent->refractiveIndex,
          tovec3(extTranslucent->transmittanceColor),
          extTranslucent->transmittanceMeasurementDistance,
          info.emissiveIntensity > 0.f,
          info.emissiveIntensity,
          tovec3(info.emissiveColorConstant),
          info.spriteSheetRow,
          info.spriteSheetCol,
          info.spriteSheetFps,
          tobool(extTranslucent->thinWallThickness_hasvalue),
          extTranslucent->thinWallThickness_hasvalue ? extTranslucent->thinWallThickness_value : 0.001f, // default TranslucentMaterial::ThinWallThickness
          tobool(extTranslucent->useDiffuseLayer),
          !extTranslucent->thinWallThickness_hasvalue &&
            pnext::find<remixapi_MaterialInfoWaterSurfaceEXT>(&info) != nullptr,
          info.filterMode,
          info.wrapModeU,
          info.wrapModeV,
          0.f,
          0.f,
          Vector2(1.f),
          Vector2(0.f),
          0.f,
        } };
      }
      if (auto extPortal = pnext::find<remixapi_MaterialInfoPortalEXT>(&info)) {
        return MaterialData { RayPortalMaterialData {
          {},
          {}, // unused
          extPortal->rayPortalIndex,
          info.spriteSheetRow,
          info.spriteSheetCol,
          info.spriteSheetFps,
          extPortal->rotationSpeed,
          info.emissiveIntensity > 0.f,
          info.emissiveIntensity,
          info.filterMode,
          info.wrapModeU,
          info.wrapModeV,
        } };
      }

      assert(0);
      return MaterialData { OpaqueMaterialData {} };
    }

    // --
    CameraType::Enum toRtCameraType(remixapi_CameraType from) {
      switch (from) {
      case REMIXAPI_CAMERA_TYPE_WORLD: return CameraType::Main;
      case REMIXAPI_CAMERA_TYPE_VIEW_MODEL: return CameraType::ViewModel;
      case REMIXAPI_CAMERA_TYPE_SKY: return CameraType::Sky;
      case REMIXAPI_CAMERA_TYPE_RENDER_TARGET: return CameraType::RenderToTexture;
      default: assert(0); return CameraType::Main;
      }
    }

    struct ExternalCameraInfo {
      CameraType::Enum type {};
      Matrix4 worldToView {};
      Matrix4 viewToProjection {};
    };

    ExternalCameraInfo toRtCamera(const remixapi_CameraInfo& info) {
      if (auto params = pnext::find<remixapi_CameraInfoParameterizedEXT>(&info)) {
        auto result = ExternalCameraInfo {
          toRtCameraType(info.type),
        };
        {
          const auto newViewToWorld = Matrix4d {
           Vector4d{ normalize(tovec3d(params->right)), 0.0 },
           Vector4d{ normalize(tovec3d(params->up)), 0.0 },
           Vector4d{ normalize(tovec3d(params->forward)), 0.0 },
           Vector4d{ tovec3d(params->position), 1.0 },
          };
          result.worldToView = inverse(newViewToWorld);
        }
        {
          constexpr bool isLhs = true;
          auto proj = float4x4 {};
          proj.SetupByHalfFovy(
            DegToRad(params->fovYInDegrees) / 2,
            params->aspect,
            params->nearPlane,
            params->farPlane,
            isLhs ? PROJ_LEFT_HANDED : 0);
          static_assert(sizeof result.viewToProjection == sizeof proj);
          memcpy(&result.viewToProjection, &proj, sizeof float4x4);
        }
        return result;
      }
      return ExternalCameraInfo {
        toRtCameraType(info.type),
        Matrix4 { info.view },
        Matrix4 { info.projection },
      };
    }

    // --

    std::optional<RtLightShaping> toRtLightShaping(const remixapi_LightInfoLightShaping* info) {
      if (info) {
        return RtLightShaping::tryCreate(
          true,
          tovec3(info->direction),
          std::cos(DegToRad(info->coneAngleDegrees)),
          info->coneSoftness,
          info->focusExponent
        );
      }

      // Note: Default constructed Light Shaping returned when no info is provided to have a valid but disabled
      // Light Shaping object (different from returning an empty optional here, which means creation of a Light
      // Shaping failed).
      return RtLightShaping{};
    }

    std::optional<RtLight> toRtLight(const remixapi_LightInfo& info) {
      if (auto src = pnext::find<remixapi_LightInfoUSDEXT>(&info)) {
        if (auto lightData = LightData::tryCreate(*src)) {
          return lightData->toRtLight();
        }
        return {};
      }
      if (auto src = pnext::find<remixapi_LightInfoSphereEXT>(&info)) {
        const auto shaping = toRtLightShaping(src->shaping_hasvalue ? &src->shaping_value : nullptr);

        if (!shaping.has_value()) {
          return {};
        }

        return RtSphereLight::tryCreate(
          tovec3(src->position),
          tovec3(info.radiance),
          src->radius,
          *shaping,
          src->volumetricRadianceScale
        );
      }
      if (auto src = pnext::find<remixapi_LightInfoRectEXT>(&info)) {
        const auto shaping = toRtLightShaping(src->shaping_hasvalue ? &src->shaping_value : nullptr);

        if (!shaping.has_value()) {
          return {};
        }

        return RtRectLight::tryCreate(
          tovec3(src->position),
          Vector2{src->xSize, src->ySize},
          tovec3(src->xAxis),
          tovec3(src->yAxis),
          tovec3(src->direction),
          tovec3(info.radiance),
          *shaping,
          src->volumetricRadianceScale
        );
      }
      if (auto src = pnext::find<remixapi_LightInfoDiskEXT>(&info)) {
        const auto shaping = toRtLightShaping(src->shaping_hasvalue ? &src->shaping_value : nullptr);

        if (!shaping.has_value()) {
          return {};
        }

        return RtDiskLight::tryCreate(
          tovec3(src->position),
          Vector2{src->xRadius, src->yRadius},
          tovec3(src->xAxis),
          tovec3(src->yAxis),
          tovec3(src->direction),
          tovec3(info.radiance),
          *shaping,
          src->volumetricRadianceScale
        );
      }
      if (auto src = pnext::find<remixapi_LightInfoCylinderEXT>(&info)) {
        return RtCylinderLight::tryCreate(
          tovec3(src->position),
          src->radius,
          tovec3(src->axis),
          src->axisLength,
          tovec3(info.radiance),
          src->volumetricRadianceScale
        );
      }
      if (auto src = pnext::find<remixapi_LightInfoDistantEXT>(&info)) {
        return RtDistantLight::tryCreate(
          tovec3(src->direction),
          DegToRad(src->angularDiameterDegrees * 0.5f),
          tovec3(info.radiance),
          src->volumetricRadianceScale
        );
      }

      // Note: Return an empty optional if the LightInfo struct does not contain a supported
      // LightInfo extension struct.
      return {};
    }

    // --

    CameraType::Enum categoryToCameraType(remixapi_InstanceCategoryFlags flags) {
      if (flags & REMIXAPI_INSTANCE_CATEGORY_BIT_VIEW_MODEL) {
        return CameraType::ViewModel;
      }
      if (flags & REMIXAPI_INSTANCE_CATEGORY_BIT_SKY) {
        return CameraType::Sky;
      }
      return CameraType::Main;
    }

    CategoryFlags toRtCategories(remixapi_InstanceCategoryFlags flags) {
      CategoryFlags result { 0 };
      if (flags & REMIXAPI_INSTANCE_CATEGORY_BIT_WORLD_UI                 ){ result.set(InstanceCategories::WorldUI               ); }
      if (flags & REMIXAPI_INSTANCE_CATEGORY_BIT_WORLD_MATTE              ){ result.set(InstanceCategories::WorldMatte            ); }
      if (flags & REMIXAPI_INSTANCE_CATEGORY_BIT_SKY                      ){ result.set(InstanceCategories::Sky                   ); }
      if (flags & REMIXAPI_INSTANCE_CATEGORY_BIT_IGNORE                   ){ result.set(InstanceCategories::Ignore                ); }
      if (flags & REMIXAPI_INSTANCE_CATEGORY_BIT_IGNORE_LIGHTS            ){ result.set(InstanceCategories::IgnoreLights          ); }
      if (flags & REMIXAPI_INSTANCE_CATEGORY_BIT_IGNORE_ANTI_CULLING      ){ result.set(InstanceCategories::IgnoreAntiCulling     ); }
      if (flags & REMIXAPI_INSTANCE_CATEGORY_BIT_IGNORE_MOTION_BLUR       ){ result.set(InstanceCategories::IgnoreMotionBlur      ); }
      if (flags & REMIXAPI_INSTANCE_CATEGORY_BIT_IGNORE_OPACITY_MICROMAP  ){ result.set(InstanceCategories::IgnoreOpacityMicromap ); }
      if (flags & REMIXAPI_INSTANCE_CATEGORY_BIT_LIVE_CAMERA              ){ result.set(InstanceCategories::IgnoreOpacityMicromap ); }
      if (flags & REMIXAPI_INSTANCE_CATEGORY_BIT_IGNORE_ALPHA_CHANNEL     ){ result.set(InstanceCategories::IgnoreAlphaChannel    ); }
      if (flags & REMIXAPI_INSTANCE_CATEGORY_BIT_HIDDEN                   ){ result.set(InstanceCategories::Hidden                ); }
      if (flags & REMIXAPI_INSTANCE_CATEGORY_BIT_PARTICLE                 ){ result.set(InstanceCategories::Particle              ); }
      if (flags & REMIXAPI_INSTANCE_CATEGORY_BIT_BEAM                     ){ result.set(InstanceCategories::Beam                  ); }
      if (flags & REMIXAPI_INSTANCE_CATEGORY_BIT_DECAL_STATIC             ){ result.set(InstanceCategories::DecalStatic           ); }
      if (flags & REMIXAPI_INSTANCE_CATEGORY_BIT_DECAL_DYNAMIC            ){ result.set(InstanceCategories::DecalDynamic          ); }
      if (flags & REMIXAPI_INSTANCE_CATEGORY_BIT_DECAL_SINGLE_OFFSET      ){ result.set(InstanceCategories::DecalSingleOffset     ); }
      if (flags & REMIXAPI_INSTANCE_CATEGORY_BIT_DECAL_NO_OFFSET          ){ result.set(InstanceCategories::DecalNoOffset         ); }
      if (flags & REMIXAPI_INSTANCE_CATEGORY_BIT_ALPHA_BLEND_TO_CUTOUT    ){ result.set(InstanceCategories::AlphaBlendToCutout    ); }
      if (flags & REMIXAPI_INSTANCE_CATEGORY_BIT_TERRAIN                  ){ result.set(InstanceCategories::Terrain               ); }
      if (flags & REMIXAPI_INSTANCE_CATEGORY_BIT_ANIMATED_WATER           ){ result.set(InstanceCategories::AnimatedWater         ); }
      if (flags & REMIXAPI_INSTANCE_CATEGORY_BIT_THIRD_PERSON_PLAYER_MODEL){ result.set(InstanceCategories::ThirdPersonPlayerModel); }
      if (flags & REMIXAPI_INSTANCE_CATEGORY_BIT_THIRD_PERSON_PLAYER_BODY ){ result.set(InstanceCategories::ThirdPersonPlayerBody ); }
      if (flags & REMIXAPI_INSTANCE_CATEGORY_BIT_IGNORE_BAKED_LIGHTING    ){ result.set(InstanceCategories::IgnoreBakedLighting   ); }
      if (flags & REMIXAPI_INSTANCE_CATEGORY_BIT_PARTICLE_EMITTER)         { result.set(InstanceCategories::ParticleEmitter); }
      if (flags & REMIXAPI_INSTANCE_CATEGORY_BIT_SMOOTH_NORMALS)            { result.set(InstanceCategories::SmoothNormals); }
      if (flags & REMIXAPI_INSTANCE_CATEGORY_BIT_HAIR_CARDS)                { result.set(InstanceCategories::HairCards); }
      
      static_assert((int)InstanceCategories::Count == 25, "Instance categories changed, please update Remix SDK");
      return result;
    }

    RtxParticleSystemDesc toRtParticleDesc(const remixapi_InstanceInfoParticleSystemLegacyEXT& info) {
      RtxParticleSystemDesc desc {};

      // Lifetimes
      desc.minTimeToLive = info.minTimeToLive;
      desc.maxTimeToLive = info.maxTimeToLive;

      // Initial 
      desc.spawnRatePerSecond = info.spawnRatePerSecond;
      desc.initialVelocityFromMotion = info.initialVelocityFromMotion;
      desc.initialVelocityFromNormal = info.initialVelocityFromNormal;
      desc.initialVelocityConeAngleDegrees = info.initialVelocityConeAngleDegrees;
      desc.gravityForce = info.gravityForce;
      desc.motionTrailMultiplier = info.motionTrailMultiplier;

      // Turbulence
      desc.turbulenceFrequency = info.turbulenceFrequency;
      desc.turbulenceForce = info.turbulenceForce;

      // Convert spawn/target pairs to 2-element animated vectors
      // Color: spawn at index 0, target at index 1
      desc.minColor = { vec4(info.minSpawnColor.x, info.minSpawnColor.y, info.minSpawnColor.z, info.minSpawnColor.w), 
                        vec4(info.minTargetColor.x, info.minTargetColor.y, info.minTargetColor.z, info.minTargetColor.w) };
      desc.maxColor = { vec4(info.maxSpawnColor.x, info.maxSpawnColor.y, info.maxSpawnColor.z, info.maxSpawnColor.w), 
                        vec4(info.maxTargetColor.x, info.maxTargetColor.y, info.maxTargetColor.z, info.maxTargetColor.w) };

      // Size: spawn at index 0, target at index 1
      desc.minSize = { vec2(info.minSpawnSize, info.minSpawnSize), vec2(info.minTargetSize, info.minTargetSize) };
      desc.maxSize = { vec2(info.maxSpawnSize, info.maxSpawnSize), vec2(info.maxTargetSize, info.maxTargetSize) };

      // Rotation speed: spawn at index 0, target at index 1
      desc.minRotationSpeed = { info.minSpawnRotationSpeed, info.minTargetRotationSpeed };
      desc.maxRotationSpeed = { info.maxSpawnRotationSpeed, info.maxTargetRotationSpeed };

      // Velocity: spawn at index 0, target at index 1
      desc.maxVelocity = { vec3(info.maxSpeed, info.maxSpeed, info.maxSpeed), vec3(info.maxSpeed, info.maxSpeed, info.maxSpeed) };

      // Collision
      desc.collisionThickness = info.collisionThickness;
      desc.collisionRestitution = info.collisionRestitution;
      desc.collisionMode = ParticleCollisionMode::Bounce;

      // Counts/flags
      desc.maxNumParticles = info.maxNumParticles;
      desc.useTurbulence = static_cast<uint8_t>(info.useTurbulence);
      desc.alignParticlesToVelocity = static_cast<uint8_t>(info.alignParticlesToVelocity);
      desc.useSpawnTexcoords = static_cast<uint8_t>(info.useSpawnTexcoords);
      desc.enableCollisionDetection = static_cast<uint8_t>(info.enableCollisionDetection);
      desc.enableMotionTrail = static_cast<uint8_t>(info.enableMotionTrail);
      desc.hideEmitter = static_cast<uint8_t>(info.hideEmitter);

      // Types/modes
      desc.billboardType = static_cast<ParticleBillboardType>(info.billboardType);
      desc.spriteSheetMode = ParticleSpriteSheetMode::UseMaterialSpriteSheet;
      desc.randomFlipAxis = ParticleRandomFlipAxis::None;

      return desc;
    }

    RtxParticleSystemDesc toRtParticleDesc(const remixapi_InstanceInfoParticleSystemEXT& info) {
      RtxParticleSystemDesc desc;
      desc.maxNumParticles = info.maxNumParticles;

      // Animated float conversions
      desc.minColor = toAnimatedFloat4D(info.minColor);
      desc.maxColor = toAnimatedFloat4D(info.maxColor);
      desc.minRotationSpeed = toAnimatedFloat1D(info.minRotationSpeed);
      desc.maxRotationSpeed = toAnimatedFloat1D(info.maxRotationSpeed);
      desc.minSize = toAnimatedFloat2D(info.minSize);
      desc.maxSize = toAnimatedFloat2D(info.maxSize);
      desc.maxVelocity = toAnimatedFloat3D(info.maxVelocity);

      desc.minTimeToLive = info.minTimeToLive;
      desc.maxTimeToLive = info.maxTimeToLive;
      desc.initialVelocityFromNormal = info.initialVelocityFromNormal;
      desc.initialVelocityConeAngleDegrees = info.initialVelocityConeAngleDegrees;
      desc.initialVelocityFromMotion = info.initialVelocityFromMotion;
      desc.turbulenceFrequency = info.turbulenceFrequency;
      desc.turbulenceForce = info.turbulenceForce;
      desc.motionTrailMultiplier = info.motionTrailMultiplier;
      desc.spawnRatePerSecond = info.spawnRatePerSecond;
      desc.collisionThickness = info.collisionThickness;
      desc.collisionRestitution = info.collisionRestitution;
      desc.gravityForce = info.gravityForce;
      desc.billboardType = static_cast<ParticleBillboardType>(info.billboardType);
      desc.hideEmitter = static_cast<uint8_t>(info.hideEmitter);
      desc.enableMotionTrail = static_cast<uint8_t>(info.enableMotionTrail);
      desc.useTurbulence = static_cast<uint8_t>(info.useTurbulence);
      desc.alignParticlesToVelocity = static_cast<uint8_t>(info.alignParticlesToVelocity);
      desc.useSpawnTexcoords = static_cast<uint8_t>(info.useSpawnTexcoords);
      desc.enableCollisionDetection = static_cast<uint8_t>(info.enableCollisionDetection);
      desc.dragCoefficient = info.dragCoefficient;
      desc.initialRotationDeviationDegrees = info.initialRotationDeviationDegrees;
      desc.spawnBurstDuration = info.spawnBurstDuration;
      desc.attractorRadius = info.attractorRadius;
      desc.attractorPosition = tovec3(info.attractorPosition);
      desc.attractorForce = info.attractorForce;
      desc.spriteSheetMode = static_cast<ParticleSpriteSheetMode>(info.spriteSheetMode);
      desc.collisionMode = static_cast<ParticleCollisionMode>(info.collisionMode);
      desc.randomFlipAxis = static_cast<ParticleRandomFlipAxis>(info.randomFlipAxis);
      desc.restrictVelocityX = static_cast<uint8_t>(info.restrictVelocityX);
      desc.restrictVelocityY = static_cast<uint8_t>(info.restrictVelocityY);
      desc.restrictVelocityZ = static_cast<uint8_t>(info.restrictVelocityZ);

      return desc;
    }

    std::unique_ptr<dxvk::ExternalDrawState> toRtDrawState(const remixapi_InstanceInfo& info) {
      return RemixAPIPrivateAccessor::toRtDrawState(info);
    }
  }
}

std::unique_ptr<dxvk::ExternalDrawState> dxvk::RemixAPIPrivateAccessor::toRtDrawState(const remixapi_InstanceInfo& info)
{
  auto state = std::make_unique<dxvk::ExternalDrawState>();
  const CameraType::Enum cameraType = convert::categoryToCameraType(info.categoryFlags);

  auto& prototype = state->drawCall;
  {
    prototype.cameraType = cameraType;
    prototype.transformData.objectToWorld = convert::tomat4(info.transform);
    prototype.transformData.texgenMode = TexGenMode::None;
    prototype.categories = convert::toRtCategories(info.categoryFlags);
    if (info.categoryFlags & REMIXAPI_INSTANCE_CATEGORY_BIT_LIVE_CAMERA) {
      prototype.isUsingRaytracedRenderTarget = true;
    }
  }

  if (auto objectPicking = pnext::find<remixapi_InstanceInfoObjectPickingEXT>(&info)) {
    prototype.drawCallID = objectPicking->objectPickingValue;
  }

  if (auto coverage = pnext::find<remixapi_InstanceInfoVoxelCoverageEXT>(&info)) {
    if (coverage->level > 255 || coverage->nodeIndex >= (1u << 24)) throw dxvk::DxvkError("Voxel coverage level exceeds R8 range");
    prototype.transformData.voxelCoverageControl = coverage->level | (coverage->nodeIndex << 8);
  }

  if (auto extBones = pnext::find<remixapi_InstanceInfoBoneTransformsEXT>(&info)) {
    const uint32_t boneCount =
      extBones->boneTransforms_count < REMIXAPI_INSTANCE_INFO_MAX_BONES_COUNT ?
      extBones->boneTransforms_count : REMIXAPI_INSTANCE_INFO_MAX_BONES_COUNT;
    prototype.skinningData.minBoneIndex = 0;
    prototype.skinningData.numBones = boneCount;
    prototype.skinningData.numBonesPerVertex = prototype.getGeometryData().numBonesPerVertex;
    prototype.skinningData.pBoneMatrices.resize(boneCount);
    for (uint32_t boneIdx = 0; boneIdx < boneCount; boneIdx++) {
      prototype.skinningData.pBoneMatrices[boneIdx] = convert::tomat4(extBones->boneTransforms_values[boneIdx]);
    }
    prototype.skinningData.computeHash();
  }

  if (auto extBlend = pnext::find<remixapi_InstanceInfoBlendEXT>(&info)) {
    prototype.materialData.alphaTestEnabled = extBlend->alphaTestEnabled;
    prototype.materialData.alphaTestReferenceValue = extBlend->alphaTestReferenceValue;
    prototype.materialData.alphaTestCompareOp = (VkCompareOp) extBlend->alphaTestCompareOp;
    prototype.materialData.blendMode.enableBlending = extBlend->alphaBlendEnabled;
    prototype.materialData.textureColorOperation = (DxvkRtTextureOperation) extBlend->textureColorOperation;
    prototype.materialData.textureColorArg1Source = (RtTextureArgSource) extBlend->textureColorArg1Source;
    prototype.materialData.textureColorArg2Source = (RtTextureArgSource) extBlend->textureColorArg2Source;
    prototype.materialData.textureAlphaOperation = (DxvkRtTextureOperation) extBlend->textureAlphaOperation;
    prototype.materialData.textureAlphaArg1Source = (RtTextureArgSource) extBlend->textureAlphaArg1Source;
    prototype.materialData.textureAlphaArg2Source = (RtTextureArgSource) extBlend->textureAlphaArg2Source;
    prototype.materialData.tFactor = extBlend->tFactor;
    prototype.materialData.isTextureFactorBlend = extBlend->isTextureFactorBlend;
    prototype.materialData.isVertexColorBakedLighting = (s_apiVersion >= REMIXAPI_VERSION_MAKE(0, 5, 2)) ? extBlend->isVertexColorBakedLighting : RtxOptions::vertexColorIsBakedLighting();
    prototype.materialData.blendMode.colorSrcFactor = (VkBlendFactor) extBlend->srcColorBlendFactor;
    prototype.materialData.blendMode.colorDstFactor = (VkBlendFactor) extBlend->dstColorBlendFactor;
    prototype.materialData.blendMode.colorBlendOp = (VkBlendOp) extBlend->colorBlendOp;
    prototype.materialData.blendMode.alphaSrcFactor = (VkBlendFactor) extBlend->srcAlphaBlendFactor;
    prototype.materialData.blendMode.alphaDstFactor = (VkBlendFactor) extBlend->dstAlphaBlendFactor;
    prototype.materialData.blendMode.alphaBlendOp = (VkBlendOp) extBlend->alphaBlendOp;
    prototype.materialData.blendMode.writeMask = (VkColorComponentFlags) extBlend->writeMask;
  }

  if (auto extParticles = pnext::find<remixapi_InstanceInfoParticleSystemEXT>(&info)) {
    state->optionalParticleDesc.emplace(convert::toRtParticleDesc(*extParticles));
  }
  if (auto extParticles = pnext::find<remixapi_InstanceInfoParticleSystemLegacyEXT>(&info)) {
    state->optionalParticleDesc.emplace(convert::toRtParticleDesc(*extParticles));
  }

  if (auto extInstancing = pnext::find<remixapi_InstanceInfoGpuInstancingEXT>(&info)) {
    if (extInstancing->instanceTransforms_count > 0 && extInstancing->instanceTransforms_values) {
      auto& gpuInstancingTransforms = state->gpuInstancingTransforms;
      gpuInstancingTransforms.reserve(extInstancing->instanceTransforms_count);
      for (uint32_t i = 0; i < extInstancing->instanceTransforms_count; ++i) {
        gpuInstancingTransforms.push_back(convert::tomat4(extInstancing->instanceTransforms_values[i]));
      }
    }
  }

  state->mesh = info.mesh;
  state->cameraType = cameraType;
  state->categories = convert::toRtCategories(info.categoryFlags);
  state->doubleSided = convert::tobool(info.doubleSided);

  return state;
}

namespace {
  remixapi_ErrorCode REMIXAPI_CALL remixapi_SetVoxelCoverage(const remixapi_VoxelCoverageInfo* info) {
    ScopedCpuProfileZoneN("Remix API SetVoxelCoverage");
    auto* device = tryAsDxvk();
    if (!device) return REMIXAPI_ERROR_CODE_REMIX_DEVICE_WAS_NOT_REGISTERED;
    if (!info || info->sType != REMIXAPI_STRUCT_TYPE_VOXEL_COVERAGE_INFO || info->pNext) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }
    constexpr uint64_t maxCells = 32u * 1024u * 1024u;
    const bool clear = info->sizeX == 0 && info->sizeY == 0 && info->sizeZ == 0;
    const uint64_t xy = uint64_t(info->sizeX) * info->sizeY;
    if ((!clear && (!info->sizeX || !info->sizeY || !info->sizeZ || xy > maxCells ||
        info->sizeZ > maxCells / xy || !info->cellSize || (info->cellSize & (info->cellSize - 1)) ||
        !info->levels || info->dataSize != xy * info->sizeZ)) || (clear && info->dataSize != 0)) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }
    constexpr uint32_t maxNodes = 1u << 20;
    if ((info->flags & ~1u) || info->treeNodeCount > maxNodes ||
        (info->treeNodeCount && !info->treeNodes)) return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    static_assert(sizeof(remixapi_VoxelCoverageNode) == 64);
    for (uint32_t i = 1; i < info->treeNodeCount; ++i) {
      const auto& node = info->treeNodes[i];
      if (!node.sizeBlocks) {
        const remixapi_VoxelCoverageNode empty {};
        if (std::memcmp(&node, &empty, sizeof(node)) != 0) return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
        continue;
      }
      if ((node.sizeBlocks & (node.sizeBlocks - 1)) ||
          (node.flags & ~0xff03u) || node.reserved[0] || node.reserved[1] || node.reserved[2])
        return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
      for (uint32_t octant = 0; octant < 8; ++octant) {
        const uint32_t childIndex = node.children[octant];
        if (!childIndex) continue;
        if (childIndex >= info->treeNodeCount || node.sizeBlocks < 2)
          return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
        const auto& child = info->treeNodes[childIndex];
        const int64_t half = node.sizeBlocks / 2;
        if (child.sizeBlocks != half ||
            int64_t(child.originX) != int64_t(node.originX) + ((octant & 1) ? half : 0) ||
            int64_t(child.originY) != int64_t(node.originY) + ((octant & 2) ? half : 0) ||
            int64_t(child.originZ) != int64_t(node.originZ) + ((octant & 4) ? half : 0))
          return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
      }
    }
    const uint32_t treeOffset = uint32_t(16 + (info->dataSize + 3) / 4);
    std::vector<uint32_t> data(treeOffset + uint64_t(info->treeNodeCount) * 16, 0);
    data[8] = treeOffset; data[9] = info->treeNodeCount; data[10] = 16;
    if (info->treeNodeCount) std::memcpy(data.data() + treeOffset, info->treeNodes,
      uint64_t(info->treeNodeCount) * sizeof(remixapi_VoxelCoverageNode));
    if (!clear) {
      data[0] = uint32_t(info->originX); data[1] = uint32_t(info->originY); data[2] = uint32_t(info->originZ);
      data[3] = info->cellSize;
      data[4] = info->sizeX; data[5] = info->sizeY; data[6] = info->sizeZ; data[7] = 1 | (info->flags << 1);
      std::memcpy(data.data() + 16, info->levels, info->dataSize);
    }
    device->EmitCs([data = std::move(data)](dxvk::DxvkContext* ctx) {
      ctx->getCommonObjects()->getSceneManager().setVoxelCoverage(dxvk::Rc<dxvk::DxvkContext>(ctx), data);
    });
    return REMIXAPI_ERROR_CODE_SUCCESS;
  }

  remixapi_ErrorCode REMIXAPI_CALL remixapi_CreateMaterial(
    const remixapi_MaterialInfo* info,
    remixapi_MaterialHandle* out_handle) {
    dxvk::D3D9DeviceEx* remixDevice = tryAsDxvk();
    if (!remixDevice) {
      return REMIXAPI_ERROR_CODE_REMIX_DEVICE_WAS_NOT_REGISTERED;
    }
    if (!out_handle || !info || info->sType != REMIXAPI_STRUCT_TYPE_MATERIAL_INFO) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }
    static std::atomic<uint64_t> nextMaterial { 0 };
    auto handle = reinterpret_cast<remixapi_MaterialHandle>(++nextMaterial);

    // async load
    std::lock_guard lock { s_mutex };
    remixDevice->EmitCs([cHandle = handle,
                         cMaterialData = convert::toRtMaterialWithoutTexturePreload(*info),
                         cPreloadSrc = convert::makePreloadSource(*info)](dxvk::DxvkContext* ctx) {
      auto& assets = ctx->getCommonObjects()->getSceneManager().getAssetReplacer();
      assets->makeMaterialWithTexturePreload(
        *ctx,
        cHandle,
        convert::toRtMaterialFinalized(*ctx, cMaterialData, cPreloadSrc));
    });

    *out_handle = handle;
    return REMIXAPI_ERROR_CODE_SUCCESS;
  }

  remixapi_ErrorCode REMIXAPI_CALL remixapi_DestroyMaterial(
    remixapi_MaterialHandle handle) {
    if (auto remixDevice = tryAsDxvk()) {
      std::lock_guard lock { s_mutex };
      remixDevice->EmitCs([cHandle = handle](dxvk::DxvkContext* ctx) {
        auto& assets = ctx->getCommonObjects()->getSceneManager().getAssetReplacer();
        assets->destroyExternalMaterial(cHandle);
      });
      return REMIXAPI_ERROR_CODE_SUCCESS;
    }
    return REMIXAPI_ERROR_CODE_REMIX_DEVICE_WAS_NOT_REGISTERED;
  }

  dxvk::RasterGeometry makeSurfaceGeometry(
    const remixapi_MeshInfoSurfaceTriangles& src,
    const dxvk::DxvkBufferSlice& vertexSlice,
    const dxvk::DxvkBufferSlice& indexSlice,
    const dxvk::DxvkBufferSlice& blendWeightsSlice,
    const dxvk::DxvkBufferSlice& blendIndicesSlice,
    uint32_t vertexStride,
    VkIndexType indexType = VK_INDEX_TYPE_UINT32) {
    auto dst = dxvk::RasterGeometry {};
    dst.externalMaterial = src.material;
    dst.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    dst.cullMode = VK_CULL_MODE_NONE; // this will be overwritten by the instance info at draw time
    dst.frontFace = VK_FRONT_FACE_CLOCKWISE;
    dst.vertexCount = src.vertices_count;
    dst.immutablePositions = !src.skinning_hasvalue;
    if (src.flags & REMIXAPI_MESH_INFO_SURFACE_TRIANGLES_BIT_PREVIOUS_POSITIONS) {
      dst.previousPositionOffset = offsetof(remixapi_HardcodedVertex, previousPosition);
    }
    assert(src.vertices_count < std::numeric_limits<uint32_t>::max());
    dst.positionBuffer = dxvk::RasterBuffer { vertexSlice, offsetof(remixapi_HardcodedVertex, position), vertexStride, VK_FORMAT_R32G32B32_SFLOAT };
    dst.normalBuffer = dxvk::RasterBuffer { vertexSlice, offsetof(remixapi_HardcodedVertex, normal), vertexStride, VK_FORMAT_R32G32B32_SFLOAT };
    dst.texcoordBuffer = dxvk::RasterBuffer { vertexSlice, offsetof(remixapi_HardcodedVertex, texcoord), vertexStride, VK_FORMAT_R32G32_SFLOAT };
    if (src.flags & REMIXAPI_MESH_INFO_SURFACE_TRIANGLES_BIT_TEXCOORD_BOUNDS) {
      dst.texcoordBoundsBuffer = dxvk::RasterBuffer { vertexSlice, offsetof(remixapi_HardcodedVertex, texcoordBounds),
        vertexStride, VK_FORMAT_R16G16B16A16_SFLOAT };
    }
    dst.color0Buffer = dxvk::RasterBuffer { vertexSlice, offsetof(remixapi_HardcodedVertex, color), vertexStride, VK_FORMAT_B8G8R8A8_UNORM };
    if (src.skinning_hasvalue) {
      dst.numBonesPerVertex = src.skinning_value.bonesPerVertex;
      // Skinning advances by the stride per vertex, so it spans the whole tuple of each vertex.
      const uint32_t weightStride = src.skinning_value.bonesPerVertex * sizeof(float);
      const uint32_t indexStride = dxvk::divCeil(src.skinning_value.bonesPerVertex, 4u) * sizeof(uint32_t);
      dst.blendWeightBuffer = dxvk::RasterBuffer { blendWeightsSlice, 0, weightStride, VK_FORMAT_R32_SFLOAT };
      dst.blendIndicesBuffer = dxvk::RasterBuffer { blendIndicesSlice, 0, indexStride, VK_FORMAT_R8G8B8A8_USCALED };
    }

    dst.indexCount = src.indices_count;
    static_assert(sizeof(src.indices_values[0]) == 4);
    const uint32_t indexStride = indexType == VK_INDEX_TYPE_UINT16 ? sizeof(uint16_t) : sizeof(uint32_t);
    dst.indexBuffer = dxvk::RasterBuffer { indexSlice, 0, indexStride, indexType };
    // look comments in UsdMod::Impl::processMesh, rtx_mod_usd.cpp
    dst.hashes[dxvk::HashComponents::Indices] = dst.hashes[dxvk::HashComponents::VertexPosition] = hack_getNextGeomHash();
    dst.hashes[dxvk::HashComponents::VertexTexcoord] = hack_getNextGeomHash();
    dst.hashes[dxvk::HashComponents::GeometryDescriptor] = hack_getNextGeomHash();
    dst.hashes[dxvk::HashComponents::VertexLayout] = hack_getNextGeomHash();
    dst.hashes.precombine();
    return dst;
  }

  // Allocates GPU buffers for a single surface and builds its RasterGeometry.
  dxvk::RasterGeometry allocateSurfaceGeometry(
    dxvk::D3D9DeviceEx* device,
    const remixapi_MeshInfoSurfaceTriangles& src) {
    ScopedCpuProfileZone();

    auto allocBuffer = [device](size_t bytes) -> dxvk::Rc<dxvk::DxvkBuffer> {
      if (bytes == 0) {
        return {};
      }
      auto bufferInfo = dxvk::DxvkBufferCreateInfo {};
      bufferInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
      bufferInfo.stages = VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;
      bufferInfo.access = VK_ACCESS_TRANSFER_WRITE_BIT;
      bufferInfo.size = dxvk::align(bytes, dxvk::CACHE_LINE_SIZE);
      return device->GetDXVKDevice()->createBuffer(
        bufferInfo,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_CACHED_BIT,
        dxvk::DxvkMemoryStats::Category::RTXBuffer,
        "Remix API mesh buffer");
    };

    constexpr size_t kAttributeStride = REMIXAPI_HARDCODED_VERTEX_STORAGE_STRIDE;
    constexpr size_t kMotionStride = offsetof(remixapi_HardcodedVertex, _pad3);
    static_assert(kAttributeStride == offsetof(remixapi_HardcodedVertex, previousPosition));
    static_assert(sizeof(remixapi_HardcodedVertex) == 64);
    const bool explicitMotion = src.flags & REMIXAPI_MESH_INFO_SURFACE_TRIANGLES_BIT_PREVIOUS_POSITIONS;
    const size_t vertexStride = explicitMotion ? kMotionStride : kAttributeStride;
    const size_t vertexDataSize = vertexStride * src.vertices_count;
    const auto quadPattern = dxvk::findQuadIndexPattern(src.indices_values, src.indices_count, src.vertices_count);
    const uint32_t indexStride = quadPattern != dxvk::QuadIndexPattern::None ? sizeof(uint16_t)
      : dxvk::remixapi_geometry::indexStride(src.indices_values, src.indices_count, src.vertices_count);
    const size_t indexDataSize = src.indices_count * indexStride;

    dxvk::Rc<dxvk::DxvkBuffer> vertexBuffer = allocBuffer(vertexDataSize);
    dxvk::Rc<dxvk::DxvkBuffer> indexBuffer = quadPattern == dxvk::QuadIndexPattern::None ? allocBuffer(indexDataSize)
      : device->GetDXVKDevice()->getCommon()->metaGeometryUtils().getQuadIndexSource(quadPattern);
    dxvk::Rc<dxvk::DxvkBuffer> skinningBuffer = nullptr;

    auto vertexSlice = dxvk::DxvkBufferSlice { vertexBuffer };
    const size_t inputStride = (src.flags & REMIXAPI_MESH_INFO_SURFACE_TRIANGLES_BIT_COMPACT_VERTICES)
      ? kAttributeStride : sizeof(remixapi_HardcodedVertex);
    if (explicitMotion) {
      dxvk::remixapi_geometry::copyVertices<kMotionStride>(vertexSlice.mapPtr(0), src.vertices_values, src.vertices_count, inputStride);
    } else {
      dxvk::remixapi_geometry::copyVertices<kAttributeStride>(vertexSlice.mapPtr(0), src.vertices_values, src.vertices_count, inputStride);
    }

    auto indexSlice = dxvk::DxvkBufferSlice {};
    if (indexDataSize > 0) {
      indexSlice = dxvk::DxvkBufferSlice { indexBuffer };
      if (quadPattern == dxvk::QuadIndexPattern::None) {
        dxvk::remixapi_geometry::copyIndices(indexSlice.mapPtr(0), src.indices_values, src.indices_count, indexStride);
      }
    }

    auto blendWeightsSlice = dxvk::DxvkBufferSlice {};
    auto blendIndicesSlice = dxvk::DxvkBufferSlice {};
    if (src.skinning_hasvalue) {
      const size_t wordsPerTuple = dxvk::divCeil(src.skinning_value.bonesPerVertex, 4u);
      const size_t weightBytes = sizeInBytes(src.skinning_value.blendWeights_values, src.skinning_value.blendWeights_count);
      const size_t indexBytes = src.vertices_count * wordsPerTuple * sizeof(uint32_t);

      skinningBuffer = allocBuffer(weightBytes + indexBytes);

      // Pack four 8-bit bone indices per uint32
      std::vector<uint32_t> compressed(src.vertices_count * wordsPerTuple);
      for (size_t vert = 0; vert < src.vertices_count; vert++) {
        const uint32_t* indices = &src.skinning_value.blendIndices_values[vert * src.skinning_value.bonesPerVertex];
        for (int j = 0; j < src.skinning_value.bonesPerVertex; j += 4) {
          uint32_t packed = 0;
          for (int k = 0; k < 4 && j + k < src.skinning_value.bonesPerVertex; ++k) {
            packed |= indices[j + k] << (8 * k);
          }
          compressed[vert * wordsPerTuple + j / 4] = packed;
        }
      }

      blendWeightsSlice = dxvk::DxvkBufferSlice { skinningBuffer, 0, weightBytes };
      blendIndicesSlice = dxvk::DxvkBufferSlice { skinningBuffer, weightBytes, indexBytes };

      memcpy(blendWeightsSlice.mapPtr(0), src.skinning_value.blendWeights_values, weightBytes);
      memcpy(blendIndicesSlice.mapPtr(0), compressed.data(), indexBytes);
    }

    const auto indexType = indexStride == sizeof(uint16_t) ? VK_INDEX_TYPE_UINT16 : VK_INDEX_TYPE_UINT32;
    auto geometry = makeSurfaceGeometry(src, vertexSlice, indexSlice, blendWeightsSlice, blendIndicesSlice, vertexStride, indexType);
    geometry.sharedQuadIndexPattern = quadPattern;
    return geometry;
  }

  #include "rtx_remix_shared_buffers.inl"
  #include "rtx_remix_quads.inl"

  remixapi_ErrorCode REMIXAPI_CALL remixapi_CreateMesh(
    const remixapi_MeshInfo* info,
    remixapi_MeshHandle* out_handle) {
    dxvk::D3D9DeviceEx* remixDevice = tryAsDxvk();
    if (!remixDevice) {
      return REMIXAPI_ERROR_CODE_REMIX_DEVICE_WAS_NOT_REGISTERED;
    }
    if (!out_handle || !info || info->sType != REMIXAPI_STRUCT_TYPE_MESH_INFO) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }
    static std::atomic<uint64_t> nextMesh { 0 };
    auto handle = reinterpret_cast<remixapi_MeshHandle>(++nextMesh);

    auto allocatedSurfaces = std::vector<dxvk::RasterGeometry> {};
    if (auto* quads = pnext::find<remixapi_MeshInfoQuadsEXT>(info)) {
      if (info->surfaces_count || info->surfaces_values || !quads->surfaces || !quads->surfaceCount
          || quads->surfaceCount > 65536 || pnext::find<remixapi_MeshInfoSharedBuffersEXT>(info)) {
        return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
      }
      for (uint32_t i = 0; i < quads->surfaceCount; i++) {
        if (!validQuadSurface(quads->surfaces[i])) {
          return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
        }
      }
      allocatedSurfaces.reserve(quads->surfaceCount);
      for (uint32_t i = 0; i < quads->surfaceCount; i++) {
        allocatedSurfaces.push_back(allocateQuadSurfaceGeometry(remixDevice, quads->surfaces[i]));
      }
    } else if (auto* shared = pnext::find<remixapi_MeshInfoSharedBuffersEXT>(info)) {
      if (info->surfaces_count || info->surfaces_values || !shared->surfaces || !shared->surfaceCount || shared->surfaceCount > 65536) {
        return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
      }
      allocatedSurfaces.reserve(shared->surfaceCount);
      for (uint32_t i = 0; i < shared->surfaceCount; i++) {
        allocatedSurfaces.push_back(allocateSharedSurfaceGeometry(shared->surfaces[i]));
      }
    } else {
      if (!info->surfaces_values || !info->surfaces_count) {
        return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
      }
      for (size_t i = 0; i < info->surfaces_count; i++) {
        const auto& surface = info->surfaces_values[i];
        if ((surface.flags & REMIXAPI_MESH_INFO_SURFACE_TRIANGLES_BIT_PREVIOUS_POSITIONS)
            && ((surface.flags & REMIXAPI_MESH_INFO_SURFACE_TRIANGLES_BIT_COMPACT_VERTICES) || surface.skinning_hasvalue)) {
          return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
        }
      }
      allocatedSurfaces.reserve(info->surfaces_count);
      for (size_t i = 0; i < info->surfaces_count; i++) {
        allocatedSurfaces.push_back(allocateSurfaceGeometry(remixDevice, info->surfaces_values[i]));
      }
    }
    std::lock_guard lock { s_mutex };

    remixDevice->EmitCs([cHandle = handle, cSurfaces = std::move(allocatedSurfaces)](dxvk::DxvkContext* ctx) mutable {
      for (auto& surface : cSurfaces) {
        if (surface.quadAttributes) {
          uploadQuadSurfaceGeometry(ctx, surface);
        }
      }
      auto& assets = ctx->getCommonObjects()->getSceneManager().getAssetReplacer();
      assets->registerExternalMesh(cHandle, std::move(cSurfaces));
    });

    *out_handle = handle;
    return REMIXAPI_ERROR_CODE_SUCCESS;
  }

  remixapi_ErrorCode REMIXAPI_CALL remixapi_DestroyMesh(
    remixapi_MeshHandle handle) {
    ScopedCpuProfileZoneN("Remix API DestroyMesh");
    dxvk::D3D9DeviceEx* remixDevice = tryAsDxvk();
    if (!remixDevice) {
      return REMIXAPI_ERROR_CODE_REMIX_DEVICE_WAS_NOT_REGISTERED;
    }
    std::lock_guard lock { s_mutex };
    remixDevice->EmitCs([cHandle = handle](dxvk::DxvkContext* ctx) {
      removeRetainedSceneMesh(ctx, cHandle);
      ctx->getCommonObjects()->getSceneManager()
        .destroyExternalMesh(dxvk::Rc<dxvk::DxvkContext>(ctx), cHandle);
    });
    return REMIXAPI_ERROR_CODE_SUCCESS;
  }

  remixapi_ErrorCode REMIXAPI_CALL remixapi_SetupCamera(
    const remixapi_CameraInfo* info) {
    dxvk::D3D9DeviceEx* remixDevice = tryAsDxvk();
    if (!remixDevice) {
      return REMIXAPI_ERROR_CODE_REMIX_DEVICE_WAS_NOT_REGISTERED;
    }
    if (!info || info->sType != REMIXAPI_STRUCT_TYPE_CAMERA_INFO ||
        info->type < REMIXAPI_CAMERA_TYPE_WORLD || info->type > REMIXAPI_CAMERA_TYPE_RENDER_TARGET) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }
    std::lock_guard lock { s_mutex };
    // ensure that near plane is not modified, to keep the projection matrix
    // exactly as the client provided, so depth buffers would have expected results,
    // for a client to be able to reproject to world space using the projection matrices
    if (dxvk::RtxOptions::enableNearPlaneOverride()) {
      assert(0);
      const_cast<bool&>(dxvk::RtxOptions::enableNearPlaneOverride()) = false;
    }
    remixDevice->EmitCs([cRtCamera = convert::toRtCamera(*info)](dxvk::DxvkContext* ctx) {
      static_cast<dxvk::RtxContext*>(ctx)->prepareScreenResolution();
      ctx->getCommonObjects()->getSceneManager().getCameraManager()
        .processExternalCamera(cRtCamera.type, cRtCamera.worldToView, cRtCamera.viewToProjection);
    });
    return REMIXAPI_ERROR_CODE_SUCCESS;
  }

  remixapi_ErrorCode REMIXAPI_CALL remixapi_SetCameraMediumMaterial(
    const remixapi_CameraMediumInfo* info) {
    dxvk::D3D9DeviceEx* remixDevice = tryAsDxvk();
    if (!remixDevice) {
      return REMIXAPI_ERROR_CODE_REMIX_DEVICE_WAS_NOT_REGISTERED;
    }
    if (!info || info->sType != REMIXAPI_STRUCT_TYPE_CAMERA_MEDIUM_INFO) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }

    const remixapi_MaterialHandle handle = info->medium;

    std::lock_guard lock { s_mutex };
    remixDevice->EmitCs([handle](dxvk::DxvkContext* ctx) {
      auto& sceneManager = ctx->getCommonObjects()->getSceneManager();
      if (handle == nullptr) {
        sceneManager.clearExternalStartInMediumMaterial();
        return;
      }

      const auto* material = sceneManager.getAssetReplacer()->accessExternalMaterial(handle);
      if (material == nullptr) {
        dxvk::Logger::warn("SetCameraMediumMaterial: material handle not found");
        return;
      }
      if (material->data.getType() != dxvk::MaterialDataType::Translucent) {
        dxvk::Logger::warn("SetCameraMediumMaterial: material must be translucent");
        return;
      }

      sceneManager.setExternalStartInMediumMaterial(material->data);
    });
    return REMIXAPI_ERROR_CODE_SUCCESS;
  }

  remixapi_ErrorCode REMIXAPI_CALL remixapi_DrawInstance(
    const remixapi_InstanceInfo* info) {
    dxvk::D3D9DeviceEx* remixDevice = tryAsDxvk();
    if (!remixDevice) {
      return REMIXAPI_ERROR_CODE_REMIX_DEVICE_WAS_NOT_REGISTERED;
    }
    ScopedCpuProfileZone();

    if (!info || info->sType != REMIXAPI_STRUCT_TYPE_INSTANCE_INFO) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }
    // Hoist conversion outside of mutex
    auto drawState = convert::toRtDrawState(*info);

    std::lock_guard lock { s_mutex };
    remixDevice->EmitCs<false>([cRtDrawState = std::move(drawState)](dxvk::DxvkContext* dxvkCtx) mutable {
      auto* ctx = static_cast<dxvk::RtxContext*>(dxvkCtx);
      ctx->commitExternalGeometryToRT(*cRtDrawState);
    });
    return REMIXAPI_ERROR_CODE_SUCCESS;
  }

  #include "rtx_remix_retained_scene.inl"

  remixapi_ErrorCode submitApiLight(
    const remixapi_LightInfo* info,
    remixapi_LightHandle* out_handle, bool update) {
    dxvk::D3D9DeviceEx* remixDevice = tryAsDxvk();
    if (!remixDevice) {
      return REMIXAPI_ERROR_CODE_REMIX_DEVICE_WAS_NOT_REGISTERED;
    }
    if (!out_handle || !info || info->sType != REMIXAPI_STRUCT_TYPE_LIGHT_INFO) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }
    static std::atomic<uint64_t> nextLight { 0 };
    auto handle = update ? *out_handle : reinterpret_cast<remixapi_LightHandle>(++nextLight);

    // async load
    std::lock_guard lock { s_mutex };
    const bool dome = pnext::find<remixapi_LightInfoDomeEXT>(info) != nullptr;
    const auto transmission = pnext::find<remixapi_LightInfoDomeTransmittanceEXT>(info);
    const auto projection = pnext::find<remixapi_LightInfoDomeProjectionEXT>(info);
    if (projection && (!dome || projection->projection > 2)) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }
    if (transmission && (!dome || !transmission->transmittanceTexture)) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }
    if (update) {
      const auto found = s_apiLightKinds.find(handle);
      if (found == s_apiLightKinds.end() || found->second != dome) {
        return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
      }
    }
    if (auto src = pnext::find<remixapi_LightInfoDomeEXT>(info)) {
      if (!std::isfinite(src->opacity) || src->blendType > 10) {
        return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
      }
      // Special case for dome lights
      remixDevice->EmitCs([cHandle = handle, 
                          cRadiance = convert::tovec3(info->radiance), 
                          cOpacity = std::clamp(src->opacity, 0.f, 1.f),
                          cBlendType = src->blendType,
                          cProjection = projection ? projection->projection : 0,
                          cBackgroundOnly = projection && convert::tobool(projection->backgroundOnly),
                          cTransform = convert::tomat4(src->transform), 
                          cTransmission = lookupApiTexture(transmission ? transmission->transmittanceTexture : nullptr),
                          cTexture = lookupApiTexture(src->colorTexture)]
                          (dxvk::DxvkContext* ctx) {

        dxvk::DomeLight domeLight;
        domeLight.radiance = cRadiance;
        domeLight.opacity = cOpacity;
        domeLight.blendType = cBlendType;
        domeLight.projection = cProjection;
        domeLight.backgroundOnly = cBackgroundOnly;
        domeLight.worldToLight = inverse(cTransform);
        domeLight.texture = resolveApiTexture(*ctx, cTexture);
        domeLight.transmittance = resolveApiTexture(*ctx, cTransmission);

        // Ensures a texture stays in VidMem
        uint32_t unused;
        ctx->getCommonObjects()->getSceneManager().trackTexture(domeLight.texture, unused, true, true);

        auto& lightMgr = ctx->getCommonObjects()->getSceneManager().getLightManager();
        lightMgr.addExternalDomeLight(cHandle, domeLight);
      });
    } else {
      // Regular analytical light handling
      const auto rtLight = convert::toRtLight(*info);

      // Note: If the toRtLight conversion process returns an empty optional, the specified LightInfo did
      // not contain the proper arguments to create a light with.
      if (!rtLight.has_value()) {
        return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
      }

      remixDevice->EmitCs([cHandle = handle, cRtLight = *rtLight](dxvk::DxvkContext* ctx) {
        auto& lightMgr = ctx->getCommonObjects()->getSceneManager().getLightManager();
        lightMgr.addExternalLight(cHandle, cRtLight);
      });
    }

    s_apiLightKinds.insert_or_assign(handle, dome);
    *out_handle = handle;
    return REMIXAPI_ERROR_CODE_SUCCESS;
  }

  remixapi_ErrorCode REMIXAPI_CALL remixapi_CreateLight(
    const remixapi_LightInfo* info, remixapi_LightHandle* out_handle) {
    return submitApiLight(info, out_handle, false);
  }

  remixapi_ErrorCode REMIXAPI_CALL updateApiLight(remixapi_LightHandle handle, const remixapi_LightInfo* info) {
    return submitApiLight(info, &handle, true);
  }

  remixapi_ErrorCode REMIXAPI_CALL remixapi_DestroyLight(
    remixapi_LightHandle handle) {
    dxvk::D3D9DeviceEx* remixDevice = tryAsDxvk();
    if (!remixDevice) {
      return REMIXAPI_ERROR_CODE_REMIX_DEVICE_WAS_NOT_REGISTERED;
    }
    std::lock_guard lock { s_mutex };
    s_apiLightKinds.erase(handle);
    remixDevice->EmitCs([cHandle = handle](dxvk::DxvkContext* ctx) {
      auto& lightMgr = ctx->getCommonObjects()->getSceneManager().getLightManager();
      lightMgr.removeExternalLight(cHandle);
    });
    return REMIXAPI_ERROR_CODE_SUCCESS;
  }


  remixapi_ErrorCode REMIXAPI_CALL remixapi_DrawLightInstance(
    remixapi_LightHandle lightHandle) {
    dxvk::D3D9DeviceEx* remixDevice = tryAsDxvk();
    if (!remixDevice) {
      return REMIXAPI_ERROR_CODE_REMIX_DEVICE_WAS_NOT_REGISTERED;
    }
    if (!lightHandle) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }

    // async load
    std::lock_guard lock { s_mutex };
    remixDevice->EmitCs([lightHandle](dxvk::DxvkContext* ctx) {
      auto& lightMgr = ctx->getCommonObjects()->getSceneManager().getLightManager();
      lightMgr.addExternalLightInstance(lightHandle);
    });

    return REMIXAPI_ERROR_CODE_SUCCESS;
  }

  remixapi_ErrorCode REMIXAPI_CALL remixapi_SetConfigVariable(const char* key, const char* value) {
    std::lock_guard lock { s_mutex };
    return dxvk::remixapi_config::setVariable(key, value);
  }

  remixapi_ErrorCode REMIXAPI_CALL remixapi_GetConfigVariableResolved(remixapi_ConfigVariableResolvedInfo* info) {
    return dxvk::remixapi_config::getResolved(info);
  }

  template<typename Task> remixapi_ErrorCode runConfigLayerTask(Task task) {
    std::lock_guard lock { s_mutex };
    auto device = tryAsDxvk();
    if (!device) {
      return REMIXAPI_ERROR_CODE_REMIX_DEVICE_WAS_NOT_REGISTERED;
    }
    remixapi_ErrorCode result = REMIXAPI_ERROR_CODE_GENERAL_FAILURE;
    device->EmitCs([&result, task = std::move(task)](dxvk::DxvkContext*) mutable {
      try {
        result = task();
      } catch (const std::exception& error) {
        dxvk::Logger::err(error.what());
        result = REMIXAPI_ERROR_CODE_GENERAL_FAILURE;
      } catch (...) {
        result = REMIXAPI_ERROR_CODE_GENERAL_FAILURE;
      }
    });
    device->Flush();
    device->SynchronizeCsThread();
    return result;
  }
  remixapi_ErrorCode REMIXAPI_CALL remixapi_AcquireConfigLayer(const char* path, float priority, float threshold, remixapi_ConfigLayerHandle* handle) {
    if (!path || !handle || !std::isfinite(priority) || !std::isfinite(threshold)) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }
    return runConfigLayerTask([path = std::string(path), priority, threshold, handle] {
      return dxvk::remixapi_config::acquireLayer(path.c_str(), priority, threshold, handle);
    });
  }
  remixapi_ErrorCode REMIXAPI_CALL remixapi_SetConfigLayerBlending(remixapi_ConfigLayerHandle handle, float strength, float threshold) {
    return runConfigLayerTask([=] { return dxvk::remixapi_config::setLayerBlending(handle, strength, threshold); });
  }
  remixapi_ErrorCode REMIXAPI_CALL remixapi_ReleaseConfigLayer(remixapi_ConfigLayerHandle handle) {
    return runConfigLayerTask([=] { return dxvk::remixapi_config::releaseLayer(handle); });
  }

  remixapi_ErrorCode REMIXAPI_CALL remixapi_pick_RequestObjectPicking(
    const remixapi_Rect2D* pixelRegion,
    PFN_remixapi_pick_RequestObjectPickingUserCallback callback,
    void* callbackUserData) {
    if (!pixelRegion || !callback) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }

    dxvk::D3D9DeviceEx* remixDevice = tryAsDxvk();
    if (!remixDevice) {
      return REMIXAPI_ERROR_CODE_REMIX_DEVICE_WAS_NOT_REGISTERED;
    }
    std::lock_guard lock { s_mutex };

    dxvk::ObjectPicking& picking = remixDevice->GetDXVKDevice()->getCommon()
      ->metaDebugView().ObjectPicking;

    picking.request(
      dxvk::Vector2i{ pixelRegion->left, pixelRegion->top },
      dxvk::Vector2i{ pixelRegion->right, pixelRegion->bottom },
      // invoke user's callback on result
      [callback, callbackUserData](std::vector<dxvk::ObjectPickingValue>&& objectPickingValues, std::optional<XXH64_hash_t>) {
        callback(objectPickingValues.data(), uint32_t(objectPickingValues.size()), callbackUserData);
      }
    );
    return REMIXAPI_ERROR_CODE_SUCCESS;
  }

  remixapi_ErrorCode REMIXAPI_CALL remixapi_pick_HighlightObjects(
    const uint32_t* objectPickingValues_values,
    uint32_t objectPickingValues_count,
    uint8_t colorR,
    uint8_t colorG,
    uint8_t colorB) {
    if (!objectPickingValues_values) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }

    dxvk::D3D9DeviceEx* remixDevice = tryAsDxvk();
    if (!remixDevice) {
      return REMIXAPI_ERROR_CODE_REMIX_DEVICE_WAS_NOT_REGISTERED;
    }
    std::lock_guard lock { s_mutex };

    if (objectPickingValues_count > 0) {
      dxvk::g_customHighlightColor = { colorR, colorG, colorB };

      const auto frameId = remixDevice->GetDXVKDevice()->getCurrentFrameId();
      const auto values = std::vector< dxvk::ObjectPickingValue > {
        objectPickingValues_values,
        objectPickingValues_values + objectPickingValues_count,
      };
      remixDevice->GetDXVKDevice()->getCommon()->metaDebugView().Highlighting
        .requestHighlighting(
          &values,
          dxvk::HighlightColor::FromVariable,
          frameId);   // thread-safe
    }
    return REMIXAPI_ERROR_CODE_SUCCESS;
  }

  remixapi_ErrorCode REMIXAPI_CALL remixapi_dxvk_CreateD3D9(
    const remixapi_StartupInfo& info,
    IDirect3D9Ex** out_pD3D9) {
    IDirect3D9Ex* d3d9ex = nullptr;

    HRESULT hr = S_OK;
    if (s_hostD3D9.ptr()) {
      d3d9ex = s_hostD3D9.ptr();
      d3d9ex->AddRef();
    } else {
      hr = dxvk::CreateD3D9(true, &d3d9ex, info.forceNoVkSwapchain || info.interopImage, false, true);
    }
    if (FAILED(hr) || !d3d9ex) {
      if (isHResultAliasedWithRemixErrorCode(hr)) {
        return static_cast<remixapi_ErrorCode>(hr);
      }
      return REMIXAPI_ERROR_CODE_GENERAL_FAILURE;
    }

    dxvk::g_combineGuiInFinalColor = info.combineGuiInFinalColor;
    dxvk::g_allowSrgbConversionForOutput = !info.disableSrgbConversionForOutput;
    dxvk::g_allowMappingLegacyHashToObjectPickingValue = !info.editorModeEnabled;
    dxvk::g_remixApiEditorModeEnabled = info.editorModeEnabled;

    // slightly different initial settings for HdRemix
    if (info.editorModeEnabled) {
      const_cast<dxvk::LightManager::FallbackLightMode&>(dxvk::LightManager::fallbackLightMode()) = dxvk::LightManager::FallbackLightMode::Never;
      const_cast<bool&>(dxvk::DxvkPostFx::desaturateOthersOnHighlight()) = false;
      const_cast<bool&>(dxvk::RtxOptions::showUICursor()) = false;
    }

    *out_pD3D9 = d3d9ex;
    return REMIXAPI_ERROR_CODE_SUCCESS;
  }

  // HdRemix has editorModeEnabled=true
  remixapi_ErrorCode REMIXAPI_CALL remixapi_dxvk_CreateD3D9_legacy(
    remixapi_Bool editorModeEnabled,
    IDirect3D9Ex** out_pD3D9) {
    auto i = remixapi_StartupInfo {};
    {
      i.sType = REMIXAPI_STRUCT_TYPE_STARTUP_INFO;
      i.disableSrgbConversionForOutput = editorModeEnabled;
      i.forceNoVkSwapchain = editorModeEnabled;
      i.editorModeEnabled = editorModeEnabled;
      i.combineGuiInFinalColor = !editorModeEnabled;
      static_assert(sizeof(remixapi_StartupInfo) == 48, "If changing, also set defaults here");
    }
    return remixapi_dxvk_CreateD3D9(i, out_pD3D9);
  }

  remixapi_ErrorCode REMIXAPI_CALL remixapi_dxvk_RegisterD3D9Device(
    IDirect3DDevice9Ex* d3d9Device) {
    if (!d3d9Device) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }
    if (s_dxvkDevice) {
      return REMIXAPI_ERROR_CODE_ALREADY_EXISTS;
    }
    auto dxvkDevice = dynamic_cast<dxvk::D3D9DeviceEx*>(d3d9Device);
    if (!dxvkDevice) {
      return REMIXAPI_ERROR_CODE_REGISTERING_NON_REMIX_D3D9_DEVICE;
    }
    IDirect3D9* dxvkD3d9 = nullptr;
    HRESULT hr = dxvkDevice->GetDirect3D(&dxvkD3d9);
    if (FAILED(hr) || !dxvkD3d9) {
      assert(0);
      return REMIXAPI_ERROR_CODE_GENERAL_FAILURE;
    }
    auto dxvkD3d9Ex = dynamic_cast<IDirect3D9Ex*>(dxvkD3d9);
    if (!dxvkD3d9Ex) {
      assert(0);
      dxvkD3d9->Release();
      return REMIXAPI_ERROR_CODE_NOT_INITIALIZED;
    }
    // if D3D9 already exists, check that user-provided D3D9 corresponds to our s_dxvkD3D9
    if (s_dxvkD3D9) {
      assert(s_dxvkD3D9 == dxvkD3d9Ex);
    }
    // GetDirect3D supplied the API's parent reference. Keep one device reference
    // independently of the caller; Shutdown releases precisely these two references.
    s_dxvkD3D9 = dxvkD3d9Ex;
    dxvkDevice->AddRef();
    s_dxvkDevice = dxvkDevice;
    dxvk::g_dxvkDeviceNative = dxvkDevice->GetDXVKDevice().ptr();
    return REMIXAPI_ERROR_CODE_SUCCESS;
  }

  remixapi_ErrorCode REMIXAPI_CALL remixapi_dxvk_GetExternalSwapchain(
    uint64_t* out_vkImage,
    uint64_t* out_vkSemaphoreRenderingDone,
    uint64_t* out_vkSemaphoreResumeSemaphore) {
    dxvk::D3D9DeviceEx* remixDevice = tryAsDxvk();
    if (!remixDevice) {
      return REMIXAPI_ERROR_CODE_REMIX_DEVICE_WAS_NOT_REGISTERED;
    }
    if (!out_vkImage || !out_vkSemaphoreRenderingDone || !out_vkSemaphoreResumeSemaphore) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }
    if (auto pres = remixDevice->GetExternalPresenter()) {
      *out_vkImage = reinterpret_cast<uint64_t>(pres->GetVkImage(0));
      *out_vkSemaphoreRenderingDone = reinterpret_cast<uint64_t>(pres->GetFrameCompleteVkSemaphore());
      *out_vkSemaphoreResumeSemaphore = reinterpret_cast<uint64_t>(pres->GetFrameResumeVkSemaphore());
      return REMIXAPI_ERROR_CODE_SUCCESS;
    }
    return REMIXAPI_ERROR_CODE_GENERAL_FAILURE;
  }

  remixapi_ErrorCode REMIXAPI_CALL remixapi_dxvk_GetVkImage(
    IDirect3DSurface9* source,
    uint64_t* out_vkImage) {
    dxvk::D3D9DeviceEx* remixDevice = tryAsDxvk();
    if (!remixDevice) {
      return REMIXAPI_ERROR_CODE_REMIX_DEVICE_WAS_NOT_REGISTERED;
    }
    if (!source || !out_vkImage) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }

    dxvk::D3D9Surface* surface = static_cast<dxvk::D3D9Surface*>(source);
    dxvk::D3D9CommonTexture* texInfo = surface ? surface->GetCommonTexture() : nullptr;
    if (texInfo) {
      *out_vkImage = reinterpret_cast<uint64_t>(texInfo->GetImage()->handle());
      return REMIXAPI_ERROR_CODE_SUCCESS;
    }
    return REMIXAPI_ERROR_CODE_GENERAL_FAILURE;
  }

  remixapi_ErrorCode copyRenderingOutput(
    IDirect3DSurface9* destination,
    remixapi_dxvk_CopyRenderingOutputType type,
    bool copyOnPresent) {
    dxvk::D3D9DeviceEx* remixDevice = tryAsDxvk();
    if (!remixDevice) {
      return REMIXAPI_ERROR_CODE_REMIX_DEVICE_WAS_NOT_REGISTERED;
    }
    if (!destination) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }
    dxvk::D3D9Surface* destSurface = static_cast<dxvk::D3D9Surface*>(destination);
    dxvk::D3D9CommonTexture* destTexInfo = destSurface ? destSurface->GetCommonTexture() : nullptr;
    dxvk::Rc<dxvk::DxvkImage> destImage = destTexInfo->GetImage();
    dxvk::Rc<dxvk::DxvkImageView> destImageView = destTexInfo->GetSampleView(false);

    if (!destTexInfo) {
      return REMIXAPI_ERROR_CODE_GENERAL_FAILURE;
    }

    if (type == REMIXAPI_DXVK_COPY_RENDERING_OUTPUT_TYPE_OBJECT_PICKING) {
      // suppress resource clean up
      if (!dxvk::g_forceKeepObjectPickingImage) {
        dxvk::g_forceKeepObjectPickingImage = true;
        return REMIXAPI_ERROR_CODE_SUCCESS;
      }
    }

#pragma warning(push)
#pragma warning(error : 4061) // all switch cases must be handled explicitly

    switch (type) {
    case REMIXAPI_DXVK_COPY_RENDERING_OUTPUT_TYPE_FINAL_COLOR:
    case REMIXAPI_DXVK_COPY_RENDERING_OUTPUT_TYPE_DEPTH:
    case REMIXAPI_DXVK_COPY_RENDERING_OUTPUT_TYPE_NORMALS:
    case REMIXAPI_DXVK_COPY_RENDERING_OUTPUT_TYPE_OBJECT_PICKING:
    case REMIXAPI_DXVK_COPY_RENDERING_OUTPUT_TYPE_WORLD_POSITION_RAW:
    case REMIXAPI_DXVK_COPY_RENDERING_OUTPUT_TYPE_TEXCOORDS_RAW:
    case REMIXAPI_DXVK_COPY_RENDERING_OUTPUT_TYPE_NORMAL_F32:
    case REMIXAPI_DXVK_COPY_RENDERING_OUTPUT_TYPE_COVERAGE:
      break;
    case REMIXAPI_DXVK_COPY_RENDERING_OUTPUT_TYPE_GUI:
      if (!dxvk::ImGUI::enableExternalPresenter()) {
        return REMIXAPI_ERROR_CODE_GENERAL_FAILURE;
      }
      break;
    default:
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }

    std::lock_guard lock { s_mutex };

    // Pass in the backbuffer incase we need it
    dxvk::Com<IDirect3DSurface9> backBuffer;
    if (FAILED(remixDevice->GetBackBuffer(0, 0, D3DBACKBUFFER_TYPE_MONO, &backBuffer)) ||
        backBuffer == nullptr) {
      return REMIXAPI_ERROR_CODE_GENERAL_FAILURE;
    }
    auto* backBufferSurface = static_cast<dxvk::D3D9Surface*>(backBuffer.ptr());
    dxvk::Rc<dxvk::DxvkImage> backbuffer0 = backBufferSurface->GetCommonTexture()->GetImage();

    auto copyTask = [cDest = std::move(destImage),
                         cDestView = std::move(destImageView),
                         cBackbuffer = std::move(backbuffer0),
                         type = type] (dxvk::DxvkContext* ctx) {
      dxvk::Resources& resourceManager = ctx->getCommonObjects()->getResources();
      const dxvk::Resources::RaytracingOutput& rtOutput = resourceManager.getRaytracingOutput();

      dxvk::Rc<dxvk::DxvkImage> srcImage = nullptr;
      switch (type) {
      case REMIXAPI_DXVK_COPY_RENDERING_OUTPUT_TYPE_FINAL_COLOR:
        srcImage = cBackbuffer;
        break;
      case REMIXAPI_DXVK_COPY_RENDERING_OUTPUT_TYPE_DEPTH:
        srcImage = rtOutput.m_primaryDepth.image;
        break;
      case REMIXAPI_DXVK_COPY_RENDERING_OUTPUT_TYPE_NORMALS:
        // The compacted GBuffer stores normals in compacted order, so it also writes a dense copy for this.
        if (rtOutput.m_raytraceArgs.sparseRenderingArgs.mode != SparseRenderingMode::Off) {
          srcImage = rtOutput.m_primaryWorldShadingNormalDense.image;
        } else {
          srcImage = rtOutput.m_primaryWorldShadingNormal.image;
        }
        break;
      case REMIXAPI_DXVK_COPY_RENDERING_OUTPUT_TYPE_OBJECT_PICKING:
        srcImage = rtOutput.m_primaryObjectPicking.image;
        break;
      case REMIXAPI_DXVK_COPY_RENDERING_OUTPUT_TYPE_GUI: {
        dxvk::DxvkRenderTargets renderTargets;
        renderTargets.color[0].view = cDestView;
        renderTargets.color[0].layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
        ctx->bindRenderTargets(renderTargets);
        ctx->getCommonObjects()->getImgui().render(ctx, { cDestView->imageInfo().extent.width, cDestView->imageInfo().extent.height });

        break;
      }
      case REMIXAPI_DXVK_COPY_RENDERING_OUTPUT_TYPE_WORLD_POSITION_RAW:
        srcImage = rtOutput.m_primaryWorldPositionRaw.image;
        break;
      case REMIXAPI_DXVK_COPY_RENDERING_OUTPUT_TYPE_TEXCOORDS_RAW:
        srcImage = rtOutput.m_primaryTexcoordsRaw.image;
        break;
      case REMIXAPI_DXVK_COPY_RENDERING_OUTPUT_TYPE_NORMAL_F32:
        srcImage = rtOutput.m_primaryNormalF32.image;
        break;
      case REMIXAPI_DXVK_COPY_RENDERING_OUTPUT_TYPE_COVERAGE:
        srcImage = rtOutput.m_primaryCoverage.image;
        break;
      default:
        assert(!"unexpected remixapi_dxvk_CopyRenderingOutputType value");
        return;
      }

      if (srcImage.ptr()) {
        dxvk::RtxContext::blitImageHelper(ctx, srcImage, cDest, VkFilter::VK_FILTER_NEAREST);
      }
    };

    auto* presenter = remixDevice->GetExternalPresenter();
    if (copyOnPresent && presenter) {
      presenter->queuePresentTask(std::move(copyTask));
    } else {
      remixDevice->EmitCs(std::move(copyTask));
    }

#pragma warning(pop)

    return REMIXAPI_ERROR_CODE_SUCCESS;
  }

  remixapi_ErrorCode REMIXAPI_CALL remixapi_dxvk_CopyRenderingOutput(
    IDirect3DSurface9* destination,
    remixapi_dxvk_CopyRenderingOutputType type) {
    return copyRenderingOutput(destination, type, false);
  }


  remixapi_ErrorCode REMIXAPI_CALL remixapi_dxvk_SetDefaultOutput(
    remixapi_dxvk_CopyRenderingOutputType type, const remixapi_Float4D* color) {
    if (!color) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }

    dxvk::D3D9DeviceEx* remixDevice = tryAsDxvk();
    if (!remixDevice) {
      return REMIXAPI_ERROR_CODE_REMIX_DEVICE_WAS_NOT_REGISTERED;
    }

    std::lock_guard lock { s_mutex };
    remixDevice->EmitCs([type, cColor = *color](dxvk::DxvkContext* ctx) {
      dxvk::RtxGlobals& globals = ctx->getCommonObjects()->getSceneManager().getGlobals();
      switch (type) {
      case REMIXAPI_DXVK_COPY_RENDERING_OUTPUT_TYPE_FINAL_COLOR:
        globals.clearColorFinalColor = vec3(cColor.x, cColor.y, cColor.z);
        break;
      case REMIXAPI_DXVK_COPY_RENDERING_OUTPUT_TYPE_DEPTH:
        globals.clearColorDepth = cColor.x;
        break;
      case REMIXAPI_DXVK_COPY_RENDERING_OUTPUT_TYPE_NORMALS:
        globals.clearColorNormal = vec3(cColor.x, cColor.y, cColor.z);
        break;
      case REMIXAPI_DXVK_COPY_RENDERING_OUTPUT_TYPE_OBJECT_PICKING:
        // converting binary value of color.x into uint to avoid losing precision.
        globals.clearColorPicking = reinterpret_cast<const uint&>(cColor.x);
        break;
      default:
        break;
      }
    });
    
    return REMIXAPI_ERROR_CODE_SUCCESS;
  }

  remixapi_ErrorCode REMIXAPI_CALL remixapi_dxvk_PrepareVulkan() {
    std::lock_guard lock { s_mutex };
    if (s_hostVulkanPrepared) {
      return REMIXAPI_ERROR_CODE_ALREADY_EXISTS;
    }
    dxvk::InitializeD3D9Runtime();
    dxvk::RtxOptions::Create();
    dxvk::remixapi_config::applyPendingVariables();
    dxvk::NsightGraphicsCapture::initialize();
    s_hostVulkanPrepared = true;
    return REMIXAPI_ERROR_CODE_SUCCESS;
  }

  remixapi_ErrorCode REMIXAPI_CALL remixapi_dxvk_CreateVulkanDevice(remixapi_HostVulkanDeviceInfo* info) {
    if (!info || info->sType != REMIXAPI_STRUCT_TYPE_HOST_VULKAN_DEVICE_INFO || info->pNext ||
        !info->instance || !info->physicalDevice || !info->deviceCreateInfo ||
        info->instanceApiVersion < VK_API_VERSION_1_4 ||
        (info->instanceExtensionCount && !info->instanceExtensions)) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }
    std::lock_guard lock { s_mutex };
    if (!s_hostVulkanPrepared || s_hostD3D9.ptr() || s_dxvkD3D9 || s_dxvkDevice) {
      return REMIXAPI_ERROR_CODE_ALREADY_EXISTS;
    }
    const auto& application = *static_cast<const VkDeviceCreateInfo*>(info->deviceCreateInfo);
    if (application.sType != VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO || !application.queueCreateInfoCount ||
        !application.pQueueCreateInfos || (application.enabledExtensionCount && !application.ppEnabledExtensionNames)) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }
    dxvk::DxvkNameSet instanceExtensions;
    for (uint32_t i = 0; i < info->instanceExtensionCount; ++i) {
      if (!info->instanceExtensions[i]) {
        return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
      }
      instanceExtensions.add(info->instanceExtensions[i]);
    }
    dxvk::DxvkHostInstanceInfo host {
      reinterpret_cast<VkInstance>(info->instance), reinterpret_cast<VkPhysicalDevice>(info->physicalDevice), &instanceExtensions
    };
    dxvk::Com<IDirect3D9Ex> d3d9;
    const HRESULT result = dxvk::CreateD3D9(true, &d3d9, true, false, true, &host);
    if (FAILED(result)) {
      return isHResultAliasedWithRemixErrorCode(result) ? static_cast<remixapi_ErrorCode>(result) : REMIXAPI_ERROR_CODE_GENERAL_FAILURE;
    }
    auto* native = static_cast<dxvk::D3D9InterfaceEx*>(d3d9.ptr());
    info->device = reinterpret_cast<uint64_t>(native->createHostVulkanDevice(application, info->graphicsQueueFamily));
    const auto& enabled = native->hostDeviceInfo().extensionNames;
    info->enabledExtensionCount = enabled.count();
    info->enabledExtensions = enabled.names();
    s_hostD3D9 = std::move(d3d9);
    return REMIXAPI_ERROR_CODE_SUCCESS;
  }

  remixapi_ErrorCode REMIXAPI_CALL remixapi_dxvk_AdoptVulkanDevice(uint64_t device) {
    std::lock_guard lock { s_mutex };
    if (!s_hostD3D9.ptr() || s_dxvkDevice) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }
    static_cast<dxvk::D3D9InterfaceEx*>(s_hostD3D9.ptr())->transferHostDeviceOwnership(reinterpret_cast<VkDevice>(device));
    return REMIXAPI_ERROR_CODE_SUCCESS;
  }

  remixapi_ErrorCode REMIXAPI_CALL remixapi_Startup(const remixapi_StartupInfo* info) {
    if (!info || info->sType != REMIXAPI_STRUCT_TYPE_STARTUP_INFO) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }
    assert(!!(s_dxvkD3D9) == !!(s_dxvkDevice));
    if (s_dxvkD3D9 || s_dxvkDevice) {
      return REMIXAPI_ERROR_CODE_ALREADY_EXISTS;
    }
    if (s_hostD3D9.ptr() && !static_cast<dxvk::D3D9InterfaceEx*>(s_hostD3D9.ptr())->hostDeviceInfo().ownershipTransferred) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }

    dxvk::Com<IDirect3D9Ex> d3d9;
    {
      remixapi_ErrorCode status = remixapi_dxvk_CreateD3D9(*info, &d3d9);
      if (status != REMIXAPI_ERROR_CODE_SUCCESS) {
        return status;
      }
    }

    HWND hwnd = nullptr;
    uint32_t width = 0, height = 0;

    if (info->hwnd) {
      hwnd = info->hwnd;
      auto hwndRect = RECT {};
      GetClientRect(info->hwnd, &hwndRect);
      width = static_cast<uint32_t>(std::max(0l, hwndRect.right - hwndRect.left));
      height = static_cast<uint32_t>(std::max(0l, hwndRect.bottom - hwndRect.top));
    }

    dxvk::Com<IDirect3DDevice9Ex> d3d9Device;
    {
      auto presInfo = D3DPRESENT_PARAMETERS {};
      {
        presInfo.BackBufferWidth = width;
        presInfo.BackBufferHeight = height;
        presInfo.BackBufferFormat = D3DFMT_UNKNOWN;
        presInfo.BackBufferCount = 0;
        presInfo.MultiSampleType = D3DMULTISAMPLE_NONE;
        presInfo.MultiSampleQuality = 0;
        presInfo.SwapEffect = D3DSWAPEFFECT_DISCARD;
        presInfo.hDeviceWindow = hwnd;
        presInfo.Windowed = true;
        presInfo.EnableAutoDepthStencil = false;
        presInfo.AutoDepthStencilFormat = D3DFMT_UNKNOWN;
        presInfo.Flags = 0;
        presInfo.FullScreen_RefreshRateInHz = 0;
        presInfo.PresentationInterval = 0;
      }

      HRESULT hr = d3d9->CreateDeviceEx(
        D3DADAPTER_DEFAULT,
        D3DDEVTYPE_HAL,
        hwnd,
        D3DCREATE_HARDWARE_VERTEXPROCESSING,
        &presInfo,
        nullptr,
        &d3d9Device);
      if (FAILED(hr) || d3d9Device == nullptr) {
        if (isHResultAliasedWithRemixErrorCode(hr)) {
          // return special aliased HRESULT
          return static_cast<remixapi_ErrorCode>(hr);
        }
        return REMIXAPI_ERROR_CODE_GENERAL_FAILURE;
      }
    }
    // Complete fallible initialization while the local COM owners can still unwind.
    // Publish API-owned references only once the device is ready for use.
    auto* device = dynamic_cast<dxvk::D3D9DeviceEx*>(d3d9Device.ptr());
    if (!device) {
      return REMIXAPI_ERROR_CODE_REGISTERING_NON_REMIX_D3D9_DEVICE;
    }
    dxvk::remixapi_config::applyPendingVariables();
    if (info->interopImage) {
      auto* presenter = device->GetExternalPresenter();
      if (!presenter) {
        return REMIXAPI_ERROR_CODE_GENERAL_FAILURE;
      }
      presenter->enableInterop();
    }
    return remixapi_dxvk_RegisterD3D9Device(d3d9Device.ptr());
  }

  remixapi_ErrorCode REMIXAPI_CALL remixapi_Shutdown(void) {
    std::lock_guard lock { s_mutex };
    s_apiLightKinds.clear();
    resetRetainedSceneHandles();
    if (s_dxvkDevice) {
      s_dxvkDevice->EmitCs([](dxvk::DxvkContext* ctx) {
        clearRetainedSceneInstances(ctx);
        dxvk::remixapi_config::releaseAllLayers();
      });
      s_dxvkDevice->Flush();
      s_dxvkDevice->SynchronizeCsThread();
    }
    s_apiTextures.clear();
    s_sharedBuffers.clear();
    s_sharedProducer = nullptr;
    s_sharedReadySequence = 0;
    s_sharedCommittedValue = 0;
    if (s_dxvkDevice) {
      if (dxvk::D3D9SwapchainExternal* presenter = s_dxvkDevice->GetExternalPresenter()) {
        presenter->clearPresentTasks();
      }
      dxvk::g_dxvkDeviceNative = nullptr;
      auto* device = std::exchange(s_dxvkDevice, nullptr);
      device->Release();
    }
    if (s_dxvkD3D9) {
      auto* d3d9 = std::exchange(s_dxvkD3D9, nullptr);
      d3d9->Release();
    }
    s_hostD3D9 = nullptr;
    s_hostVulkanPrepared = false;

    // Make sure Sentry doesn't keep the process alive when it should be shutting down.
    dxvk::sentry::shutdown();

    return REMIXAPI_ERROR_CODE_SUCCESS;
  }

  remixapi_ErrorCode REMIXAPI_CALL remixapi_Submit(const remixapi_SubmitInfo* info) {
    const uint32_t flags = info ? info->interopFlags : 0;
    const auto* water = info && info->pNext
      ? static_cast<const remixapi_SubmitInfoWaterSimEXT*>(info->pNext) : nullptr;
    if ((info && info->sType != REMIXAPI_STRUCT_TYPE_SUBMIT_INFO) ||
        (water && (water->sType != REMIXAPI_STRUCT_TYPE_SUBMIT_INFO_WATER_SIM_EXT || water->pNext ||
          water->impulseCount > kWaterSimMaxImpulses || (water->impulseCount && !water->impulses) || water->reset > 1)) ||
        (flags & ~uint32_t(REMIXAPI_SUBMIT_INTEROP_BIT_FORCE_WAIT_DEVICE_IDLE |
          REMIXAPI_SUBMIT_INTEROP_BIT_DISABLE_CPU_WAIT_FOR_VK_SUBMIT))) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }
    std::lock_guard lock { s_mutex };
    auto device = tryAsDxvk();
    if (!device) {
      return REMIXAPI_ERROR_CODE_REMIX_DEVICE_WAS_NOT_REGISTERED;
    }
    auto presenter = device->GetExternalPresenter();
    if (!presenter || !presenter->hasInterop()) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }
    if (presenter->hasPendingInteropFrame()) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }
    if (device->GetDXVKDevice()->getDeviceStatus() != VK_SUCCESS) {
      return REMIXAPI_ERROR_CODE_GENERAL_FAILURE;
    }
    if (water) {
      std::array<WaterSimImpulse, kWaterSimMaxImpulses> impulses{};
      const bool zUp = dxvk::RtxOptions::zUp();
      for (uint32_t i = 0; i < water->impulseCount; ++i) {
        const auto& impulse = water->impulses[i];
        if (!std::isfinite(impulse.position.x) || !std::isfinite(impulse.position.y) || !std::isfinite(impulse.position.z) ||
            !(impulse.radius >= 0.25f && impulse.radius <= 32.0f) || !(std::abs(impulse.strength) <= 4.0f)) {
          return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
        }
        impulses[i] = { vec2(impulse.position.x, zUp ? impulse.position.y : impulse.position.z),
          impulse.radius, impulse.strength };
      }
      device->EmitCs([impulses, count = water->impulseCount, reset = water->reset != 0](dxvk::DxvkContext* ctx) {
        ctx->getCommonObjects()->waterSimulation().addImpulses(impulses.data(), count, reset);
      });
    }
    device->EmitCs([](dxvk::DxvkContext* ctx) { replayRetainedSceneInstances(ctx); });
    if (FAILED(device->Present(nullptr, nullptr, nullptr, nullptr))) {
      return REMIXAPI_ERROR_CODE_GENERAL_FAILURE;
    }
    if (flags & REMIXAPI_SUBMIT_INTEROP_BIT_FORCE_WAIT_DEVICE_IDLE) {
      device->GetDXVKDevice()->waitForIdle();
    } else if (!(flags & REMIXAPI_SUBMIT_INTEROP_BIT_DISABLE_CPU_WAIT_FOR_VK_SUBMIT)) {
      // External consumers need the signal submitted before they enter a driver wait.
      if (s_hostD3D9.ptr()) {
        device->GetDXVKDevice()->waitForHostSubmission();
      } else {
        device->GetDXVKDevice()->waitForSubmission();
      }
    }
    return device->GetDXVKDevice()->getDeviceStatus() == VK_SUCCESS
      ? REMIXAPI_ERROR_CODE_SUCCESS : REMIXAPI_ERROR_CODE_GENERAL_FAILURE;
  }

  remixapi_ErrorCode REMIXAPI_CALL remixapi_dxvk_GetInteropInfo(remixapi_dxvk_InteropInfo* info) {
    if (!info) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }
    std::lock_guard lock { s_mutex };
    auto device = tryAsDxvk();
    auto presenter = device ? device->GetExternalPresenter() : nullptr;
    if (!presenter || !presenter->hasInterop()) {
      return REMIXAPI_ERROR_CODE_REMIX_DEVICE_WAS_NOT_REGISTERED;
    }
    presenter->getInteropInfo(*info);
    return REMIXAPI_ERROR_CODE_SUCCESS;
  }

  remixapi_ErrorCode REMIXAPI_CALL remixapi_dxvk_GetVulkanInteropInfo(remixapi_VulkanInteropInfo* info) {
    if (!info || info->sType != REMIXAPI_STRUCT_TYPE_VULKAN_INTEROP_INFO) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }
    remixapi_InteropFrameStatusEXT* status = nullptr;
    remixapi_FrameGenerationCapabilitiesEXT* capabilities = nullptr;
    for (void* next = info->pNext; next; next = pnext::detail::getPNext(next)) {
      switch (pnext::detail::getStructType(next)) {
      case REMIXAPI_STRUCT_TYPE_INTEROP_FRAME_STATUS_EXT:
        if (status) return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
        status = static_cast<remixapi_InteropFrameStatusEXT*>(next);
        break;
      case REMIXAPI_STRUCT_TYPE_FRAME_GENERATION_CAPABILITIES_EXT:
        if (capabilities) return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
        capabilities = static_cast<remixapi_FrameGenerationCapabilitiesEXT*>(next);
        break;
      default:
        return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
      }
    }
    std::lock_guard lock { s_mutex };
    auto device = tryAsDxvk();
    auto presenter = device ? device->GetExternalPresenter() : nullptr;
    if (!presenter || !presenter->hasInterop()) {
      return REMIXAPI_ERROR_CODE_REMIX_DEVICE_WAS_NOT_REGISTERED;
    }
    presenter->getVulkanInteropInfo(*info);
    if (capabilities) {
      auto& ngx = device->GetDXVKDevice()->getCommon()->metaNGXContext();
      capabilities->initializationComplete = ngx.isInitializationComplete();
      capabilities->supported = capabilities->initializationComplete && ngx.supportsDLFG();
      capabilities->maxGeneratedFrames = capabilities->supported ? ngx.dlfgMaxInterpolatedFrames() : 0;
      memset(capabilities->notSupportedReason, 0, sizeof(capabilities->notSupportedReason));
      if (!capabilities->supported) {
        const std::string reason = capabilities->initializationComplete ? ngx.getDLFGNotSupportedReason() : "DLSS is initializing.";
        memcpy(capabilities->notSupportedReason, reason.data(), std::min(reason.size(), sizeof(capabilities->notSupportedReason) - 1));
      }
    }
    return REMIXAPI_ERROR_CODE_SUCCESS;
  }

  remixapi_ErrorCode REMIXAPI_CALL remixapi_Present(const remixapi_PresentInfo* info) {
    std::lock_guard lock { s_mutex };
    dxvk::D3D9DeviceEx* remixDevice = tryAsDxvk();
    if (!remixDevice) {
      return REMIXAPI_ERROR_CODE_REMIX_DEVICE_WAS_NOT_REGISTERED;
    }
    if (info && info->sType != REMIXAPI_STRUCT_TYPE_PRESENT_INFO) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }
    remixapi_VulkanClientFrameInfoEXT* clientFrame = nullptr;
    for (void* next = info ? info->pNext : nullptr; next; next = pnext::detail::getPNext(next)) {
      if (pnext::detail::getStructType(next) != REMIXAPI_STRUCT_TYPE_VULKAN_CLIENT_FRAME_INFO_EXT || clientFrame) {
        return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
      }
      clientFrame = static_cast<remixapi_VulkanClientFrameInfoEXT*>(next);
    }
    auto presenter = remixDevice->GetExternalPresenter();
    if (clientFrame) {
      if (!presenter) return REMIXAPI_ERROR_CODE_REMIX_DEVICE_WAS_NOT_REGISTERED;
      if (clientFrame->device != reinterpret_cast<uint64_t>(remixDevice->GetDXVKDevice()->handle()) ||
          (clientFrame->image && (!clientFrame->width || !clientFrame->height ||
           !clientFrame->sourceReadySemaphore || !clientFrame->sourceReadyValue ||
           clientFrame->vsync > 1 || clientFrame->bottomUp > 1 || clientFrame->allowFrameGeneration > 1))) {
        return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
      }
      if (!clientFrame->image) {
        return SUCCEEDED(presenter->presentClientFrame(*clientFrame, false))
          ? REMIXAPI_ERROR_CODE_SUCCESS : REMIXAPI_ERROR_CODE_GENERAL_FAILURE;
      }
    }
    const bool interop = presenter && presenter->hasInterop();
    if (interop && (!info || (!info->skipVulkanSwapchain && !clientFrame) || info->interopFlipY)) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }
    const bool rtxFramePrepared = interop && presenter->hasPendingInteropFrame();
    HRESULT hr = S_OK;
    if (rtxFramePrepared) hr = presenter->completeInterop(info->clientDoneSemaphore_ValueToWait);
    if (SUCCEEDED(hr)) {
      if (clientFrame && presenter) hr = presenter->presentClientFrame(*clientFrame, rtxFramePrepared);
      else if (!interop) hr = remixDevice->Present(NULL, NULL, info ? info->hwndOverride : NULL, NULL);
    }
    if (FAILED(hr)) {
      return REMIXAPI_ERROR_CODE_GENERAL_FAILURE;
    }

    UINT windowWidth = 0, windowHeight = 0;
    {
      HWND hwnd = info && info->hwndOverride ? info->hwndOverride : remixDevice->GetWindow();
      if (hwnd) {
        RECT hwndRect = {};
        GetClientRect(hwnd, &hwndRect);
        windowWidth = static_cast<UINT>(std::max(0l, hwndRect.right - hwndRect.left));
        windowHeight = static_cast<UINT>(std::max(0l, hwndRect.bottom - hwndRect.top));
      }
    }

    if (windowWidth > 0 && windowHeight > 0) {
      dxvk::Com<IDirect3DSwapChain9> swapchain;
      hr = remixDevice->GetSwapChain(0, &swapchain);
      if (FAILED(hr) || swapchain == nullptr) {
        return REMIXAPI_ERROR_CODE_GENERAL_FAILURE;
      }
      D3DPRESENT_PARAMETERS presentParams{};
      hr = swapchain->GetPresentParameters(&presentParams);
      if (FAILED(hr)) {
        return REMIXAPI_ERROR_CODE_GENERAL_FAILURE;
      }

      // reset swapchain if window has changed
      if (presentParams.BackBufferWidth != windowWidth || //
          presentParams.BackBufferHeight != windowHeight) {
        presentParams.BackBufferWidth = windowWidth;
        presentParams.BackBufferHeight = windowHeight;
        remixDevice->ResetEx(&presentParams, nullptr);
      }
    }

    return REMIXAPI_ERROR_CODE_SUCCESS;
  }

  template<typename T> struct GuardApi;
  template<typename... Args> struct GuardApi<remixapi_ErrorCode(REMIXAPI_PTR*)(Args...)> {
    template<auto Function> static remixapi_ErrorCode REMIXAPI_CALL invoke(Args... args) noexcept {
      try {
        return Function(args...);
      } catch (const std::exception& error) {
        dxvk::Logger::err(error.what());
        return REMIXAPI_ERROR_CODE_GENERAL_FAILURE;
      } catch (...) {
        return REMIXAPI_ERROR_CODE_GENERAL_FAILURE;
      }
    }
  };

  bool isVersionCompatible(uint64_t userVersion) {
    return userVersion == REMIXAPI_VERSION_MAKE(REMIXAPI_VERSION_MAJOR, REMIXAPI_VERSION_MINOR, REMIXAPI_VERSION_PATCH);
  }
}

extern "C"
{
  REMIXAPI remixapi_ErrorCode REMIXAPI_CALL remixapi_UpdateLight(
    remixapi_LightHandle handle, const remixapi_LightInfo* info) {
    return GuardApi<PFN_remixapi_UpdateLight>::invoke<updateApiLight>(handle, info);
  }

  // HdRemix queues its bound AOV copies before Present so the completion signal includes them.
  REMIXAPI remixapi_ErrorCode REMIXAPI_CALL remixinternal_QueueAovCopy(
    IDirect3DSurface9* destination, remixapi_dxvk_CopyRenderingOutputType type) {
    return copyRenderingOutput(destination, type, true);
  }

  REMIXAPI remixapi_ErrorCode REMIXAPI_CALL remixinternal_SetAovRequests(uint32_t raw, uint32_t coverage) {
    static_assert(kRawAovWorldPosition == (1u << REMIXAPI_DXVK_COPY_RENDERING_OUTPUT_TYPE_WORLD_POSITION_RAW));
    static_assert(kRawAovTexcoords == (1u << REMIXAPI_DXVK_COPY_RENDERING_OUTPUT_TYPE_TEXCOORDS_RAW));
    static_assert(kRawAovNormal == (1u << REMIXAPI_DXVK_COPY_RENDERING_OUTPUT_TYPE_NORMAL_F32));
    if ((raw & ~kRawAovAll) || coverage > 1) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }
    std::lock_guard lock { s_mutex };
    auto* device = tryAsDxvk();
    if (!device) {
      return REMIXAPI_ERROR_CODE_REMIX_DEVICE_WAS_NOT_REGISTERED;
    }
    device->EmitCs([raw, coverage](dxvk::DxvkContext* ctx) {
      ctx->getCommonObjects()->getResources().setAovRequests(raw, coverage);
    });
    return REMIXAPI_ERROR_CODE_SUCCESS;
  }

  REMIXAPI remixapi_ErrorCode REMIXAPI_CALL remixapi_InitializeLibrary(const remixapi_InitializeLibraryInfo* info,
                                                                       remixapi_Interface* out_result) {
    if (!info || info->sType != REMIXAPI_STRUCT_TYPE_INITIALIZE_LIBRARY_INFO) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }
    if (!out_result) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }
    if (!isVersionCompatible(info->version)) {
      return REMIXAPI_ERROR_CODE_INCOMPATIBLE_VERSION;
    }
    s_apiVersion = info->version;

    auto interf = remixapi_Interface {};
    {
      interf.Startup = GuardApi<decltype(interf.Startup)>::invoke<remixapi_Startup>;
      interf.Shutdown = GuardApi<decltype(interf.Shutdown)>::invoke<remixapi_Shutdown>;
      interf.Present = GuardApi<decltype(interf.Present)>::invoke<remixapi_Present>;
      interf.CreateMaterial = GuardApi<decltype(interf.CreateMaterial)>::invoke<remixapi_CreateMaterial>;
      interf.DestroyMaterial = GuardApi<decltype(interf.DestroyMaterial)>::invoke<remixapi_DestroyMaterial>;
      interf.CreateMesh = GuardApi<decltype(interf.CreateMesh)>::invoke<remixapi_CreateMesh>;
      interf.DestroyMesh = GuardApi<decltype(interf.DestroyMesh)>::invoke<remixapi_DestroyMesh>;
      interf.SetupCamera = GuardApi<decltype(interf.SetupCamera)>::invoke<remixapi_SetupCamera>;
      interf.SetCameraMediumMaterial = GuardApi<decltype(interf.SetCameraMediumMaterial)>::invoke<remixapi_SetCameraMediumMaterial>;
      interf.CreateTextureFromMemory = GuardApi<decltype(interf.CreateTextureFromMemory)>::invoke<remixapi_CreateTextureFromMemory>;
      interf.CreateTextureFromFile = GuardApi<decltype(interf.CreateTextureFromFile)>::invoke<remixapi_CreateTextureFromFile>;
      interf.DestroyTexture = GuardApi<decltype(interf.DestroyTexture)>::invoke<remixapi_DestroyTexture>;
      interf.UpdateTextureFromMemory = GuardApi<decltype(interf.UpdateTextureFromMemory)>::invoke<remixapi_UpdateTextureFromMemory>;
      interf.Submit = GuardApi<decltype(interf.Submit)>::invoke<remixapi_Submit>;
      interf.dxvk_GetInteropInfo = GuardApi<decltype(interf.dxvk_GetInteropInfo)>::invoke<remixapi_dxvk_GetInteropInfo>;
      interf.dxvk_GetVulkanInteropInfo = GuardApi<decltype(interf.dxvk_GetVulkanInteropInfo)>::invoke<remixapi_dxvk_GetVulkanInteropInfo>;
      interf.dxvk_CreateVulkanDevice = GuardApi<decltype(interf.dxvk_CreateVulkanDevice)>::invoke<remixapi_dxvk_CreateVulkanDevice>;
      interf.dxvk_PrepareVulkan = GuardApi<decltype(interf.dxvk_PrepareVulkan)>::invoke<remixapi_dxvk_PrepareVulkan>;
      interf.dxvk_AdoptVulkanDevice = GuardApi<decltype(interf.dxvk_AdoptVulkanDevice)>::invoke<remixapi_dxvk_AdoptVulkanDevice>;
      interf.CreateSharedBuffer = GuardApi<decltype(interf.CreateSharedBuffer)>::invoke<remixapi_CreateSharedBuffer>;
      interf.CommitSharedBuffers = GuardApi<decltype(interf.CommitSharedBuffers)>::invoke<remixapi_CommitSharedBuffers>;
      interf.SetVoxelCoverage = GuardApi<decltype(interf.SetVoxelCoverage)>::invoke<remixapi_SetVoxelCoverage>;
      interf.DestroySharedBuffer = GuardApi<decltype(interf.DestroySharedBuffer)>::invoke<remixapi_DestroySharedBuffer>;
      interf.AcquireConfigLayer = GuardApi<decltype(interf.AcquireConfigLayer)>::invoke<remixapi_AcquireConfigLayer>;
      interf.SetConfigLayerBlending = GuardApi<decltype(interf.SetConfigLayerBlending)>::invoke<remixapi_SetConfigLayerBlending>;
      interf.ReleaseConfigLayer = GuardApi<decltype(interf.ReleaseConfigLayer)>::invoke<remixapi_ReleaseConfigLayer>;
      interf.GetConfigVariableResolved = GuardApi<decltype(interf.GetConfigVariableResolved)>::invoke<remixapi_GetConfigVariableResolved>;
      interf.SceneInstanceAdd = GuardApi<decltype(interf.SceneInstanceAdd)>::invoke<remixapi_SceneInstanceAdd>;
      interf.SceneInstanceUpdate = GuardApi<decltype(interf.SceneInstanceUpdate)>::invoke<remixapi_SceneInstanceUpdate>;
      interf.SceneInstanceRemove = GuardApi<decltype(interf.SceneInstanceRemove)>::invoke<remixapi_SceneInstanceRemove>;
      interf.UpdateMaterial = GuardApi<decltype(interf.UpdateMaterial)>::invoke<remixapi_UpdateMaterial>;
      interf.DrawInstance = GuardApi<decltype(interf.DrawInstance)>::invoke<remixapi_DrawInstance>;
      interf.CreateLight = GuardApi<decltype(interf.CreateLight)>::invoke<remixapi_CreateLight>;
      interf.DestroyLight = GuardApi<decltype(interf.DestroyLight)>::invoke<remixapi_DestroyLight>;
      interf.DrawLightInstance = GuardApi<decltype(interf.DrawLightInstance)>::invoke<remixapi_DrawLightInstance>;
      interf.SetConfigVariable = GuardApi<decltype(interf.SetConfigVariable)>::invoke<remixapi_SetConfigVariable>;
      interf.dxvk_CreateD3D9 = GuardApi<decltype(interf.dxvk_CreateD3D9)>::invoke<remixapi_dxvk_CreateD3D9_legacy>;
      interf.dxvk_RegisterD3D9Device = GuardApi<decltype(interf.dxvk_RegisterD3D9Device)>::invoke<remixapi_dxvk_RegisterD3D9Device>;
      interf.dxvk_GetExternalSwapchain = GuardApi<decltype(interf.dxvk_GetExternalSwapchain)>::invoke<remixapi_dxvk_GetExternalSwapchain>;
      interf.dxvk_GetVkImage = GuardApi<decltype(interf.dxvk_GetVkImage)>::invoke<remixapi_dxvk_GetVkImage>;
      interf.dxvk_CopyRenderingOutput = GuardApi<decltype(interf.dxvk_CopyRenderingOutput)>::invoke<remixapi_dxvk_CopyRenderingOutput>;
      interf.dxvk_SetDefaultOutput = GuardApi<decltype(interf.dxvk_SetDefaultOutput)>::invoke<remixapi_dxvk_SetDefaultOutput>;
      interf.pick_RequestObjectPicking = GuardApi<decltype(interf.pick_RequestObjectPicking)>::invoke<remixapi_pick_RequestObjectPicking>;
      interf.pick_HighlightObjects = GuardApi<decltype(interf.pick_HighlightObjects)>::invoke<remixapi_pick_HighlightObjects>;
    }
    static_assert(sizeof(interf) == 44 * sizeof(void*), "Add/remove function registration");

    *out_result = interf;
    return REMIXAPI_ERROR_CODE_SUCCESS;
  }

}
