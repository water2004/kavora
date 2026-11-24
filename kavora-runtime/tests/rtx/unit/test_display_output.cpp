// SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
// SPDX-License-Identifier: MIT

#include <cmath>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <string>

#include "../../../src/dxvk/dxvk_display_policy.h"

namespace {
  using namespace dxvk;
  constexpr VkSurfaceFormatKHR kSdr { VK_FORMAT_B8G8R8A8_UNORM, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR };
  constexpr VkSurfaceFormatKHR kHdr { VK_FORMAT_A2B10G10R10_UNORM_PACK32, VK_COLOR_SPACE_HDR10_ST2084_EXT };

  void require(bool condition, const char* message) {
    if (!condition) {
      throw std::runtime_error(message);
    }
  }

  void checkTransitions() {
    DxvkDisplayPolicy policy;
    DxvkDisplaySettings settings;
    DxvkDisplayCapabilities caps { 1, true, true, true, true, 200.0f, 1000.0f };
    policy.update(settings, caps);
    auto first = policy.frame(10, true);
    require(first.extendedSrgb() && !first.hdrScene(), "First HDR request must keep an SDR-range source.");
    require(policy.needsSurfaceRecreation(kSdr), "HDR request must negotiate a new surface.");
    policy.acceptSurface(kHdr);
    const auto initialPresent = policy.present(first, kHdr);
    require(initialPresent.hdr10() && initialPresent.shader.targetPaperWhiteNits == 200.0f,
      "An SDR-range first frame must use the HDR target's paper white.");
    require(!(initialPresent.shader.transfer & kDxvkDisplaySourceHdr), "The first source is not already HDR.");
    auto hdr = policy.frame(11, true);
    require(hdr.hdrScene() && hdr.peakToWhite() == 5.0f, "Confirmed HDR must preserve headroom.");
    require(!policy.frame(11, false).hdrScene(), "Menus must retain their SDR-range source contract.");

    const auto generation = policy.generation();
    settings.paperWhiteNits = 250.0f;
    policy.update(settings, caps);
    require(policy.generation() > generation && !policy.needsSurfaceRecreation(kHdr),
      "White calibration resets history without recreating WSI.");
    const auto calibration = policy.present(hdr, kHdr);
    require(calibration.shader.sourcePaperWhiteNits == 200.0f
      && calibration.shader.targetPaperWhiteNits == 250.0f
      && (calibration.shader.transfer & kDxvkDisplaySourceHdr), "A late setting change must retain source units.");

    caps.monitor = 2;
    caps.peakNits = 400.0f;
    policy.update(settings, caps);
    require(!policy.frame(12, true).hdrScene() && policy.needsSurfaceRecreation(kHdr),
      "A new monitor needs a fresh HDR confirmation even if the pixel format matches.");
    policy.acceptSurface(kHdr);
    const auto moved = policy.present(hdr, kHdr);
    require(moved.shader.sourcePeakNits == 1000.0f && moved.shader.targetPeakNits == 400.0f,
      "The transition must expose both peaks for safe final remapping.");

    caps.hdrEnabled = false;
    policy.update(settings, caps);
    require(!policy.frame(13, true).hdrScene() && policy.needsSurfaceRecreation(kHdr),
      "Disabling OS HDR must request SDR and revoke scene headroom.");
    const auto late = policy.present(hdr, kSdr);
    require((late.shader.transfer & kDxvkDisplayTransferMask) == uint32_t(DxvkDisplayTransfer::HdrToSdr),
      "An existing HDR source must tone-map on late SDR fallback.");
    policy.acceptSurface(kSdr);
    require(std::string(policy.reason()) == "os-hdr-disabled", "The OS fallback reason must be explicit.");
  }

