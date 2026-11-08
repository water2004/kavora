/* SPDX-License-Identifier: MIT */
#include "texture_mip_reference.h"
#include <filesystem>
#include <fstream>
#include <random>
#include <sstream>

using namespace dxvk::remixapiTexture;
static void writeBytes(const std::filesystem::path& path, const std::vector<uint8_t>& bytes) {
  std::ofstream out(path, std::ios::binary);
  out.write(reinterpret_cast<const char*>(bytes.data()), bytes.size());
}
static void writeExpected(const std::filesystem::path& path, const TextureData& texture, uint32_t purpose) {
  std::ofstream out(path, std::ios::binary);
  const auto& levels = texture.levels();
  const uint32_t header[] = { levels[0].width, levels[0].height, texture.format(), purpose, uint32_t(levels.size()) };
  out.write(reinterpret_cast<const char*>(header), sizeof(header));
  for (const auto& level : levels) out.write(reinterpret_cast<const char*>(level.pixels.data()), level.pixels.size());
}

int main(int argc, char** argv) {
  if (argc != 2) return 1;
  std::filesystem::path directory(argv[1]);
  std::filesystem::create_directories(directory);
  std::ofstream manifest(directory / "manifest.json");
  manifest << "[\n";
  std::mt19937 random(1317);
  uint32_t id = 0;
  for (uint32_t format = 0; format < 9; format++) for (uint32_t purpose = 0; purpose < 6; purpose++) {
    if ((purpose == 2 || purpose == 3) && channels(format) < 2) continue;
    if (purpose == 4 && format != 5) continue;
    for (const auto dimensions : { LevelSize{7, 5}, LevelSize{1, 13}, LevelSize{19, 1}, LevelSize{17, 9} }) {
      const bool immutable = dimensions.width == 19;
      const uint32_t authored = dimensions.width == 17 && purpose != 2 && purpose != 3 ? 3 : 0;
      const std::string prefix = std::to_string(id);
      TextureInfo info(dimensions.width, dimensions.height, format, purpose, authored);
      auto fill = [&](std::vector<uint8_t>& bytes, uint32_t dataFormat) {
        const uint32_t stride = pixelSize(dataFormat);
        for (size_t offset = 0; offset + stride <= bytes.size(); offset += stride) {
          std::array<float, 4> value;
          for (auto& component : value) component = float(random() % 1001) / 1000.f;
          writePixel(bytes.data() + offset, dataFormat, value, false);
        }
      };
      size_t sourceBytes = 0;
      for (uint32_t mip = 0; mip < (authored ? authored : 1); mip++)
        sourceBytes += size_t(info.levels[mip].width) * info.levels[mip].height * pixelSize(format);
      std::vector<uint8_t> source(sourceBytes);
      fill(source, format);
      writeBytes(directory / (prefix + "-source.bin"), source);
      TextureData reference(dimensions.width, dimensions.height, format, purpose, source.data(), authored);
      writeExpected(directory / (prefix + "-expected-0.bin"), reference, purpose);
      if (id) manifest << ",\n";
      manifest << "{\"id\":" << id << ",\"width\":" << dimensions.width << ",\"height\":" << dimensions.height
        << ",\"format\":" << format << ",\"purpose\":" << purpose << ",\"mips\":" << authored
        << ",\"immutable\":" << (immutable ? "true" : "false") << ",\"patches\":[";
      bool firstPatch = true;
      if (!immutable) for (uint32_t stage = 1; stage <= 2; stage++) {
        std::vector<Rect> dirty;
        const uint32_t patchCount = stage == 1 ? 2 : 1;
        for (uint32_t part = 0; part < patchCount; part++) {
          uint32_t mip = authored && stage == 2 ? 1 : 0;
          auto size = info.levels[mip];
          uint32_t left = part ? size.width / 3 : 0;
          uint32_t top = part ? size.height / 3 : 0;
          uint32_t width = std::max(1u, size.width / 2);
          uint32_t height = std::max(1u, size.height / 2);
          Rect rect { left, top, std::min(size.width, left + width), std::min(size.height, top + height) };
          const uint32_t pitch = (rect.right - rect.left) * pixelSize(info.format) + 3;
          std::vector<uint8_t> pixels(size_t(pitch) * (rect.bottom - rect.top));
          std::vector<uint8_t> row(size_t(rect.right - rect.left) * pixelSize(info.format));
          for (uint32_t y = 0; y < rect.bottom - rect.top; y++) {
            fill(row, info.format);
            memcpy(pixels.data() + size_t(y) * pitch, row.data(), row.size());
          }
          std::string filename = prefix + "-patch-" + std::to_string(stage) + "-" + std::to_string(part) + ".bin";
          writeBytes(directory / filename, pixels);
          reference.patchMip(mip, rect, pixels.data(), pitch);
          if (!authored) dirty.push_back(rect);
          if (!firstPatch) manifest << ',';
          firstPatch = false;
          manifest << "{\"stage\":" << stage << ",\"mip\":" << mip << ",\"x\":" << rect.left << ",\"y\":" << rect.top
            << ",\"width\":" << rect.right - rect.left << ",\"height\":" << rect.bottom - rect.top << ",\"pitch\":" << pitch
            << ",\"format\":" << info.format << ",\"file\":\"" << filename << "\"}";
        }
        if (!authored) reference.regenerate(std::move(dirty));
        writeExpected(directory / (prefix + "-expected-" + std::to_string(stage) + ".bin"), reference, purpose);
      }
      manifest << "]}";
      id++;
    }
  }
  manifest << "\n]\n";
}
