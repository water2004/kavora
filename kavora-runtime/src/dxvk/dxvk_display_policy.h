// SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
// SPDX-License-Identifier: MIT
#pragma once

#include "dxvk_display_output.h"

namespace dxvk {

  struct DxvkDisplaySettings {
    uint32_t hdrMode = 1;
    float paperWhiteNits = 0.0f;
    float peakNits = 0.0f;
  };

  struct DxvkDisplayCapabilities {
    uintptr_t monitor = 0;
    bool extendedSrgb = false;
    bool swapchainColorspace = false;
    bool known = false;
    bool hdrEnabled = false;
    float paperWhiteNits = 200.0f;
    float peakNits = 1000.0f;
    bool clientFormatKnown = true;
  };

  // Platform queries and WSI results enter one testable state machine.
  class DxvkDisplayPolicy {
  public:
    void update(const DxvkDisplaySettings& settings, const DxvkDisplayCapabilities& capabilities);
    DxvkDisplayFrame frame(uint32_t frameIndex, bool sceneFrame) const;
    uint32_t surfaceFormats(VkSurfaceFormatKHR* formats, uint32_t capacity) const;
    bool needsSurfaceRecreation(VkSurfaceFormatKHR actual) const;
    void acceptSurface(VkSurfaceFormatKHR actual);
    void rejectHdrSurface();
    DxvkDisplayPresent present(const DxvkDisplayFrame& source, VkSurfaceFormatKHR actual,
                               VkFormat clientFormat = VK_FORMAT_UNDEFINED) const;

    bool wantsHdr() const;
    bool hdrActive() const;
    uint64_t generation() const { return m_generation; }
    float paperWhiteNits() const { return hdrActive() ? m_paperWhiteNits : 80.0f; }
    float peakNits() const { return hdrActive() ? m_peakNits : 80.0f; }
    const char* reason() const;
    static bool isHdrSurface(VkSurfaceFormatKHR format);
    static VkSurfaceFormatKHR selectSurfaceFormat(uint32_t supportedCount, const VkSurfaceFormatKHR* supported,
                                                 uint32_t desiredCount, const VkSurfaceFormatKHR* desired);

  private:
    DxvkDisplaySettings m_settings;
    DxvkDisplayCapabilities m_capabilities;
    VkSurfaceFormatKHR m_surface { VK_FORMAT_UNDEFINED, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR };
    uint64_t m_generation = 1;
    float m_paperWhiteNits = 200.0f;
    float m_peakNits = 1000.0f;
    bool m_confirmed = false;
    bool m_rejected = false;
    bool m_unavailable = false;
  };

}
