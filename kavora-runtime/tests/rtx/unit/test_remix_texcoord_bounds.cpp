/* SPDX-License-Identifier: MIT */
#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <glm/glm.hpp>

using float3 = glm::vec3;
using uint3 = glm::uvec3;
using uint = uint32_t;
#include "rtx/pass/interleave_geometry_indices.h"
#include "rtx/pass/interleave_geometry.h"

namespace {
  void require(bool value, const char* pMessage) {
    if (!value) {
      fprintf(stderr, "FAIL: %s\n", pMessage);
      exit(1);
    }
  }
}

int main() {
  const float positions[] = { 1.f, 2.f, 3.f, 0.f, 4.f, 5.f, 6.f, 0.f };
  const float normals[] = { 0.f, 0.f, 1.f, 0.f, 1.f, 0.f };
  const float uvs[] = { 0.25f, 0.5f, 0.75f, 1.f };
  const uint32_t colors[] = { 0xff123456u, 0xff987654u };
  const uint32_t bounds[] = { 0x38003400u, 0x3c003a00u, 0u, 0u, 0x30002c00u, 0x38003400u, 0u, 0u };
  InterleaveGeometryArgs args {};
  args.positionStride = 4;
  args.positionFormat = interleaver::VK_FORMAT_R32G32B32_SFLOAT;
  args.hasNormals = 1;
  args.normalStride = 3;
  args.normalFormat = interleaver::VK_FORMAT_R32G32B32_SFLOAT;
  args.hasTexcoord = 1;
  args.texcoordStride = 2;
  args.texcoordFormat = interleaver::VK_FORMAT_R32G32_SFLOAT;
  args.hasTexcoordBounds = 1;
  args.texcoordBoundsStride = 4;
  args.hasColor0 = 1;
  args.color0Stride = 1;
  args.color0Format = interleaver::VK_FORMAT_B8G8R8A8_UNORM;
  args.outputStride = 11;
  float output[22] {};
  for (uint32_t i = 0; i < 2; i++) {
    interleaver::interleave(i, output, positions, normals, uvs, colors, bounds, args);
    uint32_t packed[3];
    memcpy(packed, output + i * 11 + 8, sizeof(packed));
    require(packed[0] == bounds[i * 4] && packed[1] == bounds[i * 4 + 1], "half4 atlas bounds are copied bit-exact through shared CPU/GPU interleaver");
    require(packed[2] == colors[i], "bounds insertion preserves vertex color packing");
    require(output[i * 11 + 6] == uvs[i * 2] && output[i * 11 + 7] == uvs[i * 2 + 1], "bounds do not alter UVs used for derivatives");
  }
  args.hasNormals = 0;
  args.forceNormals = 1;
  interleaver::interleave(0, output, positions, nullptr, uvs, colors, bounds, args);
  require(output[3] == 0.f && output[4] == 0.f && output[5] == 0.f, "bounds survive normal-generation interleaver path");
  uint32_t packed;
  memcpy(&packed, output + 8, sizeof(packed));
  require(packed == bounds[0], "normal generation retains bounds field offset");
  printf("Atlas bounds interleaver tests passed.\n");
  return 0;
}
