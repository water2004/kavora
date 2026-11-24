// SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
// SPDX-License-Identifier: MIT

#include <algorithm>
#include <cmath>
#include <iomanip>
#include <locale>
#include <mutex>
#include <sstream>

#include "dxvk_device.h"
#include "dxvk_display_monitor.h"
#include "dxvk_display_output.h"
#include "dxvk_display_policy.h"
#include "dxvk_instance.h"
#include "rtx_render/rtx_option.h"

namespace dxvk {

  struct DxvkDisplayOutput::Impl {
    static void onModeChanged(DxvkDevice* device) {
      if (device != nullptr && device->getCommon()->metaDLFG().hasDLFGFailed()) {
        DxvkDLFG::requestPreset(DxvkDLFG::litePreset());
      }
    }

    RTX_OPTION_ARGS("rtx.display", uint32_t, hdrMode, 1,
      "Display output. 0: SDR, 1: Automatic HDR10 when Windows HDR is enabled and the surface supports it.",
      args.onChangeCallback = &onModeChanged);
    RTX_OPTION("rtx.display", float, paperWhiteNits, 0.0f,
      "HDR diffuse and interface white in cd/m2. 0: Windows SDR white, or 200 cd/m2 if unavailable.");
    RTX_OPTION("rtx.display", float, peakNits, 0.0f,
      "HDR peak luminance in cd/m2. 0: Display-reported peak, or 1000 cd/m2 if unavailable.");

    mutable std::mutex mutex;
    DxvkDisplayMonitor monitor;
    DxvkDisplayMonitorInfo monitorInfo;
    DxvkDisplayPolicy policy;
    DxvkDisplayFrame sourceFrame;
    void* window = nullptr;
    bool configured = false;
    bool sharedVulkan = false;
    bool clientFormatKnown = false;
    bool extendedSrgb = false;
    bool swapchainColorspace = false;
    bool metadataEnabled = false;

    explicit Impl(DxvkDevice* device)
      : swapchainColorspace(device->instance()->extensions().extSwapchainColorspace),
        metadataEnabled(device->extensions().extHdrMetadata) { }

    void refresh() {
      monitorInfo = monitor.query(window);
      DxvkDisplayCapabilities capabilities;
      capabilities.monitor = monitorInfo.monitor;
      capabilities.extendedSrgb = extendedSrgb;
      capabilities.swapchainColorspace = swapchainColorspace;
      capabilities.known = monitorInfo.known;
      capabilities.hdrEnabled = monitorInfo.hdrEnabled;
      capabilities.paperWhiteNits = monitorInfo.paperWhiteNits;
      capabilities.peakNits = monitorInfo.peakNits;
      capabilities.clientFormatKnown = clientFormatKnown;
      DxvkDisplaySettings settings;
      {
        std::lock_guard optionLock(RtxOptionImpl::getUpdateMutex());
        settings = { hdrMode.getValueNoLock(), paperWhiteNits.getValueNoLock(), peakNits.getValueNoLock() };
      }
      policy.update(settings, capabilities);
    }
  };

  DxvkDisplayOutput::DxvkDisplayOutput(DxvkDevice* device)
    : m_impl(std::make_unique<Impl>(device)) { }

  DxvkDisplayOutput::~DxvkDisplayOutput() = default;

  void DxvkDisplayOutput::configureHost(void* window, bool sharedVulkan) {
    std::lock_guard lock(m_impl->mutex);
    m_impl->window = window;
    if (!m_impl->configured) {
      m_impl->configured = true;
      m_impl->sharedVulkan = sharedVulkan;
      m_impl->extendedSrgb = sharedVulkan;
    }
  }

  void DxvkDisplayOutput::observeClientFormat(VkFormat format) {
    std::lock_guard lock(m_impl->mutex);
    const bool extended = m_impl->sharedVulkan && isExtendedSrgbClientFormat(format);
    m_impl->extendedSrgb = extended || (m_impl->clientFormatKnown && m_impl->extendedSrgb);
    m_impl->clientFormatKnown = true;
  }

  DxvkDisplayFrame DxvkDisplayOutput::prepareFrame(void* window, uint32_t frameIndex, bool sceneFrame) {
    std::lock_guard lock(m_impl->mutex);
    m_impl->window = window;
    m_impl->refresh();
    return m_impl->policy.frame(frameIndex, sceneFrame);
  }

  void DxvkDisplayOutput::setRenderFrame(const DxvkDisplayFrame& frame) {
    m_renderFrame = frame;
    std::lock_guard lock(m_impl->mutex);
    m_impl->sourceFrame = frame;
  }

