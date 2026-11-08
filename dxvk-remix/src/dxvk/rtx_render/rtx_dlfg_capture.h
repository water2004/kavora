/* SPDX-License-Identifier: MIT */
#pragma once

#include <algorithm>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <vector>

#include "rtx_dlfg.h"
#include "rtx_semaphore.h"
#include "../dxvk_device.h"
#include "../../util/util_env.h"

namespace dxvk {

// Opt-in readback of the exact WSI images produced by frame generation.
class DlfgFrameCapture {
public:
  explicit DlfgFrameCapture(DxvkDevice* device)
    : m_device(device), m_root(env::getEnvVar("RTX_DLFG_CAPTURE_PATH")),
      m_ready(RtxSemaphore::createTimeline(device, "DLFG capture complete")) {
    std::filesystem::create_directories(m_root);
    m_firstFrame = readSetting("RTX_DLFG_CAPTURE_START", 20, 10000);
    m_frameLimit = readSetting("RTX_DLFG_CAPTURE_COUNT", 4, 128);
    m_cropSize = readSetting("RTX_DLFG_CAPTURE_CROP", 0, 4096);
  }

  void beginFrame(const DxvkFrameInterpolationInfo& frame) {
    collect();
    m_active = false;
    if (m_failed || !frame.valid()) {
      return;
    }
    m_frame = frame.frameId;
    if (m_multiplier != frame.interpolatedFrameCount + 1) {
      m_frameCount = 0;
    }
    m_multiplier = frame.interpolatedFrameCount + 1;
    m_reset = frame.resetHistory;
    ++m_frameCount;
    m_active = m_frameCount >= m_firstFrame && m_frameCount - m_firstFrame < m_frameLimit;
    if (m_active) {
      Logger::info(str::format("DLFG capture: frame=", m_frame, " multiplier=", m_multiplier,
        " reset=", m_reset));
    }
  }

