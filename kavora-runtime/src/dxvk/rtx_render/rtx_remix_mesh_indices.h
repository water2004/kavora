/* SPDX-License-Identifier: MIT */
#pragma once

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>

namespace dxvk::remixapi_geometry {

  inline uint32_t indexStride(const uint32_t* pIndices, size_t count, uint64_t vertexCount) {
    if (!count || !vertexCount || vertexCount > uint64_t(std::numeric_limits<uint16_t>::max()) + 1) {
      return sizeof(uint32_t);
    }
    for (size_t i = 0; i < count; ++i) {
      if (pIndices[i] > std::numeric_limits<uint16_t>::max()) {
        return sizeof(uint32_t);
      }
    }
    return sizeof(uint16_t);
  }

  // Destination is aligned to the selected index width and remains owned by the mesh.
  inline void copyIndices(void* pDestination, const uint32_t* pIndices, size_t count, uint32_t stride) {
    if (!count) {
      return;
    }
    if (stride == sizeof(uint16_t)) {
      auto* pOutput = static_cast<uint16_t*>(pDestination);
      for (size_t i = 0; i < count; ++i) {
        pOutput[i] = static_cast<uint16_t>(pIndices[i]);
      }
    } else {
      std::memcpy(pDestination, pIndices, count * sizeof(uint32_t));
    }
  }

}
