/* SPDX-License-Identifier: MIT */
#pragma once

#include <cstddef>
#include <cstdint>
#include <cstring>

namespace dxvk::remixapi_geometry {

  // CPU inputs differ only in trailing padding. Every shader-visible byte is retained.
  template<size_t kAttributeBytes>
  inline void copyVertices(void* pDestination, const void* pVertices, size_t count, size_t inputStride) {
    if (!count) {
      return;
    }
    if (inputStride == kAttributeBytes) {
      std::memcpy(pDestination, pVertices, count * kAttributeBytes);
      return;
    }
    auto* pOutput = static_cast<uint8_t*>(pDestination);
    const auto* pInput = static_cast<const uint8_t*>(pVertices);
    for (size_t i = 0; i < count; ++i) {
      std::memcpy(pOutput + i * kAttributeBytes, pInput + i * inputStride, kAttributeBytes);
    }
  }
}
