/* SPDX-License-Identifier: MIT */
#include "rtx_blas_compaction.h"
#include "dxvk_device.h"
#include "dxvk_context.h"
#include "dxvk_scoped_annotation.h"
#include "rtx_options.h"
#include "../../util/util_env.h"

namespace dxvk {
  namespace {
    constexpr uint32_t kQueryCount = 256;
    constexpr uint32_t kQueriesPerFrame = 32;
    constexpr uint32_t kScansPerFrame = 512;
    constexpr VkDeviceSize kCopyBytesPerFrame = 8ull << 20;
  }

  struct BlasCompactor::QueryPool final : DxvkResource {
    Rc<vk::DeviceFn> vkd;
    VkQueryPool handle = VK_NULL_HANDLE;
    Rc<sync::Fence> complete = new sync::Fence();
    std::vector<uint32_t> free;

    explicit QueryPool(DxvkDevice* device) : vkd(device->vkd()) {
      VkQueryPoolCreateInfo info { VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO };
      info.queryType = VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR;
      info.queryCount = kQueryCount;
      if (vkd->vkCreateQueryPool(vkd->device(), &info, nullptr, &handle) != VK_SUCCESS) {
        throw DxvkError("Cannot create BLAS compaction queries");
      }
      free.reserve(kQueryCount);
      for (uint32_t slot = kQueryCount; slot; slot--) {
        free.push_back(slot - 1);
      }
    }
    ~QueryPool() { vkd->vkDestroyQueryPool(vkd->device(), handle, nullptr); }
  };

  BlasCompactor::BlasCompactor(DxvkDevice* device) : m_device(device) { m_pending.reserve(kQueryCount); }
  BlasCompactor::~BlasCompactor() = default;

  void BlasCompactor::clear() {
    m_pending.clear();
    m_queries = nullptr;
    m_scanCursor = 0;
    m_sequence = 0;
    m_copies = m_sourceBytes = m_destinationBytes = 0;
    m_nextReport = 1;
  }