  void record(DxvkDLFGCommandList* commands, const Rc<DxvkImage>& image,
              uint32_t generatedIndex, bool generated) {
    recordImage(commands, image, generatedIndex, generated, generated ? "generated" : "rendered", VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
  }

  void recordInputs(DxvkDLFGCommandList* commands, const DxvkFrameInterpolationInfo& frame) {
    recordImage(commands, frame.motionVectors->image(), 0, false, "motion", VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    recordImage(commands, frame.depth->image(), 0, false, "depth", VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
  }

  void recordImage(DxvkDLFGCommandList* commands, const Rc<DxvkImage>& image,
                   uint32_t generatedIndex, bool generated, const char* role, VkImageLayout originalLayout) {
    if (!m_active) {
      return;
    }
    const auto& info = image->info();
    if (!(info.usage & VK_IMAGE_USAGE_TRANSFER_SRC_BIT) ||
        (info.format != VK_FORMAT_R8G8B8A8_UNORM && info.format != VK_FORMAT_B8G8R8A8_UNORM &&
         info.format != VK_FORMAT_R8G8B8A8_SRGB && info.format != VK_FORMAT_B8G8R8A8_SRGB &&
         info.format != VK_FORMAT_R16G16_SFLOAT && info.format != VK_FORMAT_R32_SFLOAT)) {
      disable("a transferable four-byte capture format is required");
      return;
    }
    const uint32_t width = m_cropSize ? std::min(m_cropSize, info.extent.width) : info.extent.width;
    const uint32_t height = m_cropSize ? std::min(m_cropSize, info.extent.height) : info.extent.height;
    const uint32_t offsetX = (info.extent.width - width) / 2;
    const uint32_t offsetY = (info.extent.height - height) / 2;
    DxvkBufferCreateInfo bufferInfo {};
    bufferInfo.size = VkDeviceSize(width) * height * 4;
    bufferInfo.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    bufferInfo.stages = VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_HOST_BIT;
    bufferInfo.access = VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_HOST_READ_BIT;
    auto buffer = m_device->createBuffer(bufferInfo,
      VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
      DxvkMemoryStats::Category::RTXBuffer, "DLFG image readback");
    VkImageMemoryBarrier barrier { VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER };
    barrier.srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT;
    barrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    barrier.oldLayout = originalLayout;
    barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = image->handle();
    barrier.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
    auto vk = m_device->vkd();
    vk->vkCmdPipelineBarrier(commands->getCmdBuffer(), VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
      VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &barrier);
    VkBufferImageCopy region {};
    region.bufferOffset = buffer->getSliceHandle().offset;
    region.imageSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
    region.imageOffset = { int32_t(offsetX), int32_t(offsetY), 0 };
    region.imageExtent = { width, height, 1 };
    vk->vkCmdCopyImageToBuffer(commands->getCmdBuffer(), image->handle(),
      VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buffer->getBufferRaw(), 1, &region);
    VkBufferMemoryBarrier hostBarrier { VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER };
    hostBarrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    hostBarrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    hostBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    hostBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    hostBarrier.buffer = buffer->getBufferRaw();
    hostBarrier.offset = buffer->getSliceHandle().offset;
    hostBarrier.size = bufferInfo.size;
    vk->vkCmdPipelineBarrier(commands->getCmdBuffer(), VK_PIPELINE_STAGE_TRANSFER_BIT,
      VK_PIPELINE_STAGE_HOST_BIT, 0, 0, nullptr, 1, &hostBarrier, 0, nullptr);
    barrier.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    barrier.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT;
    barrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    barrier.newLayout = originalLayout;
    vk->vkCmdPipelineBarrier(commands->getCmdBuffer(), VK_PIPELINE_STAGE_TRANSFER_BIT,
      VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr, 0, nullptr, 1, &barrier);
    commands->trackResource<DxvkAccess::Read>(image);
    commands->trackResource<DxvkAccess::Write>(buffer);
    m_recorded.push_back({ buffer, m_frame, m_multiplier, generatedIndex, generated, m_reset,
      width, height, info.format, 0, role, info.extent.width, info.extent.height, offsetX, offsetY });
  }

  void commit(DxvkDLFGCommandList* commands) {
    if (m_recorded.empty()) {
      return;
    }
    const uint64_t value = ++m_nextValue;
    commands->addSignalSemaphore(m_ready->handle(), value);
    for (auto& shot : m_recorded) {
      shot.readyValue = value;
      m_pending.push_back(std::move(shot));
    }
    m_recorded.clear();
  }

  void discard() { m_recorded.clear(); }

  void collect() {
    if (m_pending.empty()) {
      return;
    }
    uint64_t ready = 0;
    if (m_device->vkd()->vkGetSemaphoreCounterValue(m_device->handle(), m_ready->handle(), &ready) != VK_SUCCESS) {
      return;
    }
    VkSemaphore semaphore = m_ready->handle();
    VkSemaphoreWaitInfo wait { VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO };
    wait.semaphoreCount = 1;
    wait.pSemaphores = &semaphore;
    wait.pValues = &ready;
    if (m_device->vkd()->vkWaitSemaphores(m_device->handle(), &wait, 0) != VK_SUCCESS) {
      return;
    }
    for (auto it = m_pending.begin(); it != m_pending.end();) {
      if (it->readyValue > ready) {
        ++it;
        continue;
      }
      std::string name = str::format("x", it->multiplier, "-frame", it->frame, "-",
        it->role, it->index, "-", it->width, "x", it->height);
      std::ofstream data(m_root / (name + ".raw"), std::ios::binary);
      data.write(static_cast<const char*>(it->buffer->mapPtr(0)), it->buffer->info().size);
      std::ofstream metadata(m_root / (name + ".json"));
      metadata << "{\"frame\":" << it->frame << ",\"multiplier\":" << it->multiplier
        << ",\"index\":" << it->index << ",\"generated\":" << (it->generated ? "true" : "false")
        << ",\"reset\":" << (it->reset ? "true" : "false") << ",\"width\":" << it->width
        << ",\"height\":" << it->height << ",\"vkFormat\":" << uint32_t(it->format)
        << ",\"role\":\"" << it->role << "\",\"sourceWidth\":" << it->sourceWidth << ",\"sourceHeight\":" << it->sourceHeight
        << ",\"offsetX\":" << it->offsetX << ",\"offsetY\":" << it->offsetY << "}";
      if (!data || !metadata) {
        disable("image or metadata could not be written");
        return;
      }
      it = m_pending.erase(it);
    }
  }

private:
  static uint32_t readSetting(const char* name, uint32_t defaultValue, uint32_t limit) {
    const auto value = env::getEnvVar(name);
    return value.empty() ? defaultValue : uint32_t(std::min(std::strtoul(value.c_str(), nullptr, 10), static_cast<unsigned long>(limit)));
  }

  void disable(const char* reason) {
    Logger::err(str::format("DLFG capture disabled: ", reason));
    m_failed = true;
    m_active = false;
    m_recorded.clear();
    m_pending.clear();
  }
  struct Shot {
    Rc<DxvkBuffer> buffer;
    uint32_t frame, multiplier, index;
    bool generated, reset;
    uint32_t width, height;
    VkFormat format;
    uint64_t readyValue;
    const char* role;
    uint32_t sourceWidth, sourceHeight, offsetX, offsetY;
  };
  DxvkDevice* m_device;
  std::filesystem::path m_root;
  Rc<RtxSemaphore> m_ready;
  uint32_t m_frameCount = 0, m_frame = 0, m_multiplier = 0;
  uint32_t m_firstFrame = 20, m_frameLimit = 4, m_cropSize = 0;
  uint64_t m_nextValue = 0;
  bool m_active = false, m_reset = false, m_failed = false;
  std::vector<Shot> m_recorded, m_pending;
};
}
