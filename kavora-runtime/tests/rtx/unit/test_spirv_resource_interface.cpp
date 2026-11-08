/*
* Copyright (c) 2026, NVIDIA CORPORATION. All rights reserved.
*
* Permission is hereby granted, free of charge, to any person obtaining a
* copy of this software and associated documentation files (the "Software"),
* to deal in the Software without restriction, including without limitation
* the rights to use, copy, modify, merge, publish, distribute, sublicense,
* and/or sell copies of the Software, and to permit persons to whom the
* Software is furnished to do so, subject to the following conditions:
*
* The above copyright notice and this permission notice shall be included in
* all copies or substantial portions of the Software.
*
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
* IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
* FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
* THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
* LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
* FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
* DEALINGS IN THE SOFTWARE.
*/
#include <iostream>
#include <stdexcept>

#include "../../../src/spirv/spirv_resource_interface.h"

namespace {
  using Interface = dxvk::SpirvResourceInterface<64>;

  void require(bool condition, const char* message) {
    if (!condition) {
      throw std::runtime_error(message);
    }
  }

  uint32_t decorate(dxvk::SpirvCodeBuffer& code, uint32_t target, spv::Decoration decoration, uint32_t value) {
    const uint32_t offset = code.dwords() + 3;
    code.putIns(spv::OpDecorate, 4);
    code.putInt32(target);
    code.putInt32(decoration);
    code.putInt32(value);
    return offset;
  }

  template<typename F>
  void requireRejected(F makeCode, const char* message) {
    auto code = makeCode();
    try {
      Interface resources(code);
    } catch (const std::logic_error&) {
      return;
    }
    throw std::runtime_error(message);
  }

  void testSeparateSets() {
    dxvk::SpirvCodeBuffer code;
    code.putHeader(0x00010300, 10);
    const uint32_t local = decorate(code, 1, spv::DecorationBinding, 9);
    const uint32_t buffer = decorate(code, 2, spv::DecorationBinding, 0);
    const uint32_t texture = decorate(code, 3, spv::DecorationBinding, 0);
    const uint32_t sampler = decorate(code, 4, spv::DecorationBinding, 0);
    decorate(code, 4, spv::DecorationDescriptorSet, 3);
    decorate(code, 1, spv::DecorationDescriptorSet, 0);
    decorate(code, 3, spv::DecorationDescriptorSet, 2);
    decorate(code, 2, spv::DecorationDescriptorSet, 1);

    const Interface resources(code);
    require(resources.slots().count() == 1 && resources.slots().test(9), "Bindless slots must not enter the set-zero mask");
    require(resources.remappingOffsets() == std::vector<uint32_t>{local}, "Only the set-zero binding may be remapped");
    require(code.data()[buffer] == 0 && code.data()[texture] == 0 && code.data()[sampler] == 0,
      "Interface reflection must not change bindless bindings");
  }

  void testResourceSpecialization() {
    dxvk::SpirvCodeBuffer code;
    decorate(code, 1, spv::DecorationDescriptorSet, 0);
    const uint32_t binding = decorate(code, 1, spv::DecorationBinding, 7);
    const uint32_t bound = decorate(code, 2, spv::DecorationSpecId, 7);
    const uint32_t onlySpec = decorate(code, 3, spv::DecorationSpecId, 8);
    decorate(code, 4, spv::DecorationSpecId, 64);
    decorate(code, 5, spv::DecorationSpecId, 1000);
    const Interface resources(code);
    require(resources.slots().count() == 2 && resources.slots().test(7) && resources.slots().test(8),
      "Resource-bound specialization IDs must retain their slots even without a descriptor");
    require(resources.remappingOffsets() == std::vector<uint32_t>{binding, bound, onlySpec},
      "Descriptor and bound specialization IDs must share the mapping; pipeline constants must not");
  }

  void testEmptySetZero() {
    dxvk::SpirvCodeBuffer code;
    decorate(code, 1, spv::DecorationDescriptorSet, 2);
    decorate(code, 1, spv::DecorationBinding, 0);
    const Interface resources(code);
    require(resources.slots().none() && resources.remappingOffsets().empty(), "External-only shaders have no local resource slots");
  }

  void testInvalidInterfaces() {
    requireRejected([] {
      dxvk::SpirvCodeBuffer code;
      decorate(code, 1, spv::DecorationBinding, 0);
      return code;
    }, "A missing DescriptorSet must not silently remove a binding");
    requireRejected([] {
      dxvk::SpirvCodeBuffer code;
      decorate(code, 1, spv::DecorationDescriptorSet, 0);
      decorate(code, 1, spv::DecorationDescriptorSet, 1);
      return code;
    }, "Conflicting descriptor sets must be rejected");
    requireRejected([] {
      dxvk::SpirvCodeBuffer code;
      decorate(code, 1, spv::DecorationDescriptorSet, 0);
      decorate(code, 1, spv::DecorationBinding, 64);
      return code;
    }, "An out-of-range set-zero slot must be rejected");
    requireRejected([] {
      dxvk::SpirvCodeBuffer code;
      code.putIns(spv::OpDecorationGroup, 2);
      code.putInt32(1);
      decorate(code, 1, spv::DecorationBinding, 7);
      return code;
    }, "Grouped resource decorations must not be silently skipped");
    requireRejected([] {
      dxvk::SpirvCodeBuffer code;
      code.putIns(spv::OpDecorateId, 4);
      code.putInt32(1);
      code.putInt32(spv::DecorationBinding);
      code.putInt32(7);
      return code;
    }, "A resource ID decoration must not be interpreted as a literal");
  }
}

int main() {
  try {
    testSeparateSets();
    testResourceSpecialization();
    testEmptySetZero();
    testInvalidInterfaces();
    std::cout << "SPIR-V resource interface checks passed\n";
    return 0;
  } catch (const std::exception& error) {
    std::cerr << error.what() << '\n';
    return -1;
  }
}
