/* SPDX-License-Identifier: MIT */
#pragma once
#include "rtx_types.h"

namespace dxvk {
  class DxvkContext;
  class DxvkDevice;

  class BlasCompactor {
  public:
    struct Relocation {
      uint64_t previous;
      uint64_t current;
    };
    explicit BlasCompactor(DxvkDevice* device);
    ~BlasCompactor();
    std::vector<Relocation> poll(const Rc<DxvkContext>& ctx);
    void enqueue(const Rc<DxvkContext>& ctx, const std::vector<Rc<PooledBlas>>& active);
    void clear();

  private:
    struct QueryPool;
    struct Pending {
      Rc<PooledBlas> source;
      uint64_t version;
      uint64_t completion;
      VkDeviceSize compactedSize = 0;
      uint32_t slot;
    };
    DxvkDevice* m_device;
    Rc<QueryPool> m_queries;
    std::vector<Pending> m_pending;
    size_t m_scanCursor = 0;
    uint64_t m_sequence = 0;
    uint64_t m_copies = 0;
    uint64_t m_sourceBytes = 0;
    uint64_t m_destinationBytes = 0;
    uint64_t m_nextReport = 1;
  };
}
