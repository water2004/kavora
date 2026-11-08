/* SPDX-License-Identifier: MIT */
#include "../../../src/dxvk/rtx_render/rtx_remix_texture_updates.h"
#include "texture_mip_reference.h"
#include <cstdio>
#include <cstdlib>
#include <random>
#include <set>

using namespace dxvk::remixapiTexture;
static void require(bool condition, const char* message) {
  if (!condition) { fprintf(stderr, "FAIL: %s\n", message); exit(1); }
}

int main() {
  for (uint32_t width : { 1u, 2u, 3u, 7u, 16u, 31u, 64u, 4096u }) {
    for (uint32_t height : { 1u, 2u, 5u, 17u, 33u, 8192u }) {
      TextureInfo info(width, height, 0, 0);
      auto plan = planMips(info, { { 0, 0, width, height }, { 0, 0, width, height } });
      for (const auto& pass : plan.dispatches) {
        const auto target = info.levels[pass.mip];
        const uint32_t expected = ((target.width + 7) / 8) * ((target.height + 7) / 8);
        require(pass.tileCount == expected, "full image tiles cover each destination exactly once");
        std::set<uint64_t> seen;
        for (uint32_t i = 0; i < pass.tileCount; i++) {
          auto tile = plan.tiles[pass.firstTile + i];
          require(tile.x < target.width && tile.y < target.height, "tile bounds");
          require(seen.insert((uint64_t(tile.y) << 32) | tile.x).second, "overlapping patches cannot duplicate a GPU writer");
        }
      }
    }
  }
  TextureInfo atlas(4096, 8192, 0, 0);
  auto sparse = planMips(atlas, { { 0, 0, 2, 2 }, { 1, 1, 3, 3 }, { 4094, 8190, 4096, 8192 } });
  require(sparse.dispatches.front().tileCount == 2, "distant animation patches stay sparse");
  require(sparse.dispatches.back().tileCount == 1, "the final mip has one writer");
  TextureInfo authored(7, 5, 0, 0, 2);
  require(planMips(authored, { { 0, 0, 7, 5 } }).tiles.empty(), "authored mips are never regenerated");
  require(authored.validPatch(1, 0, 0, 3, 2, 12, 0), "authored mip patch");
  require(!atlas.validPatch(1, 0, 0, 1, 1, 4, 0), "generated mip patch rejected");
  require(!atlas.validPatch(0, 4095, 0, 2, 1, 8, 0), "out of bounds patch rejected");
  std::vector<uint8_t> pitched(80);
  for (size_t i = 0; i < pitched.size(); i++) pitched[i] = uint8_t(i);
  auto patch = snapshotPatch(0, { 3, 4, 6, 6 }, 0, pitched.data() + 7, 19);
  require(patch.rowPitch == 12 && patch.pixels.size() == 24, "snapshot strips unused pitch");
  for (uint32_t y = 0; y < 2; y++) for (uint32_t x = 0; x < 12; x++)
    require(patch.pixels[y * 12 + x] == pitched[7 + y * 19 + x], "snapshot owns exact incoming patch bytes");
  std::mt19937 random(77);
  for (uint32_t format : { 0u, 1u, 2u, 3u, 5u, 6u, 7u }) {
    for (uint32_t purpose : { 0u, 1u, 2u, 3u }) {
      std::vector<uint8_t> pixels(7 * 5 * pixelSize(format));
      for (auto& value : pixels) value = uint8_t(random());
      TextureInfo info(7, 5, format, purpose);
      auto uploads = initialUploads(info, format, pixels.data());
      TextureData reference(7, 5, format, purpose, pixels.data());
      require(uploads.size() == 1 && uploads[0].pixels == reference.levels()[0].pixels, "initial canonical base conversion");
    }
  }
  puts("Texture patch ownership, sparse mip tiles and base conversion passed");
}
