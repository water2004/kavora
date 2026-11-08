/* SPDX-License-Identifier: MIT */
#pragma once

#include <array>
#include <cassert>
#include <cstddef>
#include <cstdint>
#include <limits>

namespace dxvk {
  enum class QuadIndexPattern : uint8_t {
    None,
    TriangleFan,
    ReversedTriangleFan,
  };

  inline constexpr size_t kQuadIndexPatternCount = 2;
  inline constexpr uint32_t kQuadIndexVertexCount = uint32_t(std::numeric_limits<uint16_t>::max()) + 1;
  inline constexpr uint32_t kQuadIndexCount = kQuadIndexVertexCount / 4 * 6;
  inline constexpr size_t kQuadIndexBytes = kQuadIndexCount * sizeof(uint16_t);
  inline constexpr std::array<std::array<uint16_t, 6>, kQuadIndexPatternCount> kQuadIndexPatterns {{
    { 0, 1, 2, 0, 2, 3 },
    { 0, 2, 1, 0, 3, 2 },
  }};

  // Sharing requires the original primitive and vertex order, not only equivalent topology.
  inline QuadIndexPattern findQuadIndexPattern(const uint32_t* pIndices, uint64_t count, uint64_t vertexCount) {
    if (!pIndices || !vertexCount || vertexCount > kQuadIndexVertexCount || vertexCount % 4 || count != vertexCount / 4 * 6) {
      return QuadIndexPattern::None;
    }
    for (size_t pattern = 0; pattern < kQuadIndexPatternCount; ++pattern) {
      const auto& first = kQuadIndexPatterns[pattern];
      bool matches = true;
      for (size_t i = 0; i < count; i += 6) {
        const uint32_t base = uint32_t(i / 6) * 4;
        if (pIndices[i] != base + first[0] || pIndices[i + 1] != base + first[1] ||
            pIndices[i + 2] != base + first[2] || pIndices[i + 3] != base + first[3] ||
            pIndices[i + 4] != base + first[4] || pIndices[i + 5] != base + first[5]) {
          matches = false;
          break;
        }
      }
      if (matches) {
        return QuadIndexPattern(pattern + 1);
      }
    }
    return QuadIndexPattern::None;
  }

  // Writes one complete 16-bit index domain. Meshes use only the prefix they need.
  inline void writeQuadIndices(uint16_t* pIndices, QuadIndexPattern pattern) {
    assert(pattern != QuadIndexPattern::None);
    const auto& first = kQuadIndexPatterns[size_t(pattern) - 1];
    for (uint32_t quad = 0; quad < kQuadIndexVertexCount / 4; ++quad) {
      for (size_t corner = 0; corner < first.size(); ++corner) {
        pIndices[quad * 6 + corner] = uint16_t(quad * 4 + first[corner]);
      }
    }
  }
}
