/* SPDX-License-Identifier: MIT */
#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <limits>
#include <stdexcept>
#include <vector>

namespace dxvk::remixapiTexture {
  struct Rect {
    uint32_t left;
    uint32_t top;
    uint32_t right;
    uint32_t bottom;
  };

  inline uint32_t pixelSize(uint32_t format) {
    constexpr uint32_t sizes[] = { 4, 4, 4, 4, 1, 2, 8, 16, 4 };
    return format < std::size(sizes) ? sizes[format] : 0;
  }

  inline uint32_t channels(uint32_t format) {
    return format == 4 || format == 8 ? 1 : format == 5 ? 2 : 4;
  }

  inline float readHalf(uint16_t half) {
    float result = std::ldexp(float(half & 1023), -24);
    uint32_t exponent = (half >> 10) & 31;
    if (exponent == 31) {
      result = half & 1023 ? std::numeric_limits<float>::quiet_NaN() : std::numeric_limits<float>::infinity();
    } else if (exponent) {
      result = std::ldexp(float(1024 + (half & 1023)), int(exponent) - 25);
    }
    return half & 0x8000 ? -result : result;
  }

  inline float linearize(float value) {
    return value <= 0.04045f ? value / 12.92f : std::pow((value + 0.055f) / 1.055f, 2.4f);
  }

  inline float linearizeByte(uint8_t value) {
    static const std::array<float, 256> kLinear = [] {
      std::array<float, 256> values {};
      for (size_t i = 0; i < values.size(); i++) {
        values[i] = linearize(float(i) / 255.f);
      }
      return values;
    }();
    return kLinear[value];
  }

  inline std::array<float, 4> readPixel(const uint8_t* pData, uint32_t format, bool srgb) {
    std::array<float, 4> pixel { 0.f, 0.f, 0.f, 1.f };
    for (uint32_t c = 0; c < channels(format); c++) {
      if (format == 6) {
        uint16_t value;
        memcpy(&value, pData + c * 2, sizeof(value));
        pixel[c] = readHalf(value);
      } else if (format == 7 || format == 8) {
        memcpy(&pixel[c], pData + c * 4, sizeof(float));
      } else {
        pixel[c] = srgb && c < 3 ? linearizeByte(pData[c]) : pData[c] / 255.f;
      }
      if (srgb && c < 3 && format >= 6) {
        pixel[c] = linearize(pixel[c]);
      }
    }
    return pixel;
  }

  inline void encodeNormal(uint8_t* pOutput, float x, float y, float z) {
    float sum = std::abs(x) + std::abs(y) + std::max(0.f, z);
    if (!std::isfinite(sum) || sum < 1.e-20f) {
      pOutput[0] = pOutput[1] = 128;
      return;
    }
    float nx = (x + y) / sum * 0.5f + 0.5f;
    float ny = (x - y) / sum * 0.5f + 0.5f;
    pOutput[0] = uint8_t(std::lround(std::clamp(nx, 0.f, 1.f) * 255.f));
    pOutput[1] = uint8_t(std::lround(std::clamp(ny, 0.f, 1.f) * 255.f));
  }

  struct LevelSize {
    uint32_t width;
    uint32_t height;
  };

  struct TextureInfo {
    uint32_t format = 0;
    uint32_t purpose = 0;
    bool authored = false;
    std::vector<LevelSize> levels;

    TextureInfo() = default;
    TextureInfo(uint32_t width, uint32_t height, uint32_t sourceFormat, uint32_t texturePurpose, uint32_t authoredMipCount = 0)
      : format(texturePurpose >= 2 && texturePurpose <= 4 ? 5 : sourceFormat), purpose(texturePurpose), authored(authoredMipCount > 0) {
      if (!width || !height || !pixelSize(sourceFormat) || purpose > 5
          || (purpose == 4 && sourceFormat != 5) || ((purpose == 2 || purpose == 3) && (channels(sourceFormat) < 2 || authored))) {
        throw std::invalid_argument("Invalid texture format or dimensions");
      }
      if (uint64_t(width) * height > std::numeric_limits<size_t>::max() / pixelSize(format)) {
        throw std::invalid_argument("Texture data size overflow");
      }
      for (;;) {
        levels.push_back({ width, height });
        if ((width == 1 && height == 1) || (authored && levels.size() == authoredMipCount)) {
          break;
        }
        width = std::max(1u, width / 2);
        height = std::max(1u, height / 2);
      }
      if (authored && levels.size() != authoredMipCount) {
        throw std::invalid_argument("Invalid authored mip count");
      }
    }

    bool validPatch(uint32_t mip, uint32_t x, uint32_t y, uint32_t width, uint32_t height, uint32_t rowPitch, uint32_t patchFormat) const {
      if (mip >= levels.size() || (!authored && mip)) {
        return false;
      }
      const auto& level = levels[mip];
      return patchFormat == format && width && height && x <= level.width && y <= level.height
        && width <= level.width - x && height <= level.height - y && uint64_t(rowPitch) >= uint64_t(width) * pixelSize(format);
    }
  };
}