  void checkFailures() {
    DxvkDisplayPolicy policy;
    DxvkDisplaySettings settings;
    DxvkDisplayCapabilities caps { 1, true, true, true, true, 200.0f, 1000.0f };
    policy.update(settings, caps);
    policy.rejectHdrSurface();
    policy.acceptSurface(kSdr);
    VkSurfaceFormatKHR formats[4] {};
    require(policy.surfaceFormats(formats, 4) == 2 && formats[0].colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR,
      "After one HDR failure only SDR formats may be retried.");
    policy.update(settings, caps);
    require(!policy.needsSurfaceRecreation(kSdr) && std::string(policy.reason()) == "swapchain-failed",
      "A failed HDR surface must not retry every frame.");
    settings.hdrMode = 0;
    policy.update(settings, caps);
    settings.hdrMode = 1;
    policy.update(settings, caps);
    require(policy.needsSurfaceRecreation(kSdr), "An explicit mode change must allow a new HDR attempt.");
    policy.acceptSurface(kSdr);
    require(!policy.needsSurfaceRecreation(kSdr) && std::string(policy.reason()) == "hdr-surface-unavailable",
      "Missing HDR formats must settle on SDR without recreation loops.");
    caps.extendedSrgb = false;
    policy.update(settings, caps);
    require(!policy.frame(0, true).extendedSrgb() && policy.surfaceFormats(formats, 4) == 0,
      "Legacy clients retain their SDR contract and format preferences.");
    caps.extendedSrgb = true;
    caps.swapchainColorspace = false;
    policy.update(settings, caps);
    require(!policy.wantsHdr(), "An unsupported instance color-space extension cannot enable HDR.");
    caps.swapchainColorspace = true;
    caps.paperWhiteNits = std::numeric_limits<float>::quiet_NaN();
    caps.peakNits = std::numeric_limits<float>::infinity();
    settings.paperWhiteNits = -1.0f;
    settings.peakNits = std::numeric_limits<float>::quiet_NaN();
    policy.update(settings, caps);
    policy.acceptSurface(kHdr);
    const auto safe = policy.frame(1, true);
    require(safe.paperWhiteNits == 200.0f && safe.peakNits == 1000.0f,
      "Missing or invalid luminance reports must use finite documented defaults.");
    settings.paperWhiteNits = 10000.0f;
    settings.peakNits = 80.0f;
    policy.update(settings, caps);
    require(policy.frame(2, true).peakToWhite() == 1.0f
      && (policy.present(policy.frame(2, true), kHdr).shader.transfer & kDxvkDisplaySourceHdr),
      "Equal peak and white still require an explicit HDR source flag.");
  }

  void checkClientConfirmation() {
    DxvkDisplayPolicy policy;
    DxvkDisplaySettings settings;
    DxvkDisplayCapabilities caps { 1, true, true, true, true, 200.0f, 1000.0f, false };
    policy.update(settings, caps);
    VkSurfaceFormatKHR formats[4] {};
    require(policy.frame(0, true).extendedSrgb() && !policy.frame(0, true).hdrScene(),
      "An unconfirmed shared client may encode SDR into FP16 but cannot receive HDR scene values.");
    require(!policy.wantsHdr() && policy.surfaceFormats(formats, 4) == 2
      && formats[0].colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR,
      "The first client format must be known before requesting HDR WSI.");
    policy.acceptSurface(kSdr);
    require(std::string(policy.reason()) == "pending", "The first format confirmation is pending, not unsupported.");
    caps.extendedSrgb = false;
    caps.clientFormatKnown = true;
    policy.update(settings, caps);
    require(!policy.wantsHdr() && !policy.frame(1, true).extendedSrgb()
      && !policy.needsSurfaceRecreation(kSdr) && policy.surfaceFormats(formats, 4) == 0,
      "An eight-bit host remains on SDR until it supplies an FP16 composition image.");

    DxvkDisplayPolicy floating;
    caps.extendedSrgb = true;
    floating.update(settings, caps);
    require(floating.wantsHdr() && !floating.frame(2, true).hdrScene(),
      "An FP16 confirmation still needs successful WSI negotiation before rendering HDR.");
    floating.acceptSurface(kHdr);
    require(floating.frame(3, true).hdrScene(), "Confirmed FP16 and WSI together permit HDR scene values.");
  }

