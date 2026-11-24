// SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
// SPDX-License-Identifier: MIT
#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <string_view>

#include <vulkan/vulkan_core.h>

namespace dxvk {

  class DxvkDevice;

  constexpr bool isExtendedSrgbClientFormat(VkFormat format) {
    return format == VK_FORMAT_R16G16B16A16_SFLOAT;
  }

  enum class DxvkDisplayFrameFlag : uint32_t {
    ExtendedSrgb = 1u << 0,
    HdrScene = 1u << 1,
  };

  struct DxvkDisplayFrame {
    uint64_t generation = 0;
    uint32_t frameIndex = 0;
    uint32_t flags = 0;
    float paperWhiteNits = 80.0f;
    float peakNits = 80.0f;

    bool extendedSrgb() const {
      return (flags & uint32_t(DxvkDisplayFrameFlag::ExtendedSrgb)) != 0;
    }

    bool hdrScene() const {
      return (flags & uint32_t(DxvkDisplayFrameFlag::HdrScene)) != 0;
    }

    float peakToWhite() const {
      return hdrScene() ? peakNits / paperWhiteNits : 1.0f;
    }
  };

  enum class DxvkDisplayTransfer : uint32_t {
    LegacySdr = 0,
    Sdr = 1,
    Hdr10 = 2,
    HdrToSdr = 3,
  };

  constexpr uint32_t kDxvkDisplayTransferMask = 0xffu;
  constexpr uint32_t kDxvkDisplayTargetSrgb = 1u << 8;
  constexpr uint32_t kDxvkDisplaySourceHdr = 1u << 9;
  constexpr uint32_t kDxvkDisplaySourceLinear = 1u << 10;

  struct DxvkDisplayPresentArgs {
    uint32_t transfer = uint32_t(DxvkDisplayTransfer::LegacySdr);
    uint32_t frameIndex = 0;
    float sourcePaperWhiteNits = 80.0f;
    float sourcePeakNits = 80.0f;
    float targetPaperWhiteNits = 80.0f;
    float targetPeakNits = 80.0f;
  };

  struct DxvkDisplayPresent {
    uint64_t sourceGeneration = 0;
    uint64_t generation = 0;
    DxvkDisplayPresentArgs shader;

    bool hdr10() const {
      return (shader.transfer & kDxvkDisplayTransferMask) == uint32_t(DxvkDisplayTransfer::Hdr10);
    }
  };

  static_assert(sizeof(DxvkDisplayFrame) == 24);
  static_assert(sizeof(DxvkDisplayPresentArgs) == 24);

  // Owns display capabilities, negotiated output and the frame's color contract.
  class DxvkDisplayOutput {
  public:
    explicit DxvkDisplayOutput(DxvkDevice* device);
    ~DxvkDisplayOutput();

    void configureHost(void* window, bool sharedVulkan);
    // Records whether the host has supplied an FP16 composition image.
    void observeClientFormat(VkFormat format);
    DxvkDisplayFrame prepareFrame(void* window, uint32_t frameIndex, bool sceneFrame = true);

    // The command-stream thread copies this before scene post-processing.
    void setRenderFrame(const DxvkDisplayFrame& frame);
    const DxvkDisplayFrame& renderFrame() const { return m_renderFrame; }

    uint32_t surfaceFormats(VkSurfaceFormatKHR* formats, uint32_t capacity) const;
    bool needsSurfaceRecreation(VkSurfaceFormatKHR actual) const;
    void acceptSurface(VkSurfaceFormatKHR actual);
    void rejectHdrSurface(VkResult failure);
    DxvkDisplayPresent makePresent(const DxvkDisplayFrame& source, VkSurfaceFormatKHR actual, VkFormat clientFormat) const;
    bool hdrMetadata(VkHdrMetadataEXT& metadata) const;
    bool readStatus(std::string_view key, std::string& value) const;

    static bool isStatusKey(std::string_view key) {
      return key.starts_with("rtx.display.status.");
    }
    static bool isHdrSurface(VkSurfaceFormatKHR format);

  private:
    struct Impl;
    std::unique_ptr<Impl> m_impl;
    DxvkDisplayFrame m_renderFrame;
  };

}
