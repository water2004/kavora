/* SPDX-License-Identifier: MIT */
#pragma once
#include "../../../src/dxvk/rtx_render/rtx_remix_texture_data.h"
namespace dxvk::remixapiTexture {
  struct MipRegion {
    uint32_t mip;
    Rect rect;
  };

  inline uint64_t area(Rect rect) {
    return uint64_t(rect.right - rect.left) * (rect.bottom - rect.top);
  }

  // Coalescing must not turn sparse atlas writes into a large enclosing rectangle.
  inline void coalesce(std::vector<Rect>& rects) {
    for (size_t i = 0; i < rects.size(); i++) {
      for (size_t j = i + 1; j < rects.size();) {
        const auto a = rects[i];
        const auto b = rects[j];
        Rect combined { std::min(a.left, b.left), std::min(a.top, b.top),
          std::max(a.right, b.right), std::max(a.bottom, b.bottom) };
        bool touching = a.left <= b.right && b.left <= a.right && a.top <= b.bottom && b.top <= a.bottom;
        if (touching && area(combined) - area(a) <= area(b)) {
          rects[i] = combined;
          rects[j] = rects.back();
          rects.pop_back();
          j = i + 1;
        } else {
          j++;
        }
      }
    }
  }

  inline uint16_t writeHalf(float value) {
    uint32_t bits;
    memcpy(&bits, &value, sizeof(bits));
    uint32_t sign = (bits >> 16) & 0x8000;
    uint32_t fraction = bits & 0x7fffff;
    int exponent = int((bits >> 23) & 255) - 127 + 15;
    if (((bits >> 23) & 255) == 255) {
      return uint16_t(sign | 0x7c00 | (fraction ? 0x200 : 0));
    }
    if (exponent <= 0) {
      if (exponent < -10) {
        return uint16_t(sign);
      }
      fraction |= 0x800000;
      uint32_t shift = uint32_t(14 - exponent);
      uint32_t rounded = (fraction + ((1u << (shift - 1)) - 1) + ((fraction >> shift) & 1)) >> shift;
      return uint16_t(sign | rounded);
    }
    if (exponent >= 31) {
      return uint16_t(sign | 0x7c00);
    }
    fraction += 0xfff + ((fraction >> 13) & 1);
    if (fraction & 0x800000) {
      fraction = 0;
      exponent++;
    }
    return uint16_t(sign | (uint32_t(exponent) << 10) | (fraction >> 13));
  }

  inline float encodeSrgb(float value) {
    return value <= 0.0031308f ? value * 12.92f : 1.055f * std::pow(value, 1.f / 2.4f) - 0.055f;
  }

  inline void writePixel(uint8_t* pData, uint32_t format, std::array<float, 4> pixel, bool srgb) {
    for (uint32_t c = 0; c < channels(format); c++) {
      if (srgb && c < 3) {
        pixel[c] = encodeSrgb(pixel[c]);
      }
      if (format == 6) {
        uint16_t half = writeHalf(pixel[c]);
        memcpy(pData + c * 2, &half, sizeof(half));
      } else if (format == 7 || format == 8) {
        memcpy(pData + c * 4, &pixel[c], sizeof(float));
      } else {
        pData[c] = uint8_t(std::lround(std::clamp(pixel[c], 0.f, 1.f) * 255.f));
      }
    }
  }

  inline std::array<float, 3> decodeNormal(const uint8_t* pData) {
    float x = pData[0] / 255.f * 2.f - 1.f;
    float y = pData[1] / 255.f * 2.f - 1.f;
    float tx = (x + y) * 0.5f;
    float ty = (x - y) * 0.5f;
    return { tx, ty, std::max(0.f, 1.f - std::abs(tx) - std::abs(ty)) };
  }

  struct Level {
    uint32_t width;
    uint32_t height;
    std::vector<uint8_t> pixels;
  };

