// SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
// SPDX-License-Identifier: MIT

#include <algorithm>
#include <cmath>

#include "dxvk_display_policy.h"

namespace dxvk {

  namespace {
    float validNits(float value, float fallback) {
      return std::isfinite(value) && value > 0.0f ? value : fallback;
    }

    bool isSrgbFormat(VkFormat format) {
      return format == VK_FORMAT_R8G8B8A8_SRGB || format == VK_FORMAT_B8G8R8A8_SRGB
        || format == VK_FORMAT_A8B8G8R8_SRGB_PACK32;
    }
  }

  void DxvkDisplayPolicy::update(const DxvkDisplaySettings& settings, const DxvkDisplayCapabilities& capabilities) {
    const bool surfaceChanged = settings.hdrMode != m_settings.hdrMode
      || capabilities.monitor != m_capabilities.monitor
      || capabilities.extendedSrgb != m_capabilities.extendedSrgb
      || capabilities.swapchainColorspace != m_capabilities.swapchainColorspace
      || capabilities.known != m_capabilities.known
      || capabilities.hdrEnabled != m_capabilities.hdrEnabled;
    const bool contractChanged = capabilities.clientFormatKnown != m_capabilities.clientFormatKnown;
    const float peak = std::clamp(validNits(settings.peakNits,
      validNits(capabilities.peakNits, 1000.0f)), 80.0f, 10000.0f);
    const float white = std::clamp(validNits(settings.paperWhiteNits,
      validNits(capabilities.paperWhiteNits, 200.0f)), 1.0f, peak);
    if (surfaceChanged || contractChanged || peak != m_peakNits || white != m_paperWhiteNits) {
      ++m_generation;
    }
    if (surfaceChanged || contractChanged) {
      m_confirmed = false;
      m_rejected = false;
      m_unavailable = false;
    }
    m_settings = settings;
    m_capabilities = capabilities;
    m_peakNits = peak;
    m_paperWhiteNits = white;
  }

  bool DxvkDisplayPolicy::wantsHdr() const {
    return m_settings.hdrMode == 1 && m_capabilities.extendedSrgb
      && m_capabilities.clientFormatKnown
      && m_capabilities.monitor && m_capabilities.known
      && m_capabilities.hdrEnabled && m_capabilities.swapchainColorspace;
  }

  bool DxvkDisplayPolicy::isHdrSurface(VkSurfaceFormatKHR format) {
    return format.colorSpace == VK_COLOR_SPACE_HDR10_ST2084_EXT
      && (format.format == VK_FORMAT_A2B10G10R10_UNORM_PACK32
        || format.format == VK_FORMAT_A2R10G10B10_UNORM_PACK32);
  }

  bool DxvkDisplayPolicy::hdrActive() const {
    return isHdrSurface(m_surface);
  }

  VkSurfaceFormatKHR DxvkDisplayPolicy::selectSurfaceFormat(uint32_t supportedCount, const VkSurfaceFormatKHR* supported,
                                                           uint32_t desiredCount, const VkSurfaceFormatKHR* desired) {
    for (uint32_t i = 0; i < desiredCount; ++i) {
      for (uint32_t j = 0; j < supportedCount; ++j) {
        if (supported[j].colorSpace == desired[i].colorSpace
            && (supported[j].format == desired[i].format || supported[j].format == VK_FORMAT_UNDEFINED)) {
          return { desired[i].format, supported[j].colorSpace };
        }
      }
    }
    for (uint32_t i = 0; i < desiredCount; ++i) {
      if (desired[i].colorSpace != VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
        continue;
      }
      for (uint32_t j = 0; j < supportedCount; ++j) {
        if (supported[j].colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR
            && supported[j].format != VK_FORMAT_UNDEFINED
            && isSrgbFormat(supported[j].format) == isSrgbFormat(desired[i].format)) {
          return supported[j];
        }
      }
    }
    for (uint32_t j = 0; j < supportedCount; ++j) {
      if (supported[j].colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR
          && supported[j].format != VK_FORMAT_UNDEFINED) {
        return supported[j];
      }
    }
    return { VK_FORMAT_UNDEFINED, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR };
  }

  DxvkDisplayFrame DxvkDisplayPolicy::frame(uint32_t frameIndex, bool sceneFrame) const {
    DxvkDisplayFrame result;
    result.generation = m_generation;
    result.frameIndex = frameIndex;
    if (m_capabilities.extendedSrgb) {
      result.flags |= uint32_t(DxvkDisplayFrameFlag::ExtendedSrgb);
    }
    if (sceneFrame && wantsHdr() && m_confirmed && hdrActive() && !m_rejected) {
      result.flags |= uint32_t(DxvkDisplayFrameFlag::HdrScene);
      result.paperWhiteNits = m_paperWhiteNits;
      result.peakNits = m_peakNits;
    }
    return result;
  }

