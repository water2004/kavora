/* SPDX-License-Identifier: MIT */
#include "../../../src/dxvk/rtx_render/rtx_remix_mesh_indices.h"
#include "../../../src/dxvk/rtx_render/rtx_quad_indices.h"

#include <cstdio>
#include <cstdlib>
#include <random>
#include <vector>

namespace {
  void require(bool value, const char* pMessage) {
    if (!value) {
      std::fprintf(stderr, "FAIL: %s\n", pMessage);
      std::exit(1);
    }
  }

  void check(const std::vector<uint32_t>& indices, uint64_t vertexCount, uint32_t expectedStride) {
    using namespace dxvk::remixapi_geometry;
    const auto stride = indexStride(indices.data(), indices.size(), vertexCount);
    require(stride == expectedStride, "Wrong index width");
    const size_t bytes = indices.size() * stride;
    std::vector<uint32_t> storage((bytes + 3) / 4 + 2, 0xa5a5a5a5);
    auto* pOutput = reinterpret_cast<uint8_t*>(storage.data() + 1);
    copyIndices(pOutput, indices.data(), indices.size(), stride);
    for (size_t i = 0; i < indices.size(); ++i) {
      uint32_t decoded = 0;
      std::memcpy(&decoded, pOutput + i * stride, stride);
      require(decoded == indices[i], "Packing changed a triangle index");
    }
    const auto* pBytes = reinterpret_cast<const uint8_t*>(storage.data());
    for (size_t i = 0; i < 4; ++i) {
      require(pBytes[i] == 0xa5, "Packing overwrote prefix guard");
    }
    for (size_t i = bytes + 4; i < storage.size() * 4; ++i) {
      require(pBytes[i] == 0xa5, "Packing overwrote suffix guard");
    }
  }
}

int main() {
  using namespace dxvk;
  require(findQuadIndexPattern(nullptr, 0, 0) == QuadIndexPattern::None, "Empty indices were shared");
  require(findQuadIndexPattern(nullptr, 6, 4) == QuadIndexPattern::None, "Null indices were read");
  for (auto pattern : { QuadIndexPattern::TriangleFan, QuadIndexPattern::ReversedTriangleFan }) {
    std::vector<uint16_t> shared(kQuadIndexCount + 2, 0xa5a5);
    writeQuadIndices(shared.data() + 1, pattern);
    require(shared.front() == 0xa5a5 && shared.back() == 0xa5a5, "Quad generation crossed its buffer boundary");
    const uint32_t fan[] = { 0, 1, 2, 0, 2, 3 };
    const uint32_t reversed[] = { 0, 2, 1, 0, 3, 2 };
    const auto* first = pattern == QuadIndexPattern::TriangleFan ? fan : reversed;
    for (uint32_t i = 0; i < kQuadIndexCount; ++i) {
      require(shared[i + 1] == i / 6 * 4 + first[i % 6], "Quad generation changed primitive order");
    }
    std::vector<uint32_t> source(shared.begin() + 1, shared.end() - 1);
    for (uint32_t vertices : { 4u, 8u, 32768u, 65532u, 65536u }) {
      const uint32_t count = vertices / 4 * 6;
      require(findQuadIndexPattern(source.data(), count, vertices) == pattern, "Canonical quad prefix was not recognized");
      require(findQuadIndexPattern(source.data(), count - 1, vertices) == QuadIndexPattern::None, "Partial primitives were shared");
      require(findQuadIndexPattern(source.data(), count, vertices - 1) == QuadIndexPattern::None, "Invalid vertex count was shared");
      for (uint32_t changed : { 0u, 2u, count / 2, count - 1 }) {
        source[changed] ^= 0x10000;
        require(findQuadIndexPattern(source.data(), count, vertices) == QuadIndexPattern::None, "A changed index was discarded");
        source[changed] ^= 0x10000;
      }
    }
    require(findQuadIndexPattern(source.data(), source.size(), 65540) == QuadIndexPattern::None, "A 32-bit domain was shared");
    source[7] = pattern == QuadIndexPattern::TriangleFan ? 6 : 5;
    source[8] = pattern == QuadIndexPattern::TriangleFan ? 5 : 6;
    require(findQuadIndexPattern(source.data(), source.size(), 65536) == QuadIndexPattern::None, "Mixed quad winding was changed");
  }
  using namespace dxvk::remixapi_geometry;
  require(indexStride(nullptr, 0, 0) == 4, "Empty mesh must preserve its index format");
  copyIndices(nullptr, nullptr, 0, 4);
  check({}, 8, 4);
  check({0, 1, 2}, 3, 2);
  check({0, 65535, 1}, 65536, 2);
  check({0, 65536, 1}, 65537, 4);
  check({0, 65536, 1}, 65536, 4);
  check({0, 1, 2}, 65537, 4);
  check({0, 0xffffffffu, 1}, 65536, 4);
  std::mt19937 random(0x20261102);
  for (size_t count : {1, 3, 6, 33, 256, 8193, 100000}) {
    std::vector<uint32_t> indices(count);
    for (auto& index : indices) {
      index = random() & 65535;
    }
    check(indices, 65536, 2);
    check(indices, 65537, 4);
    indices[count / 2] = 65536;
    check(indices, 65536, 4);
  }
  std::puts("Mesh index packing preserves indices and buffer boundaries.");
  return 0;
}