  void checkMixedClientFormats() {
    DxvkDisplayPolicy policy;
    DxvkDisplaySettings settings;
    DxvkDisplayCapabilities caps { 1, true, true, true, true, 200.0f, 1000.0f };
    policy.update(settings, caps);
    policy.acceptSurface(kHdr);
    const auto source = policy.frame(7, true);
    const auto generation = policy.generation();
    const auto preview = policy.present(source, kHdr, VK_FORMAT_R8G8B8A8_UNORM);
    require(preview.hdr10() && !(preview.shader.transfer & kDxvkDisplaySourceHdr)
      && preview.shader.targetPaperWhiteNits == 200.0f,
      "An SDR composition must use the HDR window's reference white and ignore the original HDR scene metadata.");
    const auto srgb = policy.present(source, kHdr, VK_FORMAT_R8G8B8A8_SRGB);
    require(srgb.shader.transfer == (uint32_t(DxvkDisplayTransfer::Hdr10) | kDxvkDisplaySourceLinear),
      "A sampled sRGB format must skip software decoding when presented in HDR.");
    const auto sdr = policy.present(source, kSdr, VK_FORMAT_R8G8B8A8_UNORM);
    require(sdr.shader.transfer == uint32_t(DxvkDisplayTransfer::LegacySdr),
      "An eight-bit SDR composition must not be tone mapped again.");
    constexpr VkSurfaceFormatKHR kSrgb { VK_FORMAT_B8G8R8A8_SRGB, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR };
    const auto hardware = policy.present(source, kSrgb, VK_FORMAT_R8G8B8A8_UNORM);
    require(hardware.shader.transfer == (uint32_t(DxvkDisplayTransfer::Sdr) | kDxvkDisplayTargetSrgb),
      "An encoded SDR source must reach an sRGB framebuffer without a second encode.");
    const auto restored = policy.present(source, kHdr, VK_FORMAT_R16G16B16A16_SFLOAT);
    require(restored.hdr10() && (restored.shader.transfer & kDxvkDisplaySourceHdr)
      && policy.generation() == generation && !policy.needsSurfaceRecreation(kHdr),
      "Changing composition formats must preserve the negotiated HDR surface and let HDR scenes resume.");
  }

  void checkFormats() {
    VkSurfaceFormatKHR desired[] { kHdr, kSdr };
    VkSurfaceFormatKHR unsupported[] {
      { VK_FORMAT_A2B10G10R10_UNORM_PACK32, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR },
      kSdr,
    };
    auto result = DxvkDisplayPolicy::selectSurfaceFormat(2, unsupported, 2, desired);
    require(result.format == kSdr.format && result.colorSpace == kSdr.colorSpace,
      "A matching 10-bit format with SDR color space is not HDR10.");
    VkSurfaceFormatKHR undefined[] { { VK_FORMAT_UNDEFINED, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR } };
    result = DxvkDisplayPolicy::selectSurfaceFormat(1, undefined, 2, desired);
    require(result.format == kSdr.format && result.colorSpace == kSdr.colorSpace,
      "Undefined format support does not grant an unreported color space.");
    VkSurfaceFormatKHR supported[] { kSdr, kHdr };
    require(DxvkDisplayPolicy::isHdrSurface(DxvkDisplayPolicy::selectSurfaceFormat(2, supported, 2, desired)),
      "An exact HDR10 pair must be preferred when requested.");
    require(DxvkDisplayPolicy::selectSurfaceFormat(0, nullptr, 2, desired).format == VK_FORMAT_UNDEFINED,
      "An empty surface format list must fail safely.");
    VkSurfaceFormatKHR wrong[] { { VK_FORMAT_R16G16B16A16_SFLOAT, VK_COLOR_SPACE_EXTENDED_SRGB_LINEAR_EXT } };
    require(DxvkDisplayPolicy::selectSurfaceFormat(1, wrong, 2, desired).format == VK_FORMAT_UNDEFINED,
      "scRGB cannot silently substitute for the requested HDR10 or SDR encoding.");
  }
}

int main() {
  try {
    checkTransitions();
    checkFailures();
    checkClientConfirmation();
    checkMixedClientFormats();
    checkFormats();
    std::cout << "PASS display policy: negotiated generations, HDR transitions, bounded fallback and exact surface pairs\n";
    return 0;
  } catch (const std::exception& error) {
    std::cerr << error.what() << '\n';
    return 1;
  }
}