  uint32_t DxvkDisplayPolicy::surfaceFormats(VkSurfaceFormatKHR* formats, uint32_t capacity) const {
    if (!m_capabilities.extendedSrgb) {
      return 0;
    }
    uint32_t count = 0;
    auto append = [&](VkFormat format, VkColorSpaceKHR colorspace) {
      if (count < capacity) {
        formats[count++] = { format, colorspace };
      }
    };
    if (wantsHdr() && !m_rejected && !m_unavailable) {
      append(VK_FORMAT_A2B10G10R10_UNORM_PACK32, VK_COLOR_SPACE_HDR10_ST2084_EXT);
      append(VK_FORMAT_A2R10G10B10_UNORM_PACK32, VK_COLOR_SPACE_HDR10_ST2084_EXT);
    }
    append(VK_FORMAT_B8G8R8A8_UNORM, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR);
    append(VK_FORMAT_R8G8B8A8_UNORM, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR);
    return count;
  }

  bool DxvkDisplayPolicy::needsSurfaceRecreation(VkSurfaceFormatKHR actual) const {
    const bool desired = wantsHdr() && !m_rejected && !m_unavailable;
    return desired != isHdrSurface(actual) || (desired && !m_confirmed);
  }

  void DxvkDisplayPolicy::acceptSurface(VkSurfaceFormatKHR actual) {
    if (actual.format != m_surface.format || actual.colorSpace != m_surface.colorSpace) {
      ++m_generation;
    }
    m_surface = actual;
    m_confirmed = wantsHdr() && isHdrSurface(actual) && !m_rejected;
    if (wantsHdr() && !isHdrSurface(actual) && !m_rejected) {
      m_unavailable = true;
    }
  }

  void DxvkDisplayPolicy::rejectHdrSurface() {
    if (!m_rejected) {
      ++m_generation;
    }
    m_rejected = true;
    m_confirmed = false;
  }

  DxvkDisplayPresent DxvkDisplayPolicy::present(const DxvkDisplayFrame& source, VkSurfaceFormatKHR actual,
                                                VkFormat clientFormat) const {
    const bool extendedSource = source.extendedSrgb()
      && (clientFormat == VK_FORMAT_UNDEFINED || isExtendedSrgbClientFormat(clientFormat));
    const bool hdrSource = extendedSource && source.hdrScene();
    const bool sourceLinear = isSrgbFormat(clientFormat);
    const bool targetSrgb = isSrgbFormat(actual.format);

    DxvkDisplayPresent result;
    result.sourceGeneration = source.generation;
    result.generation = m_generation;
    result.shader.frameIndex = source.frameIndex;
    result.shader.sourcePaperWhiteNits = source.paperWhiteNits;
    result.shader.sourcePeakNits = source.peakNits;
    if (isHdrSurface(actual)) {
      result.shader.transfer = uint32_t(DxvkDisplayTransfer::Hdr10);
      result.shader.targetPaperWhiteNits = m_paperWhiteNits;
      result.shader.targetPeakNits = m_peakNits;
    } else if (extendedSource || (source.extendedSrgb() && sourceLinear != targetSrgb)) {
      result.shader.transfer = uint32_t(hdrSource ? DxvkDisplayTransfer::HdrToSdr : DxvkDisplayTransfer::Sdr);
      if (targetSrgb) {
        result.shader.transfer |= kDxvkDisplayTargetSrgb;
      }
    }
    if (hdrSource) {
      result.shader.transfer |= kDxvkDisplaySourceHdr;
    }
    if (sourceLinear && (result.shader.transfer & kDxvkDisplayTransferMask)) {
      result.shader.transfer |= kDxvkDisplaySourceLinear;
    }
    return result;
  }

  const char* DxvkDisplayPolicy::reason() const {
    if (!m_capabilities.extendedSrgb) { return "legacy-client"; }
    if (m_settings.hdrMode != 1) { return "off"; }
    if (!m_capabilities.clientFormatKnown) { return "pending"; }
    if (!m_capabilities.monitor) { return "no-window"; }
    if (!m_capabilities.known) { return "query-failed"; }
    if (!m_capabilities.hdrEnabled) { return "os-hdr-disabled"; }
    if (!m_capabilities.swapchainColorspace) { return "colorspace-extension-unavailable"; }
    if (m_rejected) { return "swapchain-failed"; }
    if (m_unavailable) { return "hdr-surface-unavailable"; }
    return m_confirmed ? "active" : "pending";
  }

}