  class TextureData {
  public:
    TextureData(uint32_t width, uint32_t height, uint32_t format, uint32_t purpose, const uint8_t* pPixels, uint32_t authoredMipCount = 0)
      : m_format(purpose >= 2 && purpose <= 4 ? 5 : format), m_purpose(purpose) {
      if (!width || !height || !pixelSize(format) || purpose > 5 || !pPixels) {
        throw std::invalid_argument("Invalid texture data");
      }
      if (size_t(width) > std::numeric_limits<size_t>::max() / height / pixelSize(m_format)) {
        throw std::invalid_argument("Texture data size overflow");
      }
      if (authoredMipCount && (purpose == 2 || purpose == 3)) {
        throw std::invalid_argument("Authored mip chains require canonical texture data");
      }
      if (purpose == 4 && format != 5) {
        throw std::invalid_argument("Octahedral normals require RG8");
      }
      uint32_t w = width;
      uint32_t h = height;
      while (true) {
        m_levels.push_back({ w, h, std::vector<uint8_t>(size_t(w) * h * pixelSize(m_format)) });
        if ((w == 1 && h == 1) || (authoredMipCount && m_levels.size() == authoredMipCount)) {
          break;
        }
        w = std::max(1u, w / 2);
        h = std::max(1u, h / 2);
      }
      if (authoredMipCount) {
        if (m_levels.size() != authoredMipCount) {
          throw std::invalid_argument("Authored mip count exceeds texture dimensions");
        }
        for (auto& level : m_levels) {
          memcpy(level.pixels.data(), pPixels, level.pixels.size());
          pPixels += level.pixels.size();
        }
        m_authoredMips = true;
        return;
      }
      auto& base = m_levels[0];
      if (purpose == 2 || purpose == 3) {
        if (channels(format) < 2) {
          throw std::invalid_argument("Normal map needs at least two channels");
        }
        for (size_t i = 0; i < size_t(width) * height; i++) {
          auto pixel = readPixel(pPixels + i * pixelSize(format), format, false);
          if (format == 2 || format == 3) {
            std::swap(pixel[0], pixel[2]);
          }
          float nx = pixel[0] * 2.f - 1.f;
          float ny = (pixel[1] * 2.f - 1.f) * (purpose == 3 ? -1.f : 1.f);
          float nz = channels(format) > 2 ? pixel[2] * 2.f - 1.f : std::sqrt(std::max(0.f, 1.f - nx * nx - ny * ny));
          encodeNormal(base.pixels.data() + i * 2, nx, ny, nz);
        }
      } else {
        memcpy(base.pixels.data(), pPixels, base.pixels.size());
      }
      regenerate({ { 0, 0, width, height } });
    }

    uint32_t format() const { return m_format; }
    const std::vector<Level>& levels() const { return m_levels; }
    bool hasAuthoredMips() const { return m_authoredMips; }

    bool validMipPatch(uint32_t mip, uint32_t x, uint32_t y, uint32_t width, uint32_t height, uint32_t rowPitch, uint32_t format) const {
      if (mip >= m_levels.size() || (!m_authoredMips && mip)) {
        return false;
      }
      const auto& level = m_levels[mip];
      return format == m_format && width && height && x <= level.width && y <= level.height &&
        width <= level.width - x && height <= level.height - y && uint64_t(rowPitch) >= uint64_t(width) * pixelSize(format);
    }

    bool validPatch(uint32_t x, uint32_t y, uint32_t width, uint32_t height, uint32_t rowPitch, uint32_t format) const {
      auto& base = m_levels[0];
      return format == m_format && width && height && x <= base.width && y <= base.height &&
        width <= base.width - x && height <= base.height - y &&
        uint64_t(rowPitch) >= uint64_t(width) * pixelSize(format);
    }

    void patch(Rect rect, const uint8_t* pPixels, uint32_t rowPitch) {
      patchMip(0, rect, pPixels, rowPitch);
    }

    void patchMip(uint32_t mip, Rect rect, const uint8_t* pPixels, uint32_t rowPitch) {
      auto& base = m_levels[mip];
      uint32_t bytes = pixelSize(m_format);
      for (uint32_t y = rect.top; y < rect.bottom; y++) {
        memcpy(base.pixels.data() + (size_t(y) * base.width + rect.left) * bytes,
          pPixels + size_t(y - rect.top) * rowPitch, size_t(rect.right - rect.left) * bytes);
      }
    }

    std::vector<MipRegion> regenerate(std::vector<Rect> rects) {
      std::vector<MipRegion> updates;
      for (size_t mip = 0; mip < m_levels.size(); mip++) {
        coalesce(rects);
        for (Rect rect : rects) {
          updates.push_back({ uint32_t(mip), rect });
          if (mip != 0) {
            regenerateLevel(mip, rect);
          }
        }
        if (mip + 1 == m_levels.size()) {
          break;
        }
        const auto& source = m_levels[mip];
        const auto& target = m_levels[mip + 1];
        for (auto& rect : rects) {
          // Proportional footprints include the final texel of odd-sized levels.
          rect = { uint32_t(uint64_t(rect.left) * target.width / source.width),
            uint32_t(uint64_t(rect.top) * target.height / source.height),
            std::min(target.width, uint32_t((uint64_t(rect.right) * target.width + source.width - 1) / source.width)),
            std::min(target.height, uint32_t((uint64_t(rect.bottom) * target.height + source.height - 1) / source.height)) };
        }
      }
      return updates;
    }