  std::vector<BlasCompactor::Relocation> BlasCompactor::poll(const Rc<DxvkContext>& ctx) {
    std::vector<Relocation> relocations;
    if (m_pending.empty()) {
      return relocations;
    }
    ScopedCpuProfileZoneN("BLAS compaction results");
    const uint64_t completed = m_queries->complete->value();
    const uint32_t frame = m_device->getCurrentFrameId();
    const uint32_t lifetime = std::max(2u, RtxOptions::numFramesToKeepBLAS());
    struct Copy { Rc<PooledBlas> source; VkDeviceSize size; };
    std::vector<Copy> copies;
    copies.reserve(kQueriesPerFrame);
    VkDeviceSize bytes = 0;
    for (size_t index = 0; index < m_pending.size();) {
      auto& pending = m_pending[index];
      if (pending.completion > completed) {
        index++;
        continue;
      }
      const bool obsolete = pending.version != pending.source->buildVersion || pending.source->frameLastTouched + lifetime < frame;
      if (obsolete && pending.version == pending.source->buildVersion) {
        pending.source->compactionVersion = 0;
      }
      if (!obsolete && !pending.compactedSize) {
        if (m_device->vkd()->vkGetQueryPoolResults(m_device->handle(), m_queries->handle, pending.slot, 1,
            sizeof(VkDeviceSize), &pending.compactedSize, sizeof(VkDeviceSize), VK_QUERY_RESULT_64_BIT) != VK_SUCCESS) {
          throw DxvkError("Completed BLAS compaction query is unavailable");
        }
      }
      const VkDeviceSize sourceSize = pending.source->accelStructure->info().size;
      const bool smaller = !obsolete && pending.compactedSize && pending.compactedSize < sourceSize;
      if (smaller) {
        // One oversized BLAS can progress without leaving an unbounded copy batch in flight.
        if (copies.size() == kQueriesPerFrame || (bytes && bytes + pending.compactedSize > kCopyBytesPerFrame)) {
          index++;
          continue;
        }
        copies.push_back({ pending.source, pending.compactedSize });
        bytes += pending.compactedSize;
      }
      m_queries->free.push_back(pending.slot);
      if (index + 1 != m_pending.size()) {
        pending = std::move(m_pending.back());
      }
      m_pending.pop_back();
    }
    if (copies.empty()) {
      return relocations;
    }
    relocations.reserve(copies.size());
    {
      ScopedGpuProfileZone(ctx, "compactBLAS");
      ctx->emitMemoryBarrier(0, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
        VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
      for (auto& copy : copies) {
        auto old = copy.source->accelStructure;
        auto info = old->info();
        info.size = copy.size;
        auto compact = m_device->createAccelStructure(info, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
          VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR, "BLAS Compacted");
        VkCopyAccelerationStructureInfoKHR operation { VK_STRUCTURE_TYPE_COPY_ACCELERATION_STRUCTURE_INFO_KHR };
        operation.src = old->getAccelStructure();
        operation.dst = compact->getAccelStructure();
        operation.mode = VK_COPY_ACCELERATION_STRUCTURE_MODE_COMPACT_KHR;
        ctx->getCommandList()->vkCmdCopyAccelerationStructureKHR(&operation);
        // Prior-frame TLAS references keep the source alive through this command's completion.
        ctx->getCommandList()->trackResource<DxvkAccess::Read>(old);
        ctx->getCommandList()->trackResource<DxvkAccess::Write>(compact);
        relocations.push_back({ copy.source->accelerationStructureReference, compact->getAccelDeviceAddress() });
        copy.source->accelStructure = compact;
        copy.source->accelerationStructureReference = compact->getAccelDeviceAddress();
        copy.source->buildInfo.dstAccelerationStructure = compact->getAccelStructure();
        copy.source->buildInfo.srcAccelerationStructure = VK_NULL_HANDLE;
        m_copies++;
        m_sourceBytes += old->info().size;
        m_destinationBytes += info.size;
      }
      ctx->emitMemoryBarrier(0, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
        VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
        VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
    }
    static const bool snapshots = env::getEnvVar("RTX_MEMORY_SNAPSHOTS") == "1";
    if (snapshots && m_copies >= m_nextReport) {
      Logger::info(str::format("BLAS compaction: frame=", frame, " copies=", m_copies, " sourceBytes=", m_sourceBytes,
        " compactedBytes=", m_destinationBytes, " pending=", m_pending.size()));
      m_nextReport = m_copies + 512;
    }
    return relocations;
  }

  void BlasCompactor::enqueue(const Rc<DxvkContext>& ctx, const std::vector<Rc<PooledBlas>>& active) {
    if (active.empty()) {
      return;
    }
    ScopedCpuProfileZoneN("BLAS compaction candidates");
    if (!m_queries.ptr()) {
      m_queries = new QueryPool(m_device);
    }
    if (m_queries->free.empty()) {
      return;
    }
    const uint64_t sequence = ++m_sequence;
    uint32_t count = 0;
    for (uint32_t scanned = 0; scanned < std::min(size_t(kScansPerFrame), active.size()) && count < kQueriesPerFrame && !m_queries->free.empty(); scanned++) {
      if (m_scanCursor >= active.size()) {
        m_scanCursor = 0;
      }
      const auto& source = active[m_scanCursor++];
      if (!(source->buildInfo.flags & VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_KHR)
          || source->buildVersion == source->compactionVersion) {
        continue;
      }
      if (!count) {
        ctx->emitMemoryBarrier(0, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
          VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
      }
      const uint32_t slot = m_queries->free.back();
      m_queries->free.pop_back();
      ctx->getCommandList()->cmdResetQueryPool(m_queries->handle, slot, 1);
      const VkAccelerationStructureKHR handle = source->accelStructure->getAccelStructure();
      ctx->getCommandList()->vkCmdWriteAccelerationStructuresPropertiesKHR(1, &handle,
        VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR, m_queries->handle, slot);
      ctx->getCommandList()->trackResource<DxvkAccess::Read>(source->accelStructure);
      source->compactionVersion = source->buildVersion;
      m_pending.push_back({ source, source->buildVersion, sequence, 0, slot });
      count++;
    }
    if (count) {
      ctx->getCommandList()->trackResource<DxvkAccess::Write>(m_queries);
      ctx->signal(m_queries->complete, sequence);
    }
  }
}
