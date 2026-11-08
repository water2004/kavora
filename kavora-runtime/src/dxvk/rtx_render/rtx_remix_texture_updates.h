/* SPDX-License-Identifier: MIT */
#pragma once
#include "rtx_remix_texture_data.h"

namespace dxvk::remixapiTexture {
  struct Upload {
    uint32_t mip;
    Rect rect;
    uint32_t rowPitch;
    std::vector<uint8_t> pixels;
  };

  inline Upload snapshotPatch(uint32_t mip, Rect rect, uint32_t format, const uint8_t* pPixels, uint32_t sourcePitch) {
    const uint32_t pitch = (rect.right - rect.left) * pixelSize(format);
    Upload upload { mip, rect, pitch, std::vector<uint8_t>(size_t(pitch) * (rect.bottom - rect.top)) };
    for (uint32_t y = 0; y < rect.bottom - rect.top; y++) {
      memcpy(upload.pixels.data() + size_t(y) * pitch, pPixels + size_t(y) * sourcePitch, pitch);
    }
    return upload;
  }

  inline std::vector<Upload> initialUploads(const TextureInfo& info, uint32_t sourceFormat, const uint8_t* pPixels) {
    std::vector<Upload> uploads;
    const uint32_t count = info.authored ? uint32_t(info.levels.size()) : 1;
    for (uint32_t mip = 0; mip < count; mip++) {
      const auto size = info.levels[mip];
      const Rect rect { 0, 0, size.width, size.height };
      if (info.purpose == 2 || info.purpose == 3) {
        Upload upload { mip, rect, size.width * 2, std::vector<uint8_t>(size_t(size.width) * size.height * 2) };
        for (size_t pixel = 0; pixel < size_t(size.width) * size.height; pixel++) {
          auto value = readPixel(pPixels + pixel * pixelSize(sourceFormat), sourceFormat, false);
          if (sourceFormat == 2 || sourceFormat == 3) {
            std::swap(value[0], value[2]);
          }
          const float nx = value[0] * 2.f - 1.f;
          const float ny = (value[1] * 2.f - 1.f) * (info.purpose == 3 ? -1.f : 1.f);
          const float nz = channels(sourceFormat) > 2 ? value[2] * 2.f - 1.f : std::sqrt(std::max(0.f, 1.f - nx * nx - ny * ny));
          encodeNormal(upload.pixels.data() + pixel * 2, nx, ny, nz);
        }
        uploads.push_back(std::move(upload));
      } else {
        uploads.push_back(snapshotPatch(mip, rect, info.format, pPixels, size.width * pixelSize(info.format)));
      }
      pPixels += size_t(size.width) * size.height * pixelSize(sourceFormat);
    }
    return uploads;
  }

  constexpr uint32_t kMipTileSize = 8;
  struct MipTile { uint32_t x; uint32_t y; };
  struct MipDispatch { uint32_t mip; uint32_t firstTile; uint32_t tileCount; };
  struct MipPlan {
    std::vector<MipTile> tiles;
    std::vector<MipDispatch> dispatches;
  };

  // Every destination tile appears once, even when dirty sprite rectangles overlap.
  inline MipPlan planMips(const TextureInfo& info, std::vector<Rect> dirty) {
    MipPlan plan;
    if (info.authored || dirty.empty()) {
      return plan;
    }
    for (uint32_t mip = 1; mip < info.levels.size(); mip++) {
      const auto source = info.levels[mip - 1];
      const auto target = info.levels[mip];
      const uint32_t columns = (target.width + kMipTileSize - 1) / kMipTileSize;
      std::vector<uint32_t> indices;
      for (auto& rect : dirty) {
        rect = { uint32_t(uint64_t(rect.left) * target.width / source.width),
          uint32_t(uint64_t(rect.top) * target.height / source.height),
          std::min(target.width, uint32_t((uint64_t(rect.right) * target.width + source.width - 1) / source.width)),
          std::min(target.height, uint32_t((uint64_t(rect.bottom) * target.height + source.height - 1) / source.height)) };
        for (uint32_t y = rect.top / kMipTileSize; y < (rect.bottom + kMipTileSize - 1) / kMipTileSize; y++) {
          for (uint32_t x = rect.left / kMipTileSize; x < (rect.right + kMipTileSize - 1) / kMipTileSize; x++) {
            indices.push_back(y * columns + x);
          }
        }
      }
      std::sort(indices.begin(), indices.end());
      indices.erase(std::unique(indices.begin(), indices.end()), indices.end());
      plan.dispatches.push_back({ mip, uint32_t(plan.tiles.size()), uint32_t(indices.size()) });
      for (uint32_t index : indices) {
        plan.tiles.push_back({ (index % columns) * kMipTileSize, (index / columns) * kMipTileSize });
      }
    }
    return plan;
  }
}
