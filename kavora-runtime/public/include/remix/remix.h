/*
 * Copyright (c) 2023-2026, NVIDIA CORPORATION. All rights reserved.
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

#pragma once

#include "remix_c.h"

#include <cstdint>
#include <cstring>
#include <filesystem>
#include <functional>
#include <memory>
#include <utility>
#include <optional>

#ifndef REMIXAPI_ASSERT
#include <cassert>
#define REMIXAPI_ASSERT(mustBeTrue) assert(mustBeTrue)
#endif


namespace remix {
  namespace detail {
#define STATIC_ASSERT_SIZEOF(Type, Expected) static_assert(sizeof(Type) == (Expected), "Remix C ABI size mismatch")

    template< typename T >
    struct Result {
      Result(T&& value)
        : m_status { REMIXAPI_ERROR_CODE_SUCCESS }
        , m_value { std::forward< T >(value) } {
      }

      Result(remixapi_ErrorCode error)
        : m_status { error }
        , m_value {} {
        REMIXAPI_ASSERT(error != REMIXAPI_ERROR_CODE_SUCCESS);
      }

      Result(const Result&) = delete;
      Result(Result&&) = delete;
      Result& operator=(const Result&) = delete;
      Result& operator=(Result&&) = delete;

      operator bool() const {
        return m_status == REMIXAPI_ERROR_CODE_SUCCESS;
      }

      T& value() {
        REMIXAPI_ASSERT(bool { *this });
        return m_value;
      }

      const T& value() const {
        REMIXAPI_ASSERT(bool { *this });
        return m_value;
      }

      remixapi_ErrorCode status() const {
        return m_status;
      }

      const T& operator*() const {
        return value();
      }
      T& operator*() {
        return value();
      }
      const T* operator->() const {
        return &value();
      }
      T* operator->() {
        return &value();
      }

    private:
      const remixapi_ErrorCode m_status;
      T                        m_value;
    };

    template<>
    struct Result< void > {
      Result(remixapi_ErrorCode error) : m_status { error } { }

      Result(const Result&) = delete;
      Result(Result&&) = delete;
      Result& operator=(const Result&) = delete;
      Result& operator=(Result&&) = delete;

      operator bool() const {
        return m_status == REMIXAPI_ERROR_CODE_SUCCESS;
      }

      remixapi_ErrorCode status() const {
        return m_status;
      }

    private:
      const remixapi_ErrorCode m_status;
    };

    template< typename T >
    struct Span
    {
        const T* values{ nullptr };
        uint32_t count{ 0 };
    };

    template< typename T >
    void assign_if(remixapi_Bool& hasvalue, T& value, const std::optional< T >& src) {
      if (src) {
        hasvalue = true;
        value = src.value();
      } else {
        hasvalue = false;
      }
    }
  }

  template< typename T >
  using Result = detail::Result< T >;

  template< typename T >
  using Span = detail::Span< T >;

  using StructType = remixapi_StructType;
  using Rect2D = remixapi_Rect2D;
  using Float2D = remixapi_Float2D;
  using Float3D = remixapi_Float3D;
  using Float4D = remixapi_Float4D;
  using Transform = remixapi_Transform;



  struct MaterialInfo;
  struct MeshInfo;
  struct CameraInfo;
  struct InstanceInfo;
  struct LightInfo;
  namespace detail {
    struct dxvk_ExternalSwapchain;
    struct dxvk_VkImage;
  }

  using TextureFormat = remixapi_TextureFormat;
  using TexturePurpose = remixapi_TexturePurpose;
  using TexturePatch = remixapi_TexturePatch;
  using TextureBatchEntry = remixapi_TextureBatchEntry;
  using MeshInfoSurfaceTriangles = remixapi_MeshInfoSurfaceTriangles;
  using MeshInfoSkinning = remixapi_MeshInfoSkinning;
  using HardcodedVertex = remixapi_HardcodedVertex;
  using VulkanInteropImage = remixapi_VulkanInteropImage;
  using dxvk_InteropInfo = remixapi_dxvk_InteropInfo;

  struct StartupInfo : remixapi_StartupInfo {
    StartupInfo() : remixapi_StartupInfo{} {
      sType = REMIXAPI_STRUCT_TYPE_STARTUP_INFO;
    }
  };

  struct SubmitInfo : remixapi_SubmitInfo {
    SubmitInfo() : remixapi_SubmitInfo{} {
      sType = REMIXAPI_STRUCT_TYPE_SUBMIT_INFO;
    }
  };

  struct PresentInfo : remixapi_PresentInfo {
    PresentInfo() : remixapi_PresentInfo{} {
      sType = REMIXAPI_STRUCT_TYPE_PRESENT_INFO;
    }
  };

  struct TextureFromMemoryInfo : remixapi_TextureFromMemoryInfo {
    TextureFromMemoryInfo() : remixapi_TextureFromMemoryInfo{} {
      sType = REMIXAPI_STRUCT_TYPE_TEXTURE_FROM_MEMORY_INFO;
    }
  };

  struct TexturePatchInfo : remixapi_TexturePatchInfo {
    TexturePatchInfo() : remixapi_TexturePatchInfo{} {
      sType = REMIXAPI_STRUCT_TYPE_TEXTURE_PATCH_INFO;
    }
  };

  struct ConfigVariableResolvedInfo : remixapi_ConfigVariableResolvedInfo {
    ConfigVariableResolvedInfo() : remixapi_ConfigVariableResolvedInfo{} {
      sType = REMIXAPI_STRUCT_TYPE_CONFIG_VARIABLE_RESOLVED_INFO;
    }
  };

  struct CameraMediumInfo : remixapi_CameraMediumInfo {
    CameraMediumInfo() : remixapi_CameraMediumInfo{} {
      sType = REMIXAPI_STRUCT_TYPE_CAMERA_MEDIUM_INFO;
    }
  };

  struct InteropFrameStatusEXT : remixapi_InteropFrameStatusEXT {
    InteropFrameStatusEXT() : remixapi_InteropFrameStatusEXT{} {
      sType = REMIXAPI_STRUCT_TYPE_INTEROP_FRAME_STATUS_EXT;
    }
  };

  struct VulkanInteropInfo : remixapi_VulkanInteropInfo {
    VulkanInteropInfo() : remixapi_VulkanInteropInfo{} {
      sType = REMIXAPI_STRUCT_TYPE_VULKAN_INTEROP_INFO;
    }
  };

  struct Interface {
    remixapi_HMODULE   m_RemixDLL { nullptr };
    remixapi_Interface m_CInterface {};

    // Functions
    Result< void >                    Startup(const remixapi_StartupInfo& info);
    Result< void >                    Shutdown();
    Result< void >                    Present(const remixapi_PresentInfo* info = nullptr);
    Result< remixapi_MaterialHandle > CreateMaterial(const remixapi_MaterialInfo& info);
    Result< void >                    DestroyMaterial(remixapi_MaterialHandle handle);
    Result< remixapi_MeshHandle >     CreateMesh(const remixapi_MeshInfo& info);
    Result< void >                    DestroyMesh(remixapi_MeshHandle handle);
    Result< void >                    SetupCamera(const remixapi_CameraInfo& info);
    Result< void >                    SetCameraMediumMaterial(remixapi_MaterialHandle medium);
    Result< void >                    DrawInstance(const remixapi_InstanceInfo& info);
    Result< remixapi_LightHandle >    CreateLight(const remixapi_LightInfo& info);
    Result< void >                    DestroyLight(remixapi_LightHandle handle);
    Result< void >                    DrawLightInstance(remixapi_LightHandle handle);
    Result< void >                    SetConfigVariable(const char* key, const char* value);

    Result< void >                    Submit(const remixapi_SubmitInfo& info);
    Result< void >                    UpdateMaterial(remixapi_MaterialHandle handle, const remixapi_MaterialInfo& info);
    Result< remixapi_TextureHandle >  CreateTextureFromMemory(const remixapi_TextureFromMemoryInfo& info);
    Result< remixapi_TextureHandle >  CreateTextureFromFile(remixapi_Path filePath);
    Result< void >                    DestroyTexture(remixapi_TextureHandle handle);
    Result< void >                    UpdateTextureFromMemory(const remixapi_TexturePatchInfo& info);
    Result< remixapi_SceneInstanceHandle > SceneInstanceAdd(const remixapi_InstanceInfo& info);
    Result< void >                    SceneInstanceUpdate(remixapi_SceneInstanceHandle handle, const remixapi_InstanceInfo& info);
    Result< void >                    SceneInstanceRemove(remixapi_SceneInstanceHandle handle);
    Result< void >                    GetConfigVariableResolved(remixapi_ConfigVariableResolvedInfo& info);
    Result< remixapi_ConfigLayerHandle > AcquireConfigLayer(const char* path, float priority, float blendThreshold);
    Result< void >                    SetConfigLayerBlending(remixapi_ConfigLayerHandle handle, float strength, float blendThreshold);
    Result< void >                    ReleaseConfigLayer(remixapi_ConfigLayerHandle handle);
    Result< remixapi_dxvk_InteropInfo > dxvk_GetInteropInfo();
    Result< remixapi_VulkanInteropInfo > dxvk_GetVulkanInteropInfo(remixapi_InteropFrameStatusEXT* frameStatus = nullptr);

    // DXVK interoperability
    Result< IDirect3D9Ex* >                  dxvk_CreateD3D9(bool editorModeEnabled = false);
    Result< void >                           dxvk_RegisterD3D9Device(IDirect3DDevice9Ex* d3d9Device);
    Result< detail::dxvk_ExternalSwapchain > dxvk_GetExternalSwapchain();
    Result< detail::dxvk_VkImage >           dxvk_GetVkImage(IDirect3DSurface9* source);
    Result< void >                           dxvk_CopyRenderingOutput(IDirect3DSurface9* destination,
                                                                      remixapi_dxvk_CopyRenderingOutputType type);
    Result< void >                           dxvk_SetDefaultOutput(remixapi_dxvk_CopyRenderingOutputType type,
                                                                   const remixapi_Float4D& color);
    // Object picking utils
    template< typename CallbackLambda > // void( remix::Span<uint32_t> objectPickingValues )
    Result< void >                           pick_RequestObjectPicking(const Rect2D& region, CallbackLambda &&callback);
    Result< void >                           pick_HighlightObjects(const uint32_t* objectPickingValues_values,
                                                                   uint32_t objectPickingValues_count,
                                                                   uint8_t colorR, uint8_t colorG, uint8_t colorB);
  };

#ifndef REMIX_WINAPI_NO_LIBRARY_LOADER
  namespace lib {
    // Helper function to load a .dll of Remix, and initialize it.
    // pRemixD3D9DllPath is a path to .dll file, e.g. "C:\dxvk-remix-nv\public\bin\d3d9.dll"
    [[nodiscard]] inline Result< Interface > loadRemixDllAndInitialize(const std::filesystem::path& remixD3D9DllPath) {

      remixapi_Interface interfaceInC = {};
      remixapi_HMODULE remixDll = nullptr;

      remixapi_ErrorCode status =
        remixapi_lib_loadRemixDllAndInitialize(remixD3D9DllPath.c_str(),
                                               &interfaceInC,
                                               &remixDll);
      if (status != REMIXAPI_ERROR_CODE_SUCCESS) {
        REMIXAPI_ASSERT(remixDll == nullptr);
        return status;
      }

      static_assert(sizeof(remixapi_Interface) == 37 * sizeof(void*),
                    "Change version, update C++ wrapper when adding new functions");

      remix::Interface interfaceInCpp = {};
      {
        interfaceInCpp.m_RemixDLL = remixDll;
        interfaceInCpp.m_CInterface = interfaceInC;
      }
      static_assert(sizeof(remix::Interface) ==
                    sizeof(interfaceInCpp.m_RemixDLL) + sizeof(interfaceInCpp.m_CInterface),
                    "Not all members of \'interfaceInCpp\' are set here");

      return interfaceInCpp;
    }

    inline Result<void> shutdownAndUnloadRemixDll(Interface& interfaceInCpp) {
      return remixapi_lib_shutdownAndUnloadRemixDll(&interfaceInCpp.m_CInterface, interfaceInCpp.m_RemixDLL);
    }
  }
#endif // !REMIX_WINAPI_NO_LIBRARY_LOADER



  inline Result< void > Interface::Startup(const remixapi_StartupInfo& info) {
    if (!m_CInterface.Startup) {
      return REMIXAPI_ERROR_CODE_NOT_INITIALIZED;
    }
    return m_CInterface.Startup(&info);
  }

  inline Result< void > Interface::Shutdown() {
    if (!m_CInterface.Shutdown) {
      return REMIXAPI_ERROR_CODE_NOT_INITIALIZED;
    }
    return m_CInterface.Shutdown();
  }

  inline Result< void > Interface::SetConfigVariable(const char* key, const char* value) {
    if (!m_CInterface.SetConfigVariable) {
      return REMIXAPI_ERROR_CODE_NOT_INITIALIZED;
    }
    return m_CInterface.SetConfigVariable(key, value);
  }

  inline Result< void > Interface::Present(const remixapi_PresentInfo* info) {
    if (!m_CInterface.Present) {
      return REMIXAPI_ERROR_CODE_NOT_INITIALIZED;
    }
    return m_CInterface.Present(info);
  }



  inline Result< void > Interface::Submit(const remixapi_SubmitInfo& info) {
    return m_CInterface.Submit(&info);
  }

  inline Result< void > Interface::UpdateMaterial(remixapi_MaterialHandle handle, const remixapi_MaterialInfo& info) {
    return m_CInterface.UpdateMaterial(handle, &info);
  }

  inline Result< void > Interface::DestroyTexture(remixapi_TextureHandle handle) {
    return m_CInterface.DestroyTexture(handle);
  }

  inline Result< void > Interface::UpdateTextureFromMemory(const remixapi_TexturePatchInfo& info) {
    return m_CInterface.UpdateTextureFromMemory(&info);
  }

  inline Result< void > Interface::SceneInstanceUpdate(remixapi_SceneInstanceHandle handle, const remixapi_InstanceInfo& info) {
    return m_CInterface.SceneInstanceUpdate(handle, &info);
  }

  inline Result< void > Interface::SceneInstanceRemove(remixapi_SceneInstanceHandle handle) {
    return m_CInterface.SceneInstanceRemove(handle);
  }

  inline Result< void > Interface::GetConfigVariableResolved(remixapi_ConfigVariableResolvedInfo& info) {
    return m_CInterface.GetConfigVariableResolved(&info);
  }

  inline Result< void > Interface::SetConfigLayerBlending(remixapi_ConfigLayerHandle handle, float strength, float blendThreshold) {
    return m_CInterface.SetConfigLayerBlending(handle, strength, blendThreshold);
  }

  inline Result< void > Interface::ReleaseConfigLayer(remixapi_ConfigLayerHandle handle) {
    return m_CInterface.ReleaseConfigLayer(handle);
  }

  inline Result< remixapi_TextureHandle > Interface::CreateTextureFromMemory(const remixapi_TextureFromMemoryInfo& info) {
    remixapi_TextureHandle handle = nullptr;
    remixapi_ErrorCode status = m_CInterface.CreateTextureFromMemory(&info, &handle);
    if (status != REMIXAPI_ERROR_CODE_SUCCESS) {
      return status;
    }
    return handle;
  }

  inline Result< remixapi_TextureHandle > Interface::CreateTextureFromFile(remixapi_Path filePath) {
    remixapi_TextureHandle handle = nullptr;
    remixapi_ErrorCode status = m_CInterface.CreateTextureFromFile(filePath, &handle);
    if (status != REMIXAPI_ERROR_CODE_SUCCESS) {
      return status;
    }
    return handle;
  }

  inline Result< remixapi_SceneInstanceHandle > Interface::SceneInstanceAdd(const remixapi_InstanceInfo& info) {
    remixapi_SceneInstanceHandle handle = nullptr;
    remixapi_ErrorCode status = m_CInterface.SceneInstanceAdd(&info, &handle);
    if (status != REMIXAPI_ERROR_CODE_SUCCESS) {
      return status;
    }
    return handle;
  }

  inline Result< remixapi_ConfigLayerHandle > Interface::AcquireConfigLayer(const char* path, float priority, float blendThreshold) {
    remixapi_ConfigLayerHandle handle = nullptr;
    remixapi_ErrorCode status = m_CInterface.AcquireConfigLayer(path, priority, blendThreshold, &handle);
    if (status != REMIXAPI_ERROR_CODE_SUCCESS) {
      return status;
    }
    return handle;
  }

  inline Result< remixapi_dxvk_InteropInfo > Interface::dxvk_GetInteropInfo() {
    remixapi_dxvk_InteropInfo info {};
    remixapi_ErrorCode status = m_CInterface.dxvk_GetInteropInfo(&info);
    if (status != REMIXAPI_ERROR_CODE_SUCCESS) {
      return status;
    }
    return info;
  }

  inline Result< remixapi_VulkanInteropInfo > Interface::dxvk_GetVulkanInteropInfo(remixapi_InteropFrameStatusEXT* frameStatus) {
    remixapi_VulkanInteropInfo info {};
    info.sType = REMIXAPI_STRUCT_TYPE_VULKAN_INTEROP_INFO;
    info.pNext = frameStatus;
    remixapi_ErrorCode status = m_CInterface.dxvk_GetVulkanInteropInfo(&info);
    if (status != REMIXAPI_ERROR_CODE_SUCCESS) {
      return status;
    }
    return info;
  }

  struct MaterialInfoOpaqueEXT : remixapi_MaterialInfoOpaqueEXT {
    MaterialInfoOpaqueEXT() : remixapi_MaterialInfoOpaqueEXT{} {
      sType = REMIXAPI_STRUCT_TYPE_MATERIAL_INFO_OPAQUE_EXT;
      pNext = nullptr;
      roughnessTexture = {};
      metallicTexture = {};
      anisotropy = 0.0f;
      albedoConstant = { 0.2f, 0.2f, 0.2f };
      opacityConstant = 1.0f;
      roughnessConstant = 0.5f;
      metallicConstant = 0.0f;
      thinFilmThickness_hasvalue = false;
      thinFilmThickness_value = 200.f;
      alphaIsThinFilmThickness = false;
      heightTexture = {};
      displaceIn = 0.0f;
      useDrawCallAlphaState = true;
      blendType_hasvalue = false;
      blendType_value = 0;
      invertedBlend = false;
      alphaTestType = 7;
      alphaReferenceValue = 0;
      displaceOut = 0.0f;
      enableDlssControlMask = true;
      dlssControlMaskIntensity = 1.0f;
      dlssControlMaskToneStrength = 0.3f;
      dlssControlMaskStructuralStrength = 0.7f;
      STATIC_ASSERT_SIZEOF(remixapi_MaterialInfoOpaqueEXT, 128);

    }

    void set_roughnessTexture(remixapi_TextureHandle texture) {
      roughnessTexture = texture;
    }

    void set_metallicTexture(remixapi_TextureHandle texture) {
      metallicTexture = texture;
    }

    void set_heightTexture(remixapi_TextureHandle texture) {
      heightTexture = texture;
    }

    void set_thinFilmThickness(const std::optional< float >& v) {
      detail::assign_if(thinFilmThickness_hasvalue, thinFilmThickness_value, v);
    }

    void set_blendType(const std::optional< int >& v) {
      detail::assign_if(blendType_hasvalue, blendType_value, v);
    }
  };

  // Can be linked to MaterialInfoOpaqueEXT
  struct MaterialInfoOpaqueSubsurfaceEXT : remixapi_MaterialInfoOpaqueSubsurfaceEXT {
    MaterialInfoOpaqueSubsurfaceEXT() : remixapi_MaterialInfoOpaqueSubsurfaceEXT{} {
      sType = REMIXAPI_STRUCT_TYPE_MATERIAL_INFO_OPAQUE_SUBSURFACE_EXT;
      pNext = nullptr;
      subsurfaceTransmittanceTexture = {};
      subsurfaceThicknessTexture = {};
      subsurfaceSingleScatteringAlbedoTexture = {};
      subsurfaceTransmittanceColor = { 0.5f, 0.5f, 0.5f };
      subsurfaceMeasurementDistance = 0.0f;
      subsurfaceSingleScatteringAlbedo = { 0.5f, 0.5f, 0.5f };
      subsurfaceVolumetricAnisotropy = 0.0f;
      subsurfaceDiffusionProfile = false;
      subsurfaceRadius = { 0.5f, 0.5f, 0.5f };
      subsurfaceRadiusScale = 0.0f;
      subsurfaceMaxSampleRadius = 0.0f;
      subsurfaceRadiusTexture = {};
      STATIC_ASSERT_SIZEOF(remixapi_MaterialInfoOpaqueSubsurfaceEXT, 104);

    }

    void set_subsurfaceTransmittanceTexture(remixapi_TextureHandle texture) {
      subsurfaceTransmittanceTexture = texture;
    }

    void set_subsurfaceThicknessTexture(remixapi_TextureHandle texture) {
      subsurfaceThicknessTexture = texture;
    }

    void set_subsurfaceSingleScatteringAlbedoTexture(remixapi_TextureHandle texture) {
      subsurfaceSingleScatteringAlbedoTexture = texture;
    }

    void set_subsurfaceRadiusTexture(remixapi_TextureHandle texture) {
      subsurfaceRadiusTexture = texture;
    }
  };

  struct MaterialInfoTranslucentEXT : remixapi_MaterialInfoTranslucentEXT {
    MaterialInfoTranslucentEXT() : remixapi_MaterialInfoTranslucentEXT{} {
      sType = REMIXAPI_STRUCT_TYPE_MATERIAL_INFO_TRANSLUCENT_EXT;
      pNext = nullptr;
      transmittanceTexture = {};
      refractiveIndex = 1.3f;
      transmittanceColor = { 0.97f, 0.97f, 0.97f };
      transmittanceMeasurementDistance = 1.0f;
      thinWallThickness_hasvalue = false;
      thinWallThickness_value = 0.001f;
      useDiffuseLayer = false;
      STATIC_ASSERT_SIZEOF(remixapi_MaterialInfoTranslucentEXT, 56);

    }

    void set_transmittanceTexture(remixapi_TextureHandle texture) {
      transmittanceTexture = texture;
    }

    void set_thinWallThickness(const std::optional< float >& v) {
      detail::assign_if(thinWallThickness_hasvalue, thinWallThickness_value, v);
    }
  };

  struct MaterialInfoPortalEXT : remixapi_MaterialInfoPortalEXT {
    MaterialInfoPortalEXT() : remixapi_MaterialInfoPortalEXT{} {
      sType = REMIXAPI_STRUCT_TYPE_MATERIAL_INFO_PORTAL_EXT;
      pNext = nullptr;
      rayPortalIndex = 0;
      rotationSpeed = 0.0f;
      STATIC_ASSERT_SIZEOF(remixapi_MaterialInfoPortalEXT, 24);
    }
  };

  struct MaterialInfo : remixapi_MaterialInfo {
    MaterialInfo() : remixapi_MaterialInfo{} {
      sType = REMIXAPI_STRUCT_TYPE_MATERIAL_INFO;
      pNext = nullptr;
      hash = 0;
      albedoTexture = {};
      normalTexture = {};
      tangentTexture = {};
      emissiveTexture = {};
      emissiveIntensity = 40.0f;
      emissiveColorConstant = { 0.0f, 0.0f, 0.0f };
      spriteSheetRow = 1;
      spriteSheetCol = 1;
      spriteSheetFps = 0;
      filterMode = 1; // Linear
      wrapModeU = 1; // Repeat
      wrapModeV = 1; // Repeat
      STATIC_ASSERT_SIZEOF(remixapi_MaterialInfo, 80);

    }

    void set_albedoTexture(remixapi_TextureHandle texture) {
      albedoTexture = texture;
    }

    void set_normalTexture(remixapi_TextureHandle texture) {
      normalTexture = texture;
    }

    void set_tangentTexture(remixapi_TextureHandle texture) {
      tangentTexture = texture;
    }

    void set_emissiveTexture(remixapi_TextureHandle texture) {
      emissiveTexture = texture;
    }
  };

  inline Result< remixapi_MaterialHandle > Interface::CreateMaterial(const remixapi_MaterialInfo& info) {
    remixapi_MaterialHandle handle = nullptr;
    remixapi_ErrorCode status = m_CInterface.CreateMaterial(&info, &handle);
    if (status != REMIXAPI_ERROR_CODE_SUCCESS) {
      return status;
    }
    return handle;
  }

  inline Result< void > Interface::DestroyMaterial(remixapi_MaterialHandle handle) {
    return m_CInterface.DestroyMaterial(handle);
  }



  struct MeshInfo : remixapi_MeshInfo {
    MeshInfo() : remixapi_MeshInfo{} {
      sType = REMIXAPI_STRUCT_TYPE_MESH_INFO;
      pNext = nullptr;
      hash = 0;
      surfaces_values = {};
      surfaces_count = 0;
      STATIC_ASSERT_SIZEOF(remixapi_MeshInfo, 40);
    }
  };

  inline Result< remixapi_MeshHandle > Interface::CreateMesh(const remixapi_MeshInfo& info) {
    remixapi_MeshHandle handle = nullptr;
    remixapi_ErrorCode status = m_CInterface.CreateMesh(&info, &handle);
    if (status != REMIXAPI_ERROR_CODE_SUCCESS) {
      return status;
    }
    return handle;
  }

  inline Result< void > Interface::DestroyMesh(remixapi_MeshHandle handle) {
    return m_CInterface.DestroyMesh(handle);
  }



  using CameraType = remixapi_CameraType;

  // Ignores view / projection matrices from CameraInfo
  // by recalculating them from the given arguments in this struct.
  struct CameraInfoParameterizedEXT : remixapi_CameraInfoParameterizedEXT {
    CameraInfoParameterizedEXT() : remixapi_CameraInfoParameterizedEXT{} {
      sType = { REMIXAPI_STRUCT_TYPE_CAMERA_INFO_PARAMETERIZED_EXT };
      pNext = { nullptr };
      position = { 0, 0, 0 };
      forward = { 0, 0, 1 };
      up = { 0, 1, 0 };
      right = { 1, 0, 0 };
      fovYInDegrees = 75.f;
      aspect = 16.f / 9.f;
      nearPlane = 0.1f;
      farPlane = 1000.f;
      STATIC_ASSERT_SIZEOF(remixapi_CameraInfoParameterizedEXT, 80);
    }
  };

  struct CameraInfo : remixapi_CameraInfo {
    CameraInfo() : remixapi_CameraInfo{} {
      sType = { REMIXAPI_STRUCT_TYPE_CAMERA_INFO };
      pNext = { nullptr };
      type = { REMIXAPI_CAMERA_TYPE_WORLD };
      view[0][0] = view[1][1] = view[2][2] = view[3][3] = 1.f;
      projection[0][0] = projection[1][1] = projection[2][2] = projection[3][3] = 1.f;
      STATIC_ASSERT_SIZEOF(remixapi_CameraInfo, 152);
    }
  };

  inline Result< void > Interface::SetupCamera(const remixapi_CameraInfo& info) {
    return m_CInterface.SetupCamera(&info);
  }

  inline Result< void > Interface::SetCameraMediumMaterial(remixapi_MaterialHandle medium) {
    if (!m_CInterface.SetCameraMediumMaterial) {
      return REMIXAPI_ERROR_CODE_NOT_INITIALIZED;
    }

    remixapi_CameraMediumInfo info {};
    info.sType = REMIXAPI_STRUCT_TYPE_CAMERA_MEDIUM_INFO;
    info.pNext = nullptr;
    info.medium = medium;
    return m_CInterface.SetCameraMediumMaterial(&info);
  }



  struct InstanceInfoBoneTransformsEXT : remixapi_InstanceInfoBoneTransformsEXT {
    InstanceInfoBoneTransformsEXT() : remixapi_InstanceInfoBoneTransformsEXT{} {
      sType = REMIXAPI_STRUCT_TYPE_INSTANCE_INFO_BONE_TRANSFORMS_EXT;
      pNext = nullptr;
      boneTransforms_count = 0;
      boneTransforms_values = {};
      STATIC_ASSERT_SIZEOF(remixapi_InstanceInfoBoneTransformsEXT, 32);
    }
  };

  struct InstanceInfoBlendEXT : remixapi_InstanceInfoBlendEXT {
    InstanceInfoBlendEXT() : remixapi_InstanceInfoBlendEXT{} {
      sType = REMIXAPI_STRUCT_TYPE_INSTANCE_INFO_BLEND_EXT;
      pNext = nullptr;
      alphaTestEnabled = false;
      alphaTestReferenceValue = 0;
      alphaTestCompareOp = 7 /* VK_COMPARE_OP_ALWAYS */;
      alphaBlendEnabled = false;
      srcColorBlendFactor = 1 /* VK_BLEND_FACTOR_ONE */;
      dstColorBlendFactor = 0 /* VK_BLEND_FACTOR_ZERO */;
      colorBlendOp = 0 /* VK_BLEND_OP_ADD */;
      srcAlphaBlendFactor = 1 /* VK_BLEND_FACTOR_ONE */;
      dstAlphaBlendFactor = 0 /* VK_BLEND_FACTOR_ZERO */;
      alphaBlendOp = 0 /* VK_BLEND_OP_ADD */;
      writeMask = 0xFFFFFFFF;
      textureColorArg1Source = 1 /* RtTextureArgSource::Texture */;
      textureColorArg2Source = 0 /* RtTextureArgSource::None */;
      textureColorOperation = 3 /* DxvkRtTextureOperation::Modulate */;
      textureAlphaArg1Source = 1 /* RtTextureArgSource::Texture */;
      textureAlphaArg2Source = 0 /* RtTextureArgSource::None */;
      textureAlphaOperation = 1 /* DxvkRtTextureOperation::SelectArg1 */;
      tFactor = 0XFFFFFFFF;
      isTextureFactorBlend = false;
      isVertexColorBakedLighting = true;
      STATIC_ASSERT_SIZEOF(remixapi_InstanceInfoBlendEXT, 96);
    }
  };

  struct InstanceInfoObjectPickingEXT : remixapi_InstanceInfoObjectPickingEXT {
    InstanceInfoObjectPickingEXT() : remixapi_InstanceInfoObjectPickingEXT{} {
      sType = REMIXAPI_STRUCT_TYPE_INSTANCE_INFO_OBJECT_PICKING_EXT;
      pNext = nullptr;
      objectPickingValue = 0;
    }
  };

  struct InstanceInfoParticleSystemEXT : remixapi_InstanceInfoParticleSystemEXT {
    InstanceInfoParticleSystemEXT() : remixapi_InstanceInfoParticleSystemEXT{} {
      sType = REMIXAPI_STRUCT_TYPE_INSTANCE_INFO_PARTICLE_SYSTEM_EXT;
      pNext = nullptr;
      maxNumParticles = 10000;
      useTurbulence = false;
      alignParticlesToVelocity = false;
      useSpawnTexcoords = false;
      enableCollisionDetection = false;
      enableMotionTrail = false;
      hideEmitter = false;
      restrictVelocityX = false;
      restrictVelocityY = false;
      restrictVelocityZ = false;
      minColor = { nullptr, 0 };
      maxColor = { nullptr, 0 };
      minRotationSpeed = { nullptr, 0 };
      maxRotationSpeed = { nullptr, 0 };
      minSize = { nullptr, 0 };
      maxSize = { nullptr, 0 };
      maxVelocity = { nullptr, 0 };
      attractorPosition = { 0, 0, 0 };
      minTimeToLive = 1.0f;
      maxTimeToLive = 1.0f;
      initialVelocityFromNormal = 0.0f;
      initialVelocityConeAngleDegrees = 0.0f;
      dragCoefficient = 0.0f;
      initialRotationDeviationDegrees = 0.0f;
      gravityForce = -0.98f;
      turbulenceFrequency = 0.05f;
      turbulenceForce = 5.0f;
      spawnRatePerSecond = 0.f;
      collisionThickness = 5.0f;
      collisionRestitution = 0.5f;
      motionTrailMultiplier = 1.0f;
      initialVelocityFromMotion = 0.0f;
      spawnBurstDuration = 0.0f;
      attractorRadius = 0.0f;
      attractorForce = 0.0f;
      billboardType = 0;
      spriteSheetMode = 0;
      collisionMode = 0;
      randomFlipAxis = 0;
    }
  };

  struct InstanceInfoGpuInstancingEXT : remixapi_InstanceInfoGpuInstancingEXT {
    InstanceInfoGpuInstancingEXT() : remixapi_InstanceInfoGpuInstancingEXT{} {
      sType = REMIXAPI_STRUCT_TYPE_INSTANCE_INFO_GPU_INSTANCING_EXT;
      pNext = nullptr;
      instanceTransforms_values = nullptr;
      instanceTransforms_count = 0;
      static_assert(sizeof remixapi_InstanceInfoGpuInstancingEXT == 32);
    }
  };

  using InstanceCategoryBit = remixapi_InstanceCategoryBit;
  using InstanceCategoryFlags = remixapi_InstanceCategoryFlags;

  struct InstanceInfo : remixapi_InstanceInfo {
    InstanceInfo() : remixapi_InstanceInfo{} {
      sType = REMIXAPI_STRUCT_TYPE_INSTANCE_INFO;
      pNext = nullptr;
      categoryFlags = 0;
      mesh = 0;
      transform = {};
      doubleSided = false;
      STATIC_ASSERT_SIZEOF(remixapi_InstanceInfo, 88);
    }
  };

  inline Result< void > Interface::DrawInstance(const remixapi_InstanceInfo& info) {
    return m_CInterface.DrawInstance(&info);
  }



  namespace detail {
    inline remixapi_LightInfoLightShaping defaultLightShaping() {
      remixapi_LightInfoLightShaping shaping {};
      {
        shaping.direction = { 0.0f, 0.0f, 1.0f };
        shaping.coneAngleDegrees = 180.0f;
        shaping.coneSoftness = 0.0f;
        shaping.focusExponent = 0.0f;
      }
      return shaping;
    };
  }

  using LightInfoLightShaping = remixapi_LightInfoLightShaping;

  struct LightInfoSphereEXT : remixapi_LightInfoSphereEXT {
    LightInfoSphereEXT() : remixapi_LightInfoSphereEXT{} {
      sType = REMIXAPI_STRUCT_TYPE_LIGHT_INFO_SPHERE_EXT;
      pNext = nullptr;
      position = { 0.0f, 0.0f, 0.0f };
      radius = 0.05f;
      shaping_hasvalue = false;
      shaping_value = detail::defaultLightShaping();
      volumetricRadianceScale = 1.0f;
      STATIC_ASSERT_SIZEOF(remixapi_LightInfoSphereEXT, 64);
    }

    void set_shaping(const std::optional< remixapi_LightInfoLightShaping >& v) {
      detail::assign_if(shaping_hasvalue, shaping_value, v);
    }
  };

  struct LightInfoRectEXT : remixapi_LightInfoRectEXT {
    LightInfoRectEXT() : remixapi_LightInfoRectEXT{} {
      sType = REMIXAPI_STRUCT_TYPE_LIGHT_INFO_RECT_EXT;
      pNext = nullptr;
      position = { 0.0f, 0.0f, 0.0f };
      xAxis = { 1.0f, 0.0f, 0.0f };
      xSize = 1.0f;
      yAxis = { 0.0f, 1.0f, 0.0f };
      ySize = 1.0f;
      direction = { 0.0f, 0.0f, 1.0f };
      shaping_hasvalue = false;
      shaping_value = detail::defaultLightShaping();
      volumetricRadianceScale = 1.0f;
      STATIC_ASSERT_SIZEOF(remixapi_LightInfoRectEXT, 104);
    }

    void set_shaping(const std::optional< remixapi_LightInfoLightShaping >& v) {
      detail::assign_if(shaping_hasvalue, shaping_value, v);
    }
  };

  struct LightInfoDiskEXT : remixapi_LightInfoDiskEXT {
    LightInfoDiskEXT() : remixapi_LightInfoDiskEXT{} {
      sType = REMIXAPI_STRUCT_TYPE_LIGHT_INFO_DISK_EXT;
      pNext = nullptr;
      position = { 0.0f, 0.0f, 0.0f };
      xAxis = { 1.0f, 0.0f, 0.0f };
      xRadius = 1.0f;
      yAxis = { 0.0f, 1.0f, 0.0f };
      yRadius = 1.0f;
      direction = { 0.0f, 0.0f, 1.0f };
      shaping_hasvalue = false;
      shaping_value = detail::defaultLightShaping();
      volumetricRadianceScale = 1.0f;
      STATIC_ASSERT_SIZEOF(remixapi_LightInfoDiskEXT, 104);
    }

    void set_shaping(const std::optional< remixapi_LightInfoLightShaping >& v) {
      detail::assign_if(shaping_hasvalue, shaping_value, v);
    }
  };

  struct LightInfoCylinderEXT : remixapi_LightInfoCylinderEXT {
    LightInfoCylinderEXT() : remixapi_LightInfoCylinderEXT{} {
      sType = REMIXAPI_STRUCT_TYPE_LIGHT_INFO_CYLINDER_EXT;
      pNext = nullptr;
      position = { 0.0f, 0.0f, 0.0f };
      radius = 1.0f;
      axis = { 1.0f, 0.0f, 0.0f };
      axisLength = 1.0f;
      volumetricRadianceScale = 1.0f;
      STATIC_ASSERT_SIZEOF(remixapi_LightInfoCylinderEXT, 56);
    }
  };

  struct LightInfoDistantEXT : remixapi_LightInfoDistantEXT {
    LightInfoDistantEXT() : remixapi_LightInfoDistantEXT{} {
      sType = REMIXAPI_STRUCT_TYPE_LIGHT_INFO_DISTANT_EXT;
      pNext = nullptr;
      direction = { 0.0f, -1.0f, 0.0f };
      angularDiameterDegrees = 0.5f;
      volumetricRadianceScale = 1.0f;
      STATIC_ASSERT_SIZEOF(remixapi_LightInfoDistantEXT, 40);
    }
  };

  struct LightInfoDomeEXT : remixapi_LightInfoDomeEXT {
    LightInfoDomeEXT() : remixapi_LightInfoDomeEXT{} {
      sType = REMIXAPI_STRUCT_TYPE_LIGHT_INFO_DOME_EXT;
      pNext = nullptr;
      transform = {};
      colorTexture = {};
      opacity = 1.0f;
      blendType = 0;
      STATIC_ASSERT_SIZEOF(remixapi_LightInfoDomeEXT, 80);

    }

    void set_colorTexture(remixapi_TextureHandle texture) {
      colorTexture = texture;
    }
  };

  struct LightInfo : remixapi_LightInfo {
    LightInfo() : remixapi_LightInfo{} {
      sType = REMIXAPI_STRUCT_TYPE_LIGHT_INFO;
      pNext = nullptr;
      hash = 0;
      radiance = { 1.0f, 1.0f, 1.0f };
      STATIC_ASSERT_SIZEOF(remixapi_LightInfo, 40);
    }
  };

  inline Result< remixapi_LightHandle > Interface::CreateLight(const remixapi_LightInfo& info) {
    remixapi_LightHandle handle = nullptr;
    remixapi_ErrorCode status = m_CInterface.CreateLight(&info, &handle);
    if (status != REMIXAPI_ERROR_CODE_SUCCESS) {
      return status;
    }
    return handle;
  }

  inline Result< void > Interface::DestroyLight(remixapi_LightHandle handle) {
    return m_CInterface.DestroyLight(handle);
  }

  inline Result< void > Interface::DrawLightInstance(remixapi_LightHandle handle) {
    return m_CInterface.DrawLightInstance(handle);
  }

  namespace detail {
    struct dxvk_ExternalSwapchain {
      uint64_t vkImage;
      uint64_t vkSemaphoreRenderingDone;
      uint64_t vkSemaphoreResumeSemaphore;
    };

    struct dxvk_VkImage {
      uint64_t vkImage;
    };
  }

  inline Result< IDirect3D9Ex* > Interface::dxvk_CreateD3D9(bool editorModeEnabled) {
    IDirect3D9Ex* d3d9 { nullptr };
    remixapi_ErrorCode status = m_CInterface.dxvk_CreateD3D9(editorModeEnabled, &d3d9);
    if (status != REMIXAPI_ERROR_CODE_SUCCESS) {
      return status;
    }
    return d3d9;
  }

  inline Result< void > Interface::dxvk_RegisterD3D9Device(IDirect3DDevice9Ex* d3d9Device) {
    return m_CInterface.dxvk_RegisterD3D9Device(d3d9Device);
  }

  inline Result< detail::dxvk_ExternalSwapchain > Interface::dxvk_GetExternalSwapchain() {
    detail::dxvk_ExternalSwapchain externalSwapchain {};
    remixapi_ErrorCode status = m_CInterface.dxvk_GetExternalSwapchain(
      &externalSwapchain.vkImage,
      &externalSwapchain.vkSemaphoreRenderingDone,
      &externalSwapchain.vkSemaphoreResumeSemaphore);
    if (status != REMIXAPI_ERROR_CODE_SUCCESS) {
      return status;
    }
    return externalSwapchain;
  }

  inline Result< detail::dxvk_VkImage > Interface::dxvk_GetVkImage(IDirect3DSurface9* source) {
    detail::dxvk_VkImage externalImage {};
    remixapi_ErrorCode status = m_CInterface.dxvk_GetVkImage(source, &externalImage.vkImage);
    if (status != REMIXAPI_ERROR_CODE_SUCCESS) {
      return status;
    }
    return externalImage;
  }

  inline Result< void > Interface::dxvk_CopyRenderingOutput(
      IDirect3DSurface9* destination, remixapi_dxvk_CopyRenderingOutputType type) {
    return m_CInterface.dxvk_CopyRenderingOutput(destination, type);
  }

  inline Result< void > Interface::dxvk_SetDefaultOutput(
      remixapi_dxvk_CopyRenderingOutputType type, const remixapi_Float4D& color) {
    return m_CInterface.dxvk_SetDefaultOutput(type, &color);
  }

  template< typename CallbackLambda >
  inline Result< void > Interface::pick_RequestObjectPicking(const Rect2D& region, CallbackLambda&& callback) {
    using Func = std::function< void(Span<uint32_t>) >;

    static auto bootstrapForC = [](const uint32_t* objectPickingValues_values,
                                   uint32_t objectPickingValues_count,
                                   void* callbackUserData) {
      // unwrap 'callbackUserData', it is a user's lambda
      if (auto* userLambda = static_cast<Func*>(callbackUserData)) {
        auto arg = Span<uint32_t>{ objectPickingValues_values, objectPickingValues_count };
        // call user's lambda
        (*userLambda)(arg);
        delete userLambda;
      }
    };

    // pass user's lambda as as 'callbackUserData'
    auto userLambda = std::make_unique<Func>(std::forward<CallbackLambda>(callback));
    remixapi_ErrorCode status = m_CInterface.pick_RequestObjectPicking(&region, bootstrapForC, userLambda.get());
    if (status == REMIXAPI_ERROR_CODE_SUCCESS) {
      userLambda.release();
    }
    return status;
  }

  inline Result< void > Interface::pick_HighlightObjects(const uint32_t* objectPickingValues_values,
                                                         uint32_t objectPickingValues_count,
                                                         uint8_t colorR, uint8_t colorG, uint8_t colorB) {
    return m_CInterface.pick_HighlightObjects(objectPickingValues_values,
                                              objectPickingValues_count,
                                              colorR, colorG, colorB);
  }
}