  private:
    bool m_authoredMips = false;

    template<uint32_t ChannelCount, bool Peak>
    void regenerateByteLevel2x2(size_t mip, Rect rect) {
      const auto& source = m_levels[mip - 1];
      auto& target = m_levels[mip];
      for (uint32_t y = rect.top; y < rect.bottom; y++) {
        const auto* upper = source.pixels.data() + (size_t(y) * 2 * source.width + rect.left * 2) * ChannelCount;
        const auto* lower = upper + size_t(source.width) * ChannelCount;
        auto* output = target.pixels.data() + (size_t(y) * target.width + rect.left) * ChannelCount;
        for (uint32_t x = rect.left; x < rect.right; x++, upper += ChannelCount * 2, lower += ChannelCount * 2, output += ChannelCount) {
          for (uint32_t c = 0; c < ChannelCount; c++) {
            if constexpr (Peak) {
              output[c] = std::max(std::max(upper[c], upper[c + ChannelCount]), std::max(lower[c], lower[c + ChannelCount]));
            } else {
              // Keep the generic filter's accumulation order and UNORM rounding.
              float sum = upper[c] / 255.f;
              sum += upper[c + ChannelCount] / 255.f;
              sum += lower[c] / 255.f;
              sum += lower[c + ChannelCount] / 255.f;
              float value = std::clamp(sum / 4.f, 0.f, 1.f) * 255.f;
              // A double offset preserves float half-boundaries before truncation.
              output[c] = uint8_t(double(value) + 0.5);
            }
          }
        }
      }
    }

    void regenerateLevel(size_t mip, Rect rect) {
      uint32_t bytes = pixelSize(m_format);
      bool normal = m_purpose >= 2 && m_purpose <= 4;
      bool srgb = (m_format == 1 || m_format == 3) && m_purpose == 0;
      const auto& source = m_levels[mip - 1];
      auto& target = m_levels[mip];
      if (!normal && !srgb && m_format <= 5 && source.width == target.width * 2 && source.height == target.height * 2) {
        uint32_t count = channels(m_format);
        if (m_purpose == 5) {
          if (count == 1) regenerateByteLevel2x2<1, true>(mip, rect);
          else if (count == 2) regenerateByteLevel2x2<2, true>(mip, rect);
          else regenerateByteLevel2x2<4, true>(mip, rect);
        } else {
          if (count == 1) regenerateByteLevel2x2<1, false>(mip, rect);
          else if (count == 2) regenerateByteLevel2x2<2, false>(mip, rect);
          else regenerateByteLevel2x2<4, false>(mip, rect);
        }
        return;
      }
      for (uint32_t y = rect.top; y < rect.bottom; y++) {
        for (uint32_t x = rect.left; x < rect.right; x++) {
          uint32_t left = uint32_t(uint64_t(x) * source.width / target.width);
          uint32_t right = uint32_t(uint64_t(x + 1) * source.width / target.width);
          uint32_t top = uint32_t(uint64_t(y) * source.height / target.height);
          uint32_t bottom = uint32_t(uint64_t(y + 1) * source.height / target.height);
          std::array<float, 4> sum {};
          if (m_purpose == 5) {
            sum.fill(-std::numeric_limits<float>::infinity());
          }
          uint32_t count = 0;
          for (uint32_t sy = top; sy < bottom; sy++) {
            for (uint32_t sx = left; sx < right; sx++) {
              const auto pPixel = source.pixels.data() + (size_t(sy) * source.width + sx) * bytes;
              auto pixel = readPixel(pPixel, m_format, srgb);
              if (normal) {
                auto n = decodeNormal(pPixel);
                float length = std::sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
                for (uint32_t c = 0; c < 3; c++) {
                  pixel[c] = length > 0.f ? n[c] / length : float(c == 2);
                }
              }
              for (uint32_t c = 0; c < 4; c++) {
                sum[c] = m_purpose == 5 ? std::max(sum[c], pixel[c]) : sum[c] + pixel[c];
              }
              count++;
            }
          }
          auto pTarget = target.pixels.data() + (size_t(y) * target.width + x) * bytes;
          if (normal) {
            encodeNormal(pTarget, sum[0], sum[1], sum[2]);
          } else {
            if (m_purpose != 5) {
              for (auto& value : sum) {
                value /= count;
              }
            }
            writePixel(pTarget, m_format, sum, srgb);
          }
        }
      }
    }

    uint32_t m_format;
    uint32_t m_purpose;
    std::vector<Level> m_levels;
  };
}
