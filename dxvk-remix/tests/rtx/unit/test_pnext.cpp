/*
* Copyright (c) 2023, NVIDIA CORPORATION. All rights reserved.
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

#include "../../../src/dxvk/rtx_render/rtx_remix_pnext.h"
#include <remix/remix.h>

#include "../../../src/util/util_error.h"

#include <cstddef>
#include <iostream>

#ifdef _MSC_VER
#define STRINGIFY_HELPER(x) #x
#define STRINGIFY(x) STRINGIFY_HELPER(x)
#define ERROR_INTRO __FUNCTION__ "(" STRINGIFY(__LINE__) "): "
#else
#define ERROR_INTRO ""
#endif

namespace pnext_test_app {
  using namespace pnext::detail;

  void test_find() {
    auto ext = remixapi_MaterialInfoOpaqueEXT {};
    {
      ext.sType = REMIXAPI_STRUCT_TYPE_MATERIAL_INFO_OPAQUE_EXT;
      ext.pNext = nullptr;
    }
    auto info = remixapi_MaterialInfo {};
    {
      info.sType = REMIXAPI_STRUCT_TYPE_MATERIAL_INFO;
      info.pNext = &ext;
    }

    if (auto f = pnext::find< remixapi_MaterialInfo >(&info)) {
      if (f != &info) {
        throw dxvk::DxvkError { ERROR_INTRO
          "Result of pnext::find< remixapi_MaterialInfoOpaqueEXT >( remixapi_MaterialInfo{..} )"
          "must match the address of \'info\' variable" };
      }
    } else {
      throw dxvk::DxvkError { ERROR_INTRO
        "pnext::find< remixapi_MaterialInfo >( remixapi_MaterialInfo{..} ) failed" };
    }

    if (auto f = pnext::find< remixapi_MaterialInfoOpaqueEXT >(&info)) {
      if (f != &ext) {
        throw dxvk::DxvkError { ERROR_INTRO
          "Result of pnext::find< remixapi_MaterialInfoOpaqueEXT >( remixapi_MaterialInfo{..} )"
          "must match the address of \'ext\' variable" };
      }
    } else {
      throw dxvk::DxvkError { ERROR_INTRO
        "pnext::find< remixapi_MaterialInfoOpaqueEXT >( remixapi_MaterialInfo{..} ) must return non-null" };
    }

    // must be un-compilable and output a short compilation error
    {
      // pnext::find< remixapi_LightInfo >(&info);
      // pnext::find< remixapi_LightInfoSphereEXT >(&info);
    }
  }

  void test_const() {
    auto ext = remixapi_MaterialInfoOpaqueEXT {};
    {
      ext.sType = REMIXAPI_STRUCT_TYPE_MATERIAL_INFO_OPAQUE_EXT;
      ext.pNext = nullptr;
    }
    auto info = remixapi_MaterialInfo {};
    {
      info.sType = REMIXAPI_STRUCT_TYPE_MATERIAL_INFO;
      info.pNext = &ext;
    }
    const auto constInfo = remixapi_MaterialInfo { info };

    {
      auto pNext = getPNext(&info);
      static_assert(!std::is_const_v< std::remove_pointer_t< decltype(pNext) > >);

      auto constPNext = getPNext(&constInfo);
      static_assert(std::is_const_v< std::remove_pointer_t< decltype(constPNext) > >);
    }
    {
      auto f = pnext::find< remixapi_MaterialInfoOpaqueEXT >(&info);
      if (f != &ext) {
        throw dxvk::DxvkError{ ERROR_INTRO
          "Result of pnext::find< remixapi_MaterialInfoOpaqueEXT >( remixapi_MaterialInfo{..} )"
          "must match the address of \'ext\' variable" };
      }

      static_assert(!std::is_const_v< std::remove_pointer_t< decltype(f) > >);
    }
    {
      auto cf = pnext::find< remixapi_MaterialInfoOpaqueEXT >(&constInfo);
      if (cf != &ext) {
        throw dxvk::DxvkError { ERROR_INTRO
          "Result of pnext::find< remixapi_MaterialInfoOpaqueEXT >( remixapi_MaterialInfo{..} )"
          "must match the address of \'ext\' variable" };
      }

      static_assert(std::is_const_v< std::remove_pointer_t< decltype(cf) > >);
    }
  }

  void test_getPNext() {
    auto ext = remixapi_LightInfoDistantEXT {};
    {
      ext.sType = REMIXAPI_STRUCT_TYPE_LIGHT_INFO_DISTANT_EXT;
      ext.pNext = nullptr;
    }
    auto info = remixapi_LightInfo {};
    {
      info.sType = REMIXAPI_STRUCT_TYPE_LIGHT_INFO;
      info.pNext = &ext;
    }

    if (info.pNext != &ext) {
      throw dxvk::DxvkError { ERROR_INTRO "Unexpected pNext mismatch: info.pNext != &ext" };
    }
    if (getPNext(&info) != info.pNext || getPNext(&ext) != ext.pNext) {
      throw dxvk::DxvkError { ERROR_INTRO "getPNext test fail: result is not equal to .pNext" };
    }
  }

  void test_memberDetection() {
    struct BadType_0 {
      int   sType_none;
      void* pNext;
    };
    struct BadType_1 {
      int   sType;
      void* pNext_none;
    };
    struct BadType_2 {
      int   sType;
    };
    struct BadType_3 {
      void* pNext;
    };
    struct BadType_4 {
      std::underlying_type_t< remixapi_StructType > sType;
      void* pNext;
    };
    struct BadType_5 {
      remixapi_StructType sType;
      uint64_t pNext;
    };
    static_assert(!helper::HasSTypePNext< BadType_0 >);
    static_assert(!helper::HasSTypePNext< BadType_1 >);
    static_assert(!helper::HasSTypePNext< BadType_2 >);
    static_assert(!helper::HasSTypePNext< BadType_3 >);
    static_assert(!helper::HasSTypePNext< BadType_4 >);
    static_assert(!helper::HasSTypePNext< BadType_5 >);

    struct GoodType {
      remixapi_StructType sType;
      void* pNext;
    };
    static_assert(helper::HasSTypePNext< GoodType >);
  }

  remixapi_ErrorCode REMIXAPI_CALL emulated(const remixapi_Rect2D* pixelRegion,
                              PFN_remixapi_pick_RequestObjectPickingUserCallback callback,
                              void* callbackUserData) {
    if (!pixelRegion || !callback) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }
    const uint32_t values[] = { 42, 7 };
    callback(values, 2, callbackUserData);
    return REMIXAPI_ERROR_CODE_SUCCESS;
  }

  void REMIXAPI_CALL receivePicking(const uint32_t* values, uint32_t count, void* userData) {
    if (count != 2 || !values || values[1] != 7) {
      throw dxvk::DxvkError { ERROR_INTRO "C interface picking callback lost its values or count" };
    }
    *static_cast<uint32_t*>(userData) = values[0];
  }

  void test_interfaceCallback() {
    auto cInterface = remixapi_Interface {};
    cInterface.pick_RequestObjectPicking = emulated;
    remixapi_Rect2D region { 1, 2, 3, 4 };
    uint32_t result = 0;
    if (cInterface.pick_RequestObjectPicking(&region, receivePicking, &result) != REMIXAPI_ERROR_CODE_SUCCESS || result != 42) {
      throw dxvk::DxvkError { ERROR_INTRO "C interface picking callback lost its user data" };
    }
    if (cInterface.pick_RequestObjectPicking(nullptr, receivePicking, &result) != REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS) {
      throw dxvk::DxvkError { ERROR_INTRO "C interface picking callback lost its error code" };
    }
  }

  remixapi_ErrorCode REMIXAPI_CALL rejectPicking(const remixapi_Rect2D*,
      PFN_remixapi_pick_RequestObjectPickingUserCallback, void*) {
    return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
  }

  void test_wrapper() {
    remix::Interface wrapper {};
    wrapper.m_CInterface.pick_RequestObjectPicking = emulated;
    uint32_t picked = 0;
    auto result = wrapper.pick_RequestObjectPicking(remix::Rect2D {},
      [&picked](remix::Span<uint32_t> values) {
        if (values.count != 2 || values.values[1] != 7) {
          throw dxvk::DxvkError { ERROR_INTRO "C++ wrapper lost picking span values" };
        }
        picked = values.values[0];
      });
    if (!result || picked != 42) {
      throw dxvk::DxvkError { ERROR_INTRO "C++ wrapper failed to forward picking callback" };
    }

    wrapper.m_CInterface.pick_RequestObjectPicking = rejectPicking;
    auto captured = std::make_shared<uint32_t>(7);
    std::weak_ptr<uint32_t> lifetime = captured;
    auto rejected = wrapper.pick_RequestObjectPicking(remix::Rect2D {}, [captured](remix::Span<uint32_t>) {});
    captured.reset();
    if (rejected.status() != REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS || !lifetime.expired()) {
      throw dxvk::DxvkError { ERROR_INTRO "Rejected C++ picking request must release its callback capture" };
    }
  }

  void test_wrapperMaterialHandles() {
    remix::MaterialInfo material;
    remix::MaterialInfoOpaqueEXT opaque;
    remix::MaterialInfoTranslucentEXT translucent;
    remix::LightInfoDomeEXT dome;
    if (material.albedoTexture || material.emissiveIntensity != 40.f || opaque.opacityConstant != 1.f ||
        opaque.roughnessConstant != 0.5f || !opaque.useDrawCallAlphaState || !opaque.enableDlssControlMask ||
        translucent.refractiveIndex != 1.3f || translucent.transmittanceMeasurementDistance != 1.f ||
        dome.opacity != 1.f) {
      throw dxvk::DxvkError { ERROR_INTRO "C++ material wrappers have inconsistent renderer defaults" };
    }
    auto texture = reinterpret_cast<remixapi_TextureHandle>(uintptr_t(0x1234));
    material.set_albedoTexture(texture);
    opaque.set_roughnessTexture(texture);
    material.pNext = &opaque;
    auto copied = material;
    auto moved = std::move(copied);
    if (moved.albedoTexture != texture || opaque.roughnessTexture != texture ||
        pnext::find<remixapi_MaterialInfoOpaqueEXT>(static_cast<remixapi_MaterialInfo*>(&moved)) != &opaque) {
      throw dxvk::DxvkError { ERROR_INTRO "C++ wrapper copy/move must preserve texture handles and its C extension chain" };
    }
    remix::VulkanInteropInfo interop;
    if (interop.sType != REMIXAPI_STRUCT_TYPE_VULKAN_INTEROP_INFO || interop.color.memoryHandle ||
        interop.uiOverlay.memoryHandle || interop.pNext) {
      throw dxvk::DxvkError { ERROR_INTRO "C++ Vulkan interop wrapper must initialize its C ABI header and zero outputs" };
    }
  }

  void test_chainTraversal() {
    remixapi_MaterialInfoOpaqueSubsurfaceEXT subsurface {};
    subsurface.sType = REMIXAPI_STRUCT_TYPE_MATERIAL_INFO_OPAQUE_SUBSURFACE_EXT;
    AnyInfoPrototype unknown { static_cast<remixapi_StructType>(0x7fffffff), &subsurface };
    remixapi_MaterialInfoOpaqueEXT opaque {};
    opaque.sType = REMIXAPI_STRUCT_TYPE_MATERIAL_INFO_OPAQUE_EXT;
    opaque.pNext = &unknown;
    remixapi_MaterialInfo material {};
    material.sType = REMIXAPI_STRUCT_TYPE_MATERIAL_INFO;
    material.pNext = &opaque;
    if (pnext::find<remixapi_MaterialInfoOpaqueSubsurfaceEXT>(&material) != &subsurface) {
      throw dxvk::DxvkError { ERROR_INTRO "pnext must traverse unknown extension structures" };
    }
    if (pnext::find<remixapi_MaterialInfoTranslucentEXT>(&material) != nullptr ||
        pnext::find<remixapi_MaterialInfoOpaqueEXT>(static_cast<remixapi_MaterialInfo*>(nullptr)) != nullptr ||
        getPNext(static_cast<remixapi_MaterialInfo*>(nullptr)) != nullptr) {
      throw dxvk::DxvkError { ERROR_INTRO "pnext must return null for missing extensions and null roots" };
    }
  }

  void test_cInitializers() {
    constexpr auto resolved = remixapi_ConfigVariableResolvedInfo_init();
    static_assert(resolved.sType == REMIXAPI_STRUCT_TYPE_CONFIG_VARIABLE_RESOLVED_INFO);
    static_assert(resolved.pNext == nullptr && resolved.key == nullptr && resolved.value == nullptr && resolved.valueSize == 0);
    constexpr auto submit = remixapi_SubmitInfo_init();
    static_assert(submit.sType == REMIXAPI_STRUCT_TYPE_SUBMIT_INFO && submit.pNext == nullptr && submit.interopFlags == 0);
    constexpr auto present = remixapi_PresentInfo_init();
    static_assert(present.sType == REMIXAPI_STRUCT_TYPE_PRESENT_INFO && present.pNext == nullptr && present.hwndOverride == nullptr);
    static_assert(!present.skipVulkanSwapchain && !present.interopFlipY && !present.clientDoneSemaphore_ValueToWait &&
      !present.disableCpuWaitForExternalSemaphores);
  }

  template<typename T>
  void test_rootType(remixapi_StructType expected) {
    T root {};
    root.sType = expected;
    if (pnext::find<T>(&root) != &root || getStructType(&root) != expected || getPNext(&root) != nullptr) {
      throw dxvk::DxvkError { ERROR_INTRO "New C API root type is missing its pnext specialization" };
    }
  }

  void test_forkAbi() {
    static_assert(REMIXAPI_VERSION_MAJOR == 1 && REMIXAPI_VERSION_MINOR == 0 && REMIXAPI_VERSION_PATCH == 0);
    static_assert(sizeof(remixapi_HardcodedVertex) == 64);
    static_assert(offsetof(remixapi_HardcodedVertex, texcoordBounds) == 36);
    static_assert(std::is_same_v<decltype(remixapi_MaterialInfo::albedoTexture), remixapi_TextureHandle>);
    static_assert(std::is_same_v<decltype(remixapi_LightInfoDomeEXT::colorTexture), remixapi_TextureHandle>);
    test_rootType<remixapi_TextureFromMemoryInfo>(REMIXAPI_STRUCT_TYPE_TEXTURE_FROM_MEMORY_INFO);
    test_rootType<remixapi_TexturePatchInfo>(REMIXAPI_STRUCT_TYPE_TEXTURE_PATCH_INFO);
    test_rootType<remixapi_SubmitInfo>(REMIXAPI_STRUCT_TYPE_SUBMIT_INFO);
    test_rootType<remixapi_ConfigVariableResolvedInfo>(REMIXAPI_STRUCT_TYPE_CONFIG_VARIABLE_RESOLVED_INFO);
    test_rootType<remixapi_VulkanInteropInfo>(REMIXAPI_STRUCT_TYPE_VULKAN_INTEROP_INFO);
  }

  namespace test_apiVersionOrdering {
    static_assert(REMIXAPI_VERSION_MAKE(0, 0, 0) < REMIXAPI_VERSION_MAKE(1, 0, 0));
    static_assert(REMIXAPI_VERSION_MAKE(0, 0, 0) < REMIXAPI_VERSION_MAKE(0, 1, 0));
    static_assert(REMIXAPI_VERSION_MAKE(0, 0, 0) < REMIXAPI_VERSION_MAKE(0, 0, 1));

    static_assert(REMIXAPI_VERSION_MAKE(1, 0, 0) < REMIXAPI_VERSION_MAKE(1, 2, 3));
    static_assert(REMIXAPI_VERSION_MAKE(1, 0, 1) < REMIXAPI_VERSION_MAKE(2, 0, 0));
    static_assert(REMIXAPI_VERSION_MAKE(1, 2, 3) < REMIXAPI_VERSION_MAKE(2, 0, 0));
    static_assert(REMIXAPI_VERSION_MAKE(0, 4, 1) < REMIXAPI_VERSION_MAKE(0, 4, 2));
    constexpr auto version = REMIXAPI_VERSION_MAKE(0xbeef, 0x12345678, 0xabcd);
    static_assert(REMIXAPI_VERSION_GET_MAJOR(version) == 0xbeef);
    static_assert(REMIXAPI_VERSION_GET_MINOR(version) == 0x12345678);
    static_assert(REMIXAPI_VERSION_GET_PATCH(version) == 0xabcd);
  }
}

int main() {
  try {
    pnext_test_app::test_find();
    pnext_test_app::test_const();
    pnext_test_app::test_getPNext();
    pnext_test_app::test_memberDetection();
    pnext_test_app::test_interfaceCallback();
    pnext_test_app::test_wrapper();
    pnext_test_app::test_wrapperMaterialHandles();
    pnext_test_app::test_chainTraversal();
    pnext_test_app::test_cInitializers();
    pnext_test_app::test_forkAbi();
  }
  catch (const dxvk::DxvkError& error) {
    std::cerr << error.message() << std::endl;
    throw;
  }

  return 0;
}
