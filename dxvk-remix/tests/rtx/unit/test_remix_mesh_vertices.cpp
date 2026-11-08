/* SPDX-License-Identifier: MIT */
#include "../../../src/dxvk/rtx_render/rtx_remix_mesh_vertices.h"
#include "remix/remix_c.h"

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
}

int main() {
  constexpr size_t kAttributes = REMIXAPI_HARDCODED_VERTEX_STORAGE_STRIDE;
  constexpr size_t kLegacy = sizeof(remixapi_HardcodedVertex);
  static_assert(kAttributes == offsetof(remixapi_HardcodedVertex, previousPosition));
  static_assert(kLegacy == 64);
  using dxvk::remixapi_geometry::copyVertices;
  copyVertices<kAttributes>(nullptr, nullptr, 0, kAttributes);
  copyVertices<kAttributes>(nullptr, nullptr, 0, kLegacy);
  std::mt19937 random(0x20261103);
  for (size_t count : { 1, 3, 4, 8193, 100000 }) {
    std::vector<uint8_t> padded(count * kLegacy);
    for (auto& byte : padded) {
      byte = static_cast<uint8_t>(random());
    }
    std::vector<uint8_t> compact(count * kAttributes + 32, 0xa5);
    std::vector<uint8_t> copied(compact.size(), 0xa5);
    copyVertices<kAttributes>(compact.data() + 16, padded.data(), count, kLegacy);
    copyVertices<kAttributes>(copied.data() + 16, compact.data() + 16, count, kAttributes);
    for (size_t i = 0; i < count; ++i) {
      require(std::memcmp(padded.data() + i * kLegacy, copied.data() + 16 + i * kAttributes, kAttributes) == 0,
        "Vertex position, normal, UV, color or bounds changed");
    }
    constexpr size_t kMotionStride = offsetof(remixapi_HardcodedVertex, _pad3);
    static_assert(kMotionStride == 56);
    std::vector<uint8_t> motion(count * kMotionStride + 32, 0xa5);
    copyVertices<kMotionStride>(motion.data() + 16, padded.data(), count, kLegacy);
    for (size_t i = 0; i < count; ++i) {
      require(std::memcmp(padded.data() + i * kLegacy, motion.data() + 16 + i * kMotionStride, kMotionStride) == 0,
        "Explicit previous positions were lost from the native upload");
    }
    for (size_t i = 0; i < 16; ++i) {
      require(motion[i] == 0xa5 && motion[motion.size() - i - 1] == 0xa5, "Motion upload exceeded its buffer");
      require(compact[i] == 0xa5 && copied[i] == 0xa5, "Vertex copy overwrote its prefix guard");
      require(compact[compact.size() - i - 1] == 0xa5 && copied[copied.size() - i - 1] == 0xa5,
        "Vertex copy overwrote its suffix guard");
    }
  }
  std::puts("Compact and padded vertex uploads preserve every attribute and buffer boundary.");
  return 0;
}
