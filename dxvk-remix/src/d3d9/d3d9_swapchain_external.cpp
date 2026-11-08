/*
* Copyright (c) 2021-2023, NVIDIA CORPORATION. All rights reserved.
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
#include "d3d9_swapchain.h"
#include "d3d9_surface.h"
#include "d3d9_monitor.h"

#include "d3d9_hud.h"
#include "../util/util_env.h"
#include "../dxvk/rtx_render/rtx_bridge_message_channel.h"
#include "../dxvk/dxvk_scoped_annotation.h"

// NV-DXVK start: Explicit external-memory Vulkan interop.
#include <remix/remix_c.h>
#include "../dxvk/rtx_render/rtx_remix_pnext.h"
// NV-DXVK end


namespace dxvk {
  extern bool g_combineGuiInFinalColor = true;

  D3D9SwapchainExternal::D3D9SwapchainExternal(
        D3D9DeviceEx* pDevice,
        D3DPRESENT_PARAMETERS* pPresentParams,
  const D3DDISPLAYMODEEX* pFullscreenDisplayMode)
    : D3D9SwapChainEx(pDevice, pPresentParams, pFullscreenDisplayMode) {
    m_frameEndSemaphore = RtxSemaphore::createBinary(pDevice->GetDXVKDevice().ptr(), "ExternalPresenter::frameEnd");
    m_frameResumeSemaphore = RtxSemaphore::createBinary(pDevice->GetDXVKDevice().ptr(), "ExternalPresenter::frameResume");
  }

  // NV-DXVK start: Explicit external-memory Vulkan interop with client-owned presentation.
  D3D9SwapchainExternal::~D3D9SwapchainExternal() {
    if (m_interopColorHandle) {
      CloseHandle(m_interopColorHandle);
    }
    if (m_interopDepthHandle) {
      CloseHandle(m_interopDepthHandle);
    }
    if (m_interopUiHandle) {
      CloseHandle(m_interopUiHandle);
    }
  }

  void D3D9SwapchainExternal::enableInterop() {
    m_interopSharedDevice = !m_device->vkd()->ownsDevice();
    m_interopDone = RtxSemaphore::createTimeline(m_device.ptr(), "RemixInterop::done", 0, !m_interopSharedDevice);
    m_interopClient = RtxSemaphore::createTimeline(m_device.ptr(), "RemixInterop::client", 0, !m_interopSharedDevice);
    m_interopEnabled = true;
  }

  void D3D9SwapchainExternal::prepareInteropImages(const Rc<DxvkImage>& color, const Rc<DxvkImage>& depth) {
    auto prepare = [this, &color](const Rc<DxvkImage>& source, Rc<DxvkImage>& target, HANDLE& handle, const char* name, bool ui = false) {
      if (!source.ptr()) {
        if (target.ptr()) {
          ++m_interopGeneration;
        }
        if (handle) {
          CloseHandle(handle);
          handle = nullptr;
        }
        target = nullptr;
        return;
      }
      const auto& src = source->info();
      const bool colorFormatSupported = src.format == VK_FORMAT_R8G8B8A8_UNORM || src.format == VK_FORMAT_R8G8B8A8_SRGB ||
        src.format == VK_FORMAT_B8G8R8A8_UNORM || src.format == VK_FORMAT_B8G8R8A8_SRGB;
      // The client timeline protects the producer's original images on a shared device.
      if (m_interopSharedDevice && !ui && (source != color || colorFormatSupported)) {
        if (target != source) {
          target = source;
          ++m_interopGeneration;
        }
        return;
      }
      const VkFormat format = source == color ? VK_FORMAT_R8G8B8A8_UNORM : src.format;
      if (target.ptr() && target->info().extent == src.extent && target->info().format == format) {
        return;
      }
      DxvkImageCreateInfo info {};
      info.type = VK_IMAGE_TYPE_2D;
      info.format = format;
      info.flags = VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT;
      info.sampleCount = VK_SAMPLE_COUNT_1_BIT;
      info.extent = src.extent;
      info.numLayers = 1;
      info.mipLevels = 1;
      info.usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
      info.stages = VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
      info.access = VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT;
      const auto features = m_device->adapter()->formatProperties(format).optimalTilingFeatures;
      if (features & VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) {
        info.usage |= VK_IMAGE_USAGE_STORAGE_BIT;
        info.stages |= VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
        info.access |= VK_ACCESS_SHADER_WRITE_BIT;
      }
      if (features & VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT) {
        info.usage |= VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_INPUT_ATTACHMENT_BIT;
        info.stages |= VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        info.access |= VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_INPUT_ATTACHMENT_READ_BIT;
      }
      if (ui) {
        info.usage |= VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        info.stages |= VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        info.access |= VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_READ_BIT;
      }
      info.tiling = VK_IMAGE_TILING_OPTIMAL;
      info.layout = VK_IMAGE_LAYOUT_GENERAL;
      if (!m_interopSharedDevice) {
        info.sharing.mode = DxvkSharedHandleMode::Export;
        info.sharing.type = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
      }
      auto image = m_device->createImage(info, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, DxvkMemoryStats::Category::RTXRenderTarget, name);
      if (!m_interopSharedDevice && !image->isShared()) {
        throw DxvkError("Vulkan interop image does not support external memory export");
      }
      HANDLE exported = m_interopSharedDevice ? nullptr : image->sharedHandle();
      if (!m_interopSharedDevice && (!exported || exported == INVALID_HANDLE_VALUE)) {
        throw DxvkError("Failed to export Vulkan interop image memory");
      }
      if (handle) {
        CloseHandle(handle);
      }
      target = std::move(image);
      handle = exported;
      ++m_interopGeneration;
    };
    prepare(color, m_interopColor, m_interopColorHandle, "RemixInterop::color");
    prepare(depth, m_interopDepth, m_interopDepthHandle, "RemixInterop::depth");
    prepare(color, m_interopUi, m_interopUiHandle, "RemixInterop::ui", true);
    if (!m_interopUiView.ptr() || m_interopUiView->image() != m_interopUi) {
      DxvkImageViewCreateInfo view {};
      view.type = VK_IMAGE_VIEW_TYPE_2D;
      view.format = m_interopUi->info().format;
      view.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
      view.aspect = VK_IMAGE_ASPECT_COLOR_BIT;
      view.numLevels = 1;
      view.numLayers = 1;
      m_interopUiView = m_device->createImageView(m_interopUi, view);
    }
  }

  HRESULT D3D9SwapchainExternal::completeInterop(uint64_t clientDoneValue) {
    if (!m_interopEnabled || !m_interopSubmitted || clientDoneValue <= m_interopClientValue) {
      return D3DERR_INVALIDCALL;
    }
    m_interopClientValue = clientDoneValue;
    m_parent->EmitCs([this, clientDoneValue, color = m_interopColor, depth = m_interopDepth, ui = m_interopUi](DxvkContext* ctx) {
      ctx->getCommandList()->addWaitSemaphore(m_interopClient->handle(), clientDoneValue);
      if (!m_interopSharedDevice) {
        ctx->transferImageExternal(color, false);
        if (depth.ptr()) {
          ctx->transferImageExternal(depth, false);
        }
        ctx->transferImageExternal(ui, false);
      } else {
        // Keep borrowed images alive until the host's consuming submission completes.
        ctx->getCommandList()->trackResource<DxvkAccess::Write>(color);
        if (depth.ptr()) ctx->getCommandList()->trackResource<DxvkAccess::Write>(depth);
        ctx->getCommandList()->trackResource<DxvkAccess::Write>(ui);
      }
      ctx->flushCommandList();
    });
    m_parent->Flush();
    m_parent->SynchronizeCsThread();
    m_interopSubmitted = false;
    return S_OK;
  }

  HRESULT D3D9SwapchainExternal::presentClientFrame(remixapi_VulkanClientFrameInfoEXT& frame, bool rtxFramePrepared) {
    if (frame.device != reinterpret_cast<uint64_t>(m_device->handle())) {
      return D3DERR_INVALIDCALL;
    }
    if (!frame.image) {
      m_parent->Flush();
      m_parent->SynchronizeCsThread();
      m_device->waitForCompletion();
      if (m_dlfgPresenter != nullptr) {
        m_dlfgPresenter->synchronize();
      }
      m_device->waitForIdle();
      m_clientFrameView = nullptr;
      m_clientFrameGeneration = 0;
      return S_OK;
    }
    if (!frame.width || !frame.height || !frame.sourceReadySemaphore || !frame.sourceReadyValue ||
        frame.vsync > 1 || frame.bottomUp > 1 || frame.allowFrameGeneration > 1) {
      return D3DERR_INVALIDCALL;
    }
    if (!m_clientFrameConsumed.ptr()) {
      m_clientFrameConsumed = RtxSemaphore::createTimeline(m_device.ptr(), "ClientFrame::consumed");
    }
    RECT clientRect {};
    if (!IsWindow(m_window) || IsIconic(m_window) || !GetClientRect(m_window, &clientRect) ||
        clientRect.right <= clientRect.left || clientRect.bottom <= clientRect.top) {
      // Host signals must follow all earlier GPU signals on this timeline.
      m_clientFrameConsumed->wait(m_clientFrameConsumedValue);
      m_clientFrameConsumed->signal(++m_clientFrameConsumedValue);
      frame.sourceConsumedSemaphore = reinterpret_cast<uint64_t>(m_clientFrameConsumed->handle());
      frame.sourceConsumedValue = m_clientFrameConsumedValue;
      return S_OK;
    }
    const auto imageHandle = reinterpret_cast<VkImage>(frame.image);
    if (!m_clientFrameView.ptr() || m_clientFrameView->image()->handle() != imageHandle ||
        m_clientFrameGeneration != frame.imageGeneration) {
      DxvkImageCreateInfo image {};
      image.type = VK_IMAGE_TYPE_2D;
      image.format = VkFormat(frame.format);
      image.sampleCount = VK_SAMPLE_COUNT_1_BIT;
      image.extent = { frame.width, frame.height, 1 };
      image.numLayers = 1;
      image.mipLevels = 1;
      image.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
      image.stages = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
      image.access = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
      image.tiling = VK_IMAGE_TILING_OPTIMAL;
      image.layout = VK_IMAGE_LAYOUT_GENERAL;
      image.initialLayout = VK_IMAGE_LAYOUT_GENERAL;
      auto borrowed = m_device->createImageFromVkImage(image, imageHandle);
      DxvkImageViewCreateInfo view {};
      view.type = VK_IMAGE_VIEW_TYPE_2D;
      view.format = image.format;
      view.usage = VK_IMAGE_USAGE_SAMPLED_BIT;
      view.aspect = VK_IMAGE_ASPECT_COLOR_BIT;
      view.numLevels = 1;
      view.numLayers = 1;
      m_clientFrameView = m_device->createImageView(borrowed, view);
      m_clientFrameGeneration = frame.imageGeneration;
    }
    const bool vsync = frame.vsync != 0 && !m_context->isDLFGEnabled();
    const VkExtent2D surfaceExtent {
      uint32_t(clientRect.right - clientRect.left),
      uint32_t(clientRect.bottom - clientRect.top)
    };
    m_srcRect = { 0, 0, LONG(frame.width), LONG(frame.height) };
    m_dstRect = { 0, 0, LONG(surfaceExtent.width), LONG(surfaceExtent.height) };
    bool recreated = false;
    if ((m_presenter == nullptr && m_dlfgPresenter == nullptr) || (m_dlfgPresenter != nullptr) != m_context->isDLFGEnabled()) {
      m_vsync = vsync;
      CreatePresenter();
      recreated = true;
    } else if (m_vsync != vsync || m_clientInterpolationCount != m_context->dlfgInterpolatedFrameCount() ||
               m_clientSurfaceExtent != surfaceExtent) {
      m_vsync = vsync;
      RecreateSwapChain(m_vsync);
      recreated = true;
    }
    // WSI may constrain the requested extent independently of the host color image.
    m_clientSurfaceExtent = surfaceExtent;
    if (recreated) {
      const auto actualExtent = GetPresenter()->info().imageExtent;
      Logger::info(str::format("Client Vulkan surface: source=", frame.width, "x", frame.height,
        ", window=", surfaceExtent.width, "x", surfaceExtent.height,
        ", swapchain=", actualExtent.width, "x", actualExtent.height));
    }
    const uint64_t consumedValue = ++m_clientFrameConsumedValue;
    ClientPresentImage image {
      m_clientFrameView, reinterpret_cast<VkSemaphore>(frame.sourceReadySemaphore), frame.sourceReadyValue,
      m_clientFrameConsumed->handle(), consumedValue, frame.bottomUp != 0, {}
    };
    if (rtxFramePrepared && frame.allowFrameGeneration && m_clientFrameInterpolation.valid() &&
        m_clientFrameInterpolation.frameId == m_interopFrameId) {
      image.interpolation = m_clientFrameInterpolation;
    }
    PresentImage(vsync ? 1 : 0, &image);
    if (!rtxFramePrepared) m_device->incrementPresentCount();
    frame.sourceConsumedSemaphore = reinterpret_cast<uint64_t>(m_clientFrameConsumed->handle());
    frame.sourceConsumedValue = consumedValue;
    frame.presentationEpoch = m_clientPresentationEpoch;
    frame.renderedFrames = m_dlfgPresenter != nullptr ? m_dlfgPresenter->getRenderedFrameCount() : m_frameId;
    frame.generatedFrames = m_dlfgPresenter != nullptr ? m_dlfgPresenter->getGeneratedFrameCount() : 0;
    return S_OK;
  }

  void D3D9SwapchainExternal::getInteropInfo(remixapi_dxvk_InteropInfo& info) const {
    info = {};
    info.dxvkDoneSemaphore_Handle = reinterpret_cast<uint64_t>(m_interopDone->sharedHandle());
    info.dxvkDoneSemaphore_ValueToWait = m_interopDoneValue;
    info.clientDoneSemaphore_Handle = reinterpret_cast<uint64_t>(m_interopClient->sharedHandle());
    if (m_interopColor.ptr()) {
      info.imageMemory_Handle = reinterpret_cast<uint64_t>(m_interopColorHandle);
      info.allocationSize = m_interopColor->sharedAllocationSize();
      info.width = m_interopColor->info().extent.width;
      info.height = m_interopColor->info().extent.height;
      switch (m_interopColor->info().format) {
      case VK_FORMAT_B8G8R8A8_UNORM: info.interopImageFormat = REMIXAPI_TEXTURE_FORMAT_BGRA8_UNORM; break;
      case VK_FORMAT_B8G8R8A8_SRGB: info.interopImageFormat = REMIXAPI_TEXTURE_FORMAT_BGRA8_SRGB; break;
      case VK_FORMAT_R8G8B8A8_SRGB: info.interopImageFormat = REMIXAPI_TEXTURE_FORMAT_RGBA8_SRGB; break;
      default: info.interopImageFormat = REMIXAPI_TEXTURE_FORMAT_RGBA8_UNORM; break;
      }
    }
    if (m_interopDepth.ptr()) {
      info.depthImageMemory_Handle = reinterpret_cast<uint64_t>(m_interopDepthHandle);
      info.depthAllocationSize = m_interopDepth->sharedAllocationSize();
      info.depthWidth = m_interopDepth->info().extent.width;
      info.depthHeight = m_interopDepth->info().extent.height;
      info.interopDepthImageFormat = REMIXAPI_TEXTURE_FORMAT_R32_SFLOAT;
    }
  }

  void D3D9SwapchainExternal::getVulkanInteropInfo(remixapi_VulkanInteropInfo& info) const {
    void* extensions = info.pNext;
    info = {};
    info.sType = REMIXAPI_STRUCT_TYPE_VULKAN_INTEROP_INFO;
    info.device = reinterpret_cast<uint64_t>(m_device->handle());
    info.pNext = extensions;
    for (void* next = extensions; next; next = pnext::detail::getPNext(next)) {
      if (pnext::detail::getStructType(next) == REMIXAPI_STRUCT_TYPE_INTEROP_FRAME_STATUS_EXT) {
        auto* status = static_cast<remixapi_InteropFrameStatusEXT*>(next);
        status->frameId = m_interopFrameId;
        status->flags = m_interopFrameFlags;
        status->shaderCompileCount = m_interopShaderCompileCount;
      }
    }
    const auto& id = m_device->adapter()->devicePropertiesExt().coreDeviceId;
    memcpy(info.deviceUUID, id.deviceUUID, sizeof(info.deviceUUID));
    memcpy(info.driverUUID, id.driverUUID, sizeof(info.driverUUID));
    memcpy(info.deviceLUID, id.deviceLUID, sizeof(info.deviceLUID));
    info.deviceLUIDValid = id.deviceLUIDValid;
    info.deviceNodeMask = id.deviceNodeMask;
    info.queueFamilyIndex = m_device->queues().graphics.queueFamily;
    info.semaphoreType = VK_SEMAPHORE_TYPE_TIMELINE;
    info.dxvkDoneSemaphore_Handle = m_interopSharedDevice ? reinterpret_cast<uint64_t>(m_interopDone->handle())
      : reinterpret_cast<uint64_t>(m_interopDone->sharedHandle());
    info.dxvkDoneSemaphore_ValueToWait = m_interopDoneValue;
    info.clientDoneSemaphore_Handle = m_interopSharedDevice ? reinterpret_cast<uint64_t>(m_interopClient->handle())
      : reinterpret_cast<uint64_t>(m_interopClient->sharedHandle());
    auto copyImage = [this](const Rc<DxvkImage>& image, HANDLE handle, remixapi_VulkanInteropImage& out) {
      if (!image.ptr()) {
        return;
      }
      const auto& desc = image->info();
      out.image = reinterpret_cast<uint64_t>(image->handle());
      out.memoryHandle = reinterpret_cast<uint64_t>(handle);
      out.generation = m_interopGeneration;
      if (!m_interopSharedDevice) {
        out.allocationSize = image->sharedAllocationSize();
        out.bindOffset = image->sharedMemoryOffset();
        out.memoryTypeIndex = image->sharedMemoryTypeIndex();
        out.allocationFlags = VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT;
      }
      out.flags = desc.flags;
      out.imageType = desc.type;
      out.format = desc.format;
      out.width = desc.extent.width;
      out.height = desc.extent.height;
      out.depth = desc.extent.depth;
      out.mipLevels = desc.mipLevels;
      out.arrayLayers = desc.numLayers;
      out.samples = desc.sampleCount;
      out.tiling = desc.tiling;
      out.usage = desc.usage;
      out.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
      out.initialLayout = desc.initialLayout;
      out.externalLayout = VK_IMAGE_LAYOUT_GENERAL;
      out.ownerQueueFamily = m_interopSharedDevice ? m_device->queues().graphics.queueFamily : VK_QUEUE_FAMILY_EXTERNAL;
      out.externalMemoryHandleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
      out.dedicatedAllocation = true;
      out.viewFormatCount = desc.viewFormatCount;
      for (uint32_t i = 0; i < desc.viewFormatCount && i < 16; i++) {
        out.viewFormats[i] = desc.viewFormats[i];
      }
    };
    copyImage(m_interopColor, m_interopColorHandle, info.color);
    copyImage(m_interopDepth, m_interopDepthHandle, info.depth);
    copyImage(m_interopUi, m_interopUiHandle, info.uiOverlay);
  }
  // NV-DXVK end


  void D3D9SwapchainExternal::queuePresentTask(std::function<void(DxvkContext*)> task) {
    std::lock_guard lock { m_presentTaskMutex };
    m_presentTasks.push_back(std::move(task));
  }


  void D3D9SwapchainExternal::clearPresentTasks() {
    std::lock_guard lock { m_presentTaskMutex };
    m_presentTasks.clear();
  }


  void D3D9SwapchainExternal::runPresentTasks() {
    std::vector<std::function<void(DxvkContext*)>> tasks;
    {
      std::lock_guard lock { m_presentTaskMutex };
      tasks.swap(m_presentTasks);
    }

    for (auto& task : tasks) {
      task(m_context.ptr());
    }
  }


  HRESULT STDMETHODCALLTYPE D3D9SwapchainExternal::Present(const RECT*, const RECT*, HWND hDestWindowOverride, const RGNDATA*,  DWORD) {
    // NV-DXVK start: Submit and completion must balance before another frame is rendered.
    if (m_interopEnabled && m_interopSubmitted) {
      return D3DERR_INVALIDCALL;
    }
    // NV-DXVK end
    if (hDestWindowOverride != nullptr) {
      m_window = hDestWindowOverride;
    }

    // Update window handle with other systems
    m_context->getCommonObjects()->setWindowHandle(m_window);

    auto targetImage = m_backBuffers[0]->GetCommonTexture()->GetImage();
    auto swapImageView = m_backBuffers[0]->GetImageView(false);

    auto& imageInfo = targetImage->info();

    m_parent->m_rtx.EndFrame(targetImage);

    m_parent->EmitCs([this, cTargetImage = targetImage](DxvkContext* ctx) {
      const auto state = static_cast<RtxContext*>(ctx)->getInteropFrameState();
      m_interopFrameId = state.frameId;
      m_interopFrameFlags = (state.raytraced ? 1u : 0u) | (state.cameraValid ? 2u : 0u)
        | (state.shaderCompileCount ? 4u : 0u);
      m_interopShaderCompileCount = state.shaderCompileCount;
      m_clientFrameInterpolation.reset();
      if (state.raytraced && state.cameraValid && !state.shaderCompileCount) {
        m_clientFrameInterpolation = static_cast<RtxContext*>(ctx)->getFrameInterpolationInfo();
      }
      const auto& finalOutput =
        ctx->getCommonObjects()->getResources().getRaytracingOutput().m_finalOutput;

      if (!finalOutput.empty()) {
        const auto srcImage = finalOutput.resource(Resources::AccessType::ReadWrite).image;
        RtxContext::blitImageHelper(ctx, srcImage, cTargetImage, VK_FILTER_NEAREST);
      }
    });

    m_parent->Flush();
    m_parent->SynchronizeCsThread();

    m_context->beginRecording(m_device->createCommandList());

    DxvkRenderTargets renderTargets;
    renderTargets.color[0].view = swapImageView;
    renderTargets.color[0].layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    m_context->bindRenderTargets(renderTargets);

    if (m_hud != nullptr) {
      VkSurfaceFormatKHR fmt;
      fmt.format = imageInfo.format;
      fmt.colorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;

      m_hud->render(m_context, fmt, { imageInfo.extent.width, imageInfo.extent.height });
    }

    if (!m_interopEnabled && ImGUI::enableExternalPresenter() && g_combineGuiInFinalColor) {
      m_device->getCommon()->getImgui().render(m_context, { imageInfo.extent.width, imageInfo.extent.height });
    }

    m_parent->m_rtx.OnPresent(targetImage);

// Run capture copies before signaling frame completion.
    runPresentTasks();

    // NV-DXVK start: Export color and projected depth after UI rendering, before signaling completion.
    if (m_interopEnabled) {
      const auto depth = m_context->getCommonObjects()->getResources().getRaytracingOutput().m_primaryDepth.image;
      prepareInteropImages(targetImage, depth);
      if (targetImage != m_interopColor) {
        RtxContext::blitImageHelper(m_context.ptr(), targetImage, m_interopColor, VK_FILTER_NEAREST);
      }
      VkClearColorValue transparent {};
      m_context->clearColorImage(m_interopUi, transparent, m_interopUi->getAvailableSubresources());
      DxvkRenderTargets uiTargets;
      uiTargets.color[0].view = m_interopUiView;
      uiTargets.color[0].layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
      m_context->bindRenderTargets(uiTargets);
      if (ImGUI::enableExternalPresenter() || RtxOptions::showUI() != UIType::None) {
        m_device->getCommon()->getImgui().render(m_context, { imageInfo.extent.width, imageInfo.extent.height });
      }
      m_context->changeImageLayout(m_interopUi, VK_IMAGE_LAYOUT_GENERAL);
      if (!m_interopSharedDevice) m_context->transferImageExternal(m_interopUi, true);
      m_context->changeImageLayout(m_interopColor, VK_IMAGE_LAYOUT_GENERAL);
      if (!m_interopSharedDevice) m_context->transferImageExternal(m_interopColor, true);
      if (m_interopDepth.ptr()) {
        if (depth != m_interopDepth) {
          RtxContext::blitImageHelper(m_context.ptr(), depth, m_interopDepth, VK_FILTER_NEAREST);
        }
        m_context->changeImageLayout(m_interopDepth, VK_IMAGE_LAYOUT_GENERAL);
        if (!m_interopSharedDevice) m_context->transferImageExternal(m_interopDepth, true);
      }
      m_context->getCommandList()->addSignalSemaphore(m_interopDone->handle(), ++m_interopDoneValue);
      m_device->submitCommandList(m_context->endRecording(), VK_NULL_HANDLE, VK_NULL_HANDLE);
      m_parent->GetDXVKDevice()->incrementPresentCount();
      m_interopSubmitted = true;
      return S_OK;
    }
    // NV-DXVK end

    m_context->changeImageLayout(targetImage, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    m_context->emitMemoryBarrier(VK_DEPENDENCY_DEVICE_GROUP_BIT,
                                 VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                                 VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                                 VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                                 VK_ACCESS_SHADER_READ_BIT);

    // signal to external that rendering is done
    m_context->getCommandList()->addSignalSemaphore(m_frameEndSemaphore->handle(), 1);
    m_device->submitCommandList(m_context->endRecording(), VK_NULL_HANDLE, VK_NULL_HANDLE);

    m_parent->GetDXVKDevice()->incrementPresentCount();

    // Wait on the next frame before resuming rendering
    m_parent->EmitCs([this](DxvkContext* ctx) {
      ctx->getCommandList()->addWaitSemaphore(m_frameResumeSemaphore->handle(), 1);
      ctx->flushCommandList();
    });

    return S_OK;
  }

}
