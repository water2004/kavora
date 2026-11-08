/* SPDX-License-Identifier: MIT */
#include "texture_mip_reference.h"
#include "../../../src/dxvk/shaders/rtx/utility/atlas_mip_bounds.h"

#include <cstdio>
#include <cstdlib>
#include <random>

namespace {
  void require(bool value, const char* pMessage) {
    if (!value) {
      fprintf(stderr, "FAIL: %s\n", pMessage);
      exit(1);
    }
  }
}

int main() {
  using namespace dxvk::remixapiTexture;
  {
    std::mt19937 random(134);
    for (uint32_t format = 0; format < 6; format++) {
      for (uint32_t purpose : { 0u, 1u, 5u }) {
        for (uint32_t width : { 1u, 2u, 3u, 4u, 8u, 16u, 31u }) {
          for (uint32_t height : { 1u, 2u, 3u, 4u, 8u, 16u, 33u }) {
            uint32_t bytes = pixelSize(format);
            std::vector<uint8_t> pixels(size_t(width) * height * bytes);
            for (auto& value : pixels) value = uint8_t(random());
            TextureData texture(width, height, format, purpose, pixels.data());
            Level source { width, height, std::move(pixels) };
            bool srgb = (format == 1 || format == 3) && purpose == 0;
            for (size_t mip = 1; mip < texture.levels().size(); mip++) {
              const auto& actual = texture.levels()[mip];
              Level expected { actual.width, actual.height, std::vector<uint8_t>(actual.pixels.size()) };
              for (uint32_t y = 0; y < expected.height; y++) {
                for (uint32_t x = 0; x < expected.width; x++) {
                  uint32_t left = uint32_t(uint64_t(x) * source.width / expected.width);
                  uint32_t right = uint32_t(uint64_t(x + 1) * source.width / expected.width);
                  uint32_t top = uint32_t(uint64_t(y) * source.height / expected.height);
                  uint32_t bottom = uint32_t(uint64_t(y + 1) * source.height / expected.height);
                  std::array<float, 4> sum {};
                  if (purpose == 5) sum.fill(-std::numeric_limits<float>::infinity());
                  uint32_t count = 0;
                  for (uint32_t sy = top; sy < bottom; sy++) {
                    for (uint32_t sx = left; sx < right; sx++) {
                      auto pixel = readPixel(source.pixels.data() + (size_t(sy) * source.width + sx) * bytes, format, srgb);
                      for (uint32_t c = 0; c < 4; c++) sum[c] = purpose == 5 ? std::max(sum[c], pixel[c]) : sum[c] + pixel[c];
                      count++;
                    }
                  }
                  if (purpose != 5) for (auto& value : sum) value /= count;
                  writePixel(expected.pixels.data() + (size_t(y) * expected.width + x) * bytes, format, sum, srgb);
                }
              }
              require(expected.pixels == actual.pixels, "specialized byte mips match the scalar proportional filter exactly");
              source = std::move(expected);
            }
          }
        }
      }
    }
  }
  {
    std::vector<uint8_t> packed(8 * 8 * 4 + 4 * 4 * 4 + 2 * 2 * 4);
    for (size_t i = 0; i < packed.size(); i++) {
      packed[i] = uint8_t(i * 97 + i / 5);
    }
    TextureData authored(8, 8, 1, 0, packed.data(), 3);
    require(authored.hasAuthoredMips() && authored.levels().size() == 3, "authored chains keep their supplied level count");
    size_t offset = 0;
    for (const auto& level : authored.levels()) {
      require(memcmp(level.pixels.data(), packed.data() + offset, level.pixels.size()) == 0,
        "authored colors and alpha survive byte-for-byte without filtering");
      offset += level.pixels.size();
    }
    auto previous = authored.levels();
    const uint8_t changed[] = { 11, 19, 23, 0 };
    require(authored.validMipPatch(1, 2, 3, 1, 1, 4, 1), "an authored mip accepts an in-bounds update");
    require(!authored.validMipPatch(1, 4, 3, 1, 1, 4, 1) && !authored.validMipPatch(3, 0, 0, 1, 1, 4, 1),
      "authored patch validation uses the selected level's dimensions");
    authored.patchMip(1, { 2, 3, 3, 4 }, changed, 4);
    require(memcmp(authored.levels()[1].pixels.data() + (3 * 4 + 2) * 4, changed, 4) == 0, "authored update keeps transparent texels");
    require(authored.levels()[0].pixels == previous[0].pixels && authored.levels()[2].pixels == previous[2].pixels,
      "patching one authored mip does not replace any other level");
    TextureData generated(8, 8, 1, 0, packed.data());
    require(!generated.validMipPatch(1, 0, 0, 1, 1, 4, 1), "generated textures retain mip-zero update semantics");
    bool rejected = false;
    try {
      TextureData invalid(8, 8, 1, 0, packed.data(), 5);
    } catch (const std::invalid_argument&) {
      rejected = true;
    }
    require(rejected, "authored counts cannot exceed the full chain");
  }
  for (uint32_t value = 0; value < 256; value++) {
    float expected = linearize(float(value) / 255.f);
    float actual = linearizeByte(uint8_t(value));
    require(memcmp(&expected, &actual, sizeof(float)) == 0, "byte sRGB decoding is bit-identical to the scalar transfer function");
    for (uint32_t format : { 0u, 1u, 2u, 3u, 4u, 5u }) {
      uint8_t encoded[] = { uint8_t(value), uint8_t(255 - value), uint8_t(value ^ 0x55), uint8_t(value) };
      auto decoded = readPixel(encoded, format, true);
      for (uint32_t c = 0; c < channels(format); c++) {
        expected = encoded[c] / 255.f;
        if (c < 3) {
          expected = linearize(expected);
        }
        require(memcmp(&expected, &decoded[c], sizeof(float)) == 0, "cached decoding preserves every channel and linear alpha");
      }
    }
  }
  {
    require(AtlasSampling::displacementScale(0.f, 0.f, 0.f, 0.f) == 1.f,
      "standalone texture displacement preserves its full UV scale");
    for (float extent : { 1.f / 16.f, 1.f / 64.f, 1.f / 256.f }) {
      const float scale = AtlasSampling::displacementScale(0.5f, 0.25f, 0.5f + extent, 0.25f + extent);
      require(0.25f * scale / extent == 0.25f,
        "quarter-tile displacement stays a quarter tile at every atlas size");
      require(AtlasSampling::displacementScale(0.f, 0.f, extent * 2.f, extent) == extent,
        "rectangular sprites preserve the tangent basis aspect ratio");
    }
    require(AtlasSampling::maximumMip(2322.f / 4096.f, 2926.f / 4096.f,
      2386.f / 4096.f, 2990.f / 4096.f, 4096.f, 4096.f) == 5.f,
      "padded 64-pixel sprite cannot sample a neighboring sprite's mip-6 footprint");
    require(AtlasSampling::maximumMip(2304.f / 4096.f, 2880.f / 4096.f,
      2368.f / 4096.f, 2944.f / 4096.f, 4096.f, 4096.f) == 6.f,
      "aligned sprites retain their complete mip chain");
    for (int extent = 1; extent <= 12; extent++) {
      for (int origin = 0; origin + extent <= 16; origin++) {
        float lo = origin / 16.f, hi = (origin + extent) / 16.f;
        float lastMip = AtlasSampling::maximumMip(lo, lo, hi, hi, 16.f, 16.f);
        for (float mip = 0; mip <= lastMip; mip++) {
          float first = AtlasSampling::firstCenter(lo, 16.f, mip);
          float last = AtlasSampling::lastCenter(hi, 16.f, mip);
          float halfCell = std::exp2(mip) / 32.f;
          require(first <= last && first - halfCell >= lo && last + halfCell <= hi,
            "every allowed mip has complete texel footprints within the sprite");
        }
      }
    }
    std::vector<uint8_t> pixels(16 * 16 * 4);
    for (int y = 0; y < 16; y++) {
      for (int x = 0; x < 16; x++) {
        bool inside = x >= 3 && x < 11 && y >= 3 && y < 11;
        size_t offset = (y * 16 + x) * 4;
        pixels[offset] = inside ? 255 : 0;
        pixels[offset + 2] = inside ? 0 : 255;
        pixels[offset + 3] = 255;
      }
    }
    TextureData atlas(16, 16, 0, 1, pixels.data());
    auto& mip = atlas.levels()[2];
    require(mip.pixels[2] > 0, "base-level edge clamping samples the blue neighbor at a coarse mip");
    float coordinate = AtlasSampling::firstCenter(3.f / 16.f, 16.f, 2.f);
    size_t texel = size_t(coordinate * mip.width);
    size_t offset = (texel * mip.width + texel) * 4;
    require(mip.pixels[offset] == 255 && mip.pixels[offset + 2] == 0,
      "mip-aware edge clamping samples only the red sprite");
  }
  const uint8_t srgb[] = { 0, 0, 0, 255, 255, 255, 255, 255, 0, 0, 0, 255, 255, 255, 255, 255 };
  TextureData color(2, 2, 1, 0, srgb);
  require(color.levels().size() == 2 && color.levels()[1].pixels[0] == 188, "sRGB mip averages in linear space");
  require(color.levels()[1].pixels[3] == 255, "sRGB alpha is linear");

  const uint8_t height[] = { 0, 5, 250, 11, 0, 0, 0, 0, 0 };
  TextureData heightMap(3, 3, 4, 5, height);
  require(heightMap.levels().size() == 2 && heightMap.levels()[1].pixels[0] == 250, "height mip takes maximum including odd-sized edge");
  const uint8_t changed = 255;
  heightMap.patch({ 2, 2, 3, 3 }, &changed, 1);
  auto rects = heightMap.regenerate({ { 2, 2, 3, 3 } });
  require(heightMap.levels()[1].pixels[0] == 255 && rects[1].rect.right == 1, "edge patch updates parent maximum");

  const uint8_t normal[] = { 128, 128, 255, 255, 128, 128, 255, 255, 128, 128, 255, 255, 128, 128, 255, 255 };
  TextureData normalMap(2, 2, 0, 2, normal);
  require(normalMap.format() == 5 && normalMap.levels()[0].pixels.size() == 8, "DX normal converts into RG8 hemisphere octahedral encoding");
  auto n = decodeNormal(normalMap.levels()[1].pixels.data());
  require(std::abs(n[0]) < 0.02f && std::abs(n[1]) < 0.02f && n[2] > 0.98f, "normal mip stays normalized toward surface");
  const uint8_t tilted[] = { 128, 200, 230, 255 };
  TextureData dx(1, 1, 0, 2, tilted);
  TextureData ogl(1, 1, 0, 3, tilted);
  auto ndx = decodeNormal(dx.levels()[0].pixels.data());
  auto nogl = decodeNormal(ogl.levels()[0].pixels.data());
  require(ndx[1] > 0.f && nogl[1] < 0.f, "OpenGL normal Y is inverted into DirectX convention");

  std::vector<uint8_t> atlas(8 * 8, 0);
  TextureData data(8, 8, 4, 1, atlas.data());
  require(data.validPatch(6, 6, 2, 2, 4, 4), "padded row pitch accepted");
  require(!data.validPatch(7, 7, 2, 2, 2, 4), "out-of-bounds patch rejected");
  require(!data.validPatch(0, 0, 1, 1, 1, 5), "format mismatch rejected");
  const uint8_t padded[] = { 100, 100, 77, 77, 100, 100, 77, 77 };
  data.patch({ 6, 6, 8, 8 }, padded, 4);
  rects = data.regenerate({ { 6, 6, 8, 8 } });
  require(rects[1].rect.left == 3 && rects[1].rect.top == 3 && rects[1].rect.right == 4 && rects[1].rect.bottom == 4,
    "mip regeneration scopes the affected rectangle");
  require(data.levels()[1].pixels[15] == 100 && data.levels()[1].pixels[0] == 0, "patch changes no unrelated mip texels");

  for (uint32_t raw = 0; raw < 65536; raw++) {
    uint16_t half = uint16_t(raw);
    if ((half & 0x7c00) == 0x7c00 && (half & 1023)) {
      continue;
    }
    require(writeHalf(readHalf(half)) == half, "all finite/infinite binary16 values round-trip");
  }
  const float floating[] = { -2.f, 6.f, 10.f, 18.f };
  TextureData floats(2, 2, 8, 1, reinterpret_cast<const uint8_t*>(floating));
  float result;
  memcpy(&result, floats.levels()[1].pixels.data(), sizeof(result));
  require(result == 8.f, "R32_SFLOAT mips preserve signed/HDR values");

  for (uint32_t width = 1; width <= 17; width++) {
    for (uint32_t height = 1; height <= 17; height++) {
      for (uint32_t purpose : { 1u, 5u }) {
        std::vector<uint8_t> base(size_t(width) * height);
        for (size_t i = 0; i < base.size(); i++) {
          base[i] = uint8_t((i * 79 + width * 13) % 256);
        }
        TextureData scoped(width, height, 4, purpose, base.data());
        for (uint32_t y = 0; y < height; y++) {
          for (uint32_t x = 0; x < width; x++) {
            uint8_t changedPixel = uint8_t(255 - base[size_t(y) * width + x]);
            base[size_t(y) * width + x] = changedPixel;
            scoped.patch({ x, y, x + 1, y + 1 }, &changedPixel, 1);
            scoped.regenerate({ { x, y, x + 1, y + 1 } });
            TextureData rebuilt(width, height, 4, purpose, base.data());
            for (size_t mip = 0; mip < rebuilt.levels().size(); mip++) {
              require(scoped.levels()[mip].pixels == rebuilt.levels()[mip].pixels,
                "scoped patch matches full mip rebuild for every odd/even edge");
            }
          }
        }
      }
    }
  }

  for (auto size : { std::array<uint32_t, 2> { 256, 256 }, { 257, 129 } }) {
    for (uint32_t purpose : { 0u, 1u, 4u, 5u }) {
      const uint32_t width = size[0];
      const uint32_t height = size[1];
      const uint32_t format = purpose == 0 ? 1 : purpose == 4 ? 5 : 4;
      const uint32_t bytes = pixelSize(format);
      std::vector<uint8_t> base(size_t(width) * height * bytes);
      for (size_t i = 0; i < base.size(); i++) {
        base[i] = uint8_t(i * 79 + i / 13);
      }
      TextureData scoped(width, height, format, purpose, base.data());
      for (bool overlapping : { false, true }) {
        auto previous = scoped.levels();
        std::vector<Rect> patches = overlapping
          ? std::vector<Rect> { { 3, 5, 47, 7 }, { 5, 3, 7, 47 }, { 6, 6, 11, 11 }, { 11, 6, 15, 11 } }
          : std::vector<Rect> { { 0, 0, 2, 2 }, { width - 2, height - 2, width, height } };
        for (Rect patch : patches) {
          for (uint32_t y = patch.top; y < patch.bottom; y++) {
            for (uint32_t x = patch.left; x < patch.right; x++) {
              for (uint32_t c = 0; c < bytes; c++) {
                auto& pixel = base[(size_t(y) * width + x) * bytes + c];
                pixel = uint8_t(255 - pixel);
              }
            }
          }
          scoped.patch(patch, base.data() + (size_t(patch.top) * width + patch.left) * bytes, width * bytes);
        }
        auto updates = scoped.regenerate(patches);
        TextureData rebuilt(width, height, format, purpose, base.data());
        uint64_t updatedTexels = 0;
        uint32_t baseRegions = 0;
        for (auto update : updates) {
          baseRegions += update.mip == 0;
          updatedTexels += area(update.rect);
          auto& uploaded = previous[update.mip];
          const auto& source = scoped.levels()[update.mip];
          for (uint32_t y = update.rect.top; y < update.rect.bottom; y++) {
            auto offset = (size_t(y) * source.width + update.rect.left) * bytes;
            memcpy(uploaded.pixels.data() + offset, source.pixels.data() + offset,
              size_t(update.rect.right - update.rect.left) * bytes);
          }
        }
        if (!overlapping) {
          require(baseRegions == 2 && updatedTexels < 1024,
            "distant animated sprites retain sparse mip uploads instead of enclosing the atlas");
        }
        for (size_t mip = 0; mip < rebuilt.levels().size(); mip++) {
          require(scoped.levels()[mip].pixels == rebuilt.levels()[mip].pixels,
            "sparse and overlapping animation patches match a full mip rebuild");
          require(previous[mip].pixels == rebuilt.levels()[mip].pixels,
            "sparse upload regions cover every changed texel in every mip");
        }
      }
    }
  }

  printf("Texture mip generation, normal conversion, animation patch and binary16 tests passed.\n");
  return 0;
}
