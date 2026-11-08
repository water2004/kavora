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
#pragma once

#include <bitset>
#include <stdexcept>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "spirv_code_buffer.h"

namespace dxvk {

  // Set zero uses remappable resource slots. Other sets keep their binding numbers.
  template<size_t SlotCount>
  class SpirvResourceInterface {
  public:
    using SlotMask = std::bitset<SlotCount>;

    explicit SpirvResourceInterface(SpirvCodeBuffer& code) {
      std::unordered_map<uint32_t, uint32_t> descriptorSets;
      std::unordered_set<uint32_t> groups;

      // Decorations of a target can appear in either order.
      for (auto ins : code) {
        if (ins.opCode() == spv::OpDecorationGroup) {
          groups.insert(ins.arg(1));
        } else if (ins.opCode() == spv::OpDecorate && ins.arg(2) == spv::DecorationDescriptorSet) {
          const auto [entry, inserted] = descriptorSets.emplace(ins.arg(1), ins.arg(3));
          if (!inserted && entry->second != ins.arg(3)) {
            throw std::invalid_argument("SPIR-V resource has conflicting descriptor sets");
          }
        }
      }

      for (auto ins : code) {
        if (ins.opCode() != spv::OpDecorate && ins.opCode() != spv::OpDecorateId) {
          continue;
        }
        const auto decoration = spv::Decoration(ins.arg(2));
        if (decoration != spv::DecorationBinding && decoration != spv::DecorationDescriptorSet &&
            decoration != spv::DecorationSpecId) {
          continue;
        }
        if (ins.opCode() != spv::OpDecorate || groups.count(ins.arg(1))) {
          throw std::invalid_argument("SPIR-V resource interface requires literal, direct decorations");
        }

        const uint32_t slot = ins.arg(3);
        if (decoration == spv::DecorationSpecId) {
          // Low specialization IDs describe whether the corresponding resource is bound.
          if (slot < SlotCount) {
            m_slots.set(slot);
            m_remappingOffsets.push_back(ins.offset() + 3);
          }
        } else if (decoration == spv::DecorationBinding) {
          const auto set = descriptorSets.find(ins.arg(1));
          if (set == descriptorSets.end()) {
            throw std::invalid_argument("SPIR-V resource binding has no descriptor set");
          }
          if (set->second == 0) {
            if (slot >= SlotCount) {
              throw std::out_of_range("SPIR-V resource slot exceeds the slot table");
            }
            m_slots.set(slot);
            m_remappingOffsets.push_back(ins.offset() + 3);
          }
        }
      }
    }

    const SlotMask& slots() const {
      return m_slots;
    }

    const std::vector<uint32_t>& remappingOffsets() const {
      return m_remappingOffsets;
    }

  private:
    SlotMask m_slots;
    std::vector<uint32_t> m_remappingOffsets;
  };

}