  uint32_t DxvkDisplayOutput::surfaceFormats(VkSurfaceFormatKHR* formats, uint32_t capacity) const {
    std::lock_guard lock(m_impl->mutex);
    return m_impl->policy.surfaceFormats(formats, capacity);
  }

  bool DxvkDisplayOutput::needsSurfaceRecreation(VkSurfaceFormatKHR actual) const {
    std::lock_guard lock(m_impl->mutex);
    return m_impl->policy.needsSurfaceRecreation(actual);
  }

  void DxvkDisplayOutput::acceptSurface(VkSurfaceFormatKHR actual) {
    std::lock_guard lock(m_impl->mutex);
    m_impl->policy.acceptSurface(actual);
  }

  void DxvkDisplayOutput::rejectHdrSurface(VkResult failure) {
    std::lock_guard lock(m_impl->mutex);
    m_impl->policy.rejectHdrSurface();
    Logger::warn(str::format("HDR swap chain creation failed (", failure, "); retrying SDR."));
  }

  DxvkDisplayPresent DxvkDisplayOutput::makePresent(const DxvkDisplayFrame& source, VkSurfaceFormatKHR actual,
                                                   VkFormat clientFormat) const {
    std::lock_guard lock(m_impl->mutex);
    return m_impl->policy.present(source, actual, clientFormat);
  }

  bool DxvkDisplayOutput::hdrMetadata(VkHdrMetadataEXT& metadata) const {
    std::lock_guard lock(m_impl->mutex);
    const auto& monitor = m_impl->monitorInfo;
    auto validPrimary = [](const std::array<float, 2>& primary) {
      return std::isfinite(primary[0]) && std::isfinite(primary[1])
        && primary[0] > 0.0f && primary[1] > 0.0f && primary[0] + primary[1] <= 1.0001f;
    };
    if (!m_impl->metadataEnabled || !m_impl->policy.hdrActive() || !monitor.known
        || !validPrimary(monitor.redPrimary) || !validPrimary(monitor.greenPrimary)
        || !validPrimary(monitor.bluePrimary) || !validPrimary(monitor.whitePoint)) {
      return false;
    }
    metadata = { VK_STRUCTURE_TYPE_HDR_METADATA_EXT };
    metadata.displayPrimaryRed = { monitor.redPrimary[0], monitor.redPrimary[1] };
    metadata.displayPrimaryGreen = { monitor.greenPrimary[0], monitor.greenPrimary[1] };
    metadata.displayPrimaryBlue = { monitor.bluePrimary[0], monitor.bluePrimary[1] };
    metadata.whitePoint = { monitor.whitePoint[0], monitor.whitePoint[1] };
    metadata.maxLuminance = m_impl->policy.peakNits();
    metadata.minLuminance = std::isfinite(monitor.minimumNits)
      ? std::clamp(monitor.minimumNits, 0.0f, metadata.maxLuminance) : 0.0f;
    metadata.maxContentLightLevel = metadata.maxLuminance;
    // The full-frame average is unknown until the image has been measured.
    metadata.maxFrameAverageLightLevel = 0.0f;
    return true;
  }

  bool DxvkDisplayOutput::readStatus(std::string_view key, std::string& value) const {
    if (!isStatusKey(key)) {
      return false;
    }
    std::lock_guard lock(m_impl->mutex);
    const auto name = key.substr(std::string_view("rtx.display.status.").size());
    std::ostringstream text;
    text.imbue(std::locale::classic());
    text << std::setprecision(9);
    if (name == "mode") {
      text << (m_impl->policy.hdrActive() ? "hdr10" : "sdr");
    } else if (name == "reason") {
      text << m_impl->policy.reason();
    } else if (name == "paperWhiteNits") {
      text << m_impl->policy.paperWhiteNits();
    } else if (name == "peakNits") {
      text << m_impl->policy.peakNits();
    } else if (name == "generation") {
      text << m_impl->policy.generation();
    } else if (name == "sourceFrame") {
      const auto& source = m_impl->sourceFrame;
      text << source.frameIndex << ' ' << source.flags << ' ' << source.paperWhiteNits << ' ' << source.peakNits;
    } else {
      return false;
    }
    value = text.str();
    return true;
  }

  bool DxvkDisplayOutput::isHdrSurface(VkSurfaceFormatKHR format) {
    return DxvkDisplayPolicy::isHdrSurface(format);
  }

}
