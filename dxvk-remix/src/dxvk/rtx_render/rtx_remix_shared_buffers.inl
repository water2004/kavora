/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: MIT
 */

// Included inside the Remix C API's private namespace.
class ApiSharedBuffer final : public dxvk::DxvkResource {
public:
  dxvk::Rc<dxvk::DxvkBuffer> buffer;
  dxvk::Rc<dxvk::RtxSemaphore> producer;
  HANDLE memoryHandle = nullptr;
  uint64_t readyValue = 0;
  uint64_t size = 0;
  bool committed = false;

  ~ApiSharedBuffer() override {
    if (memoryHandle) {
      CloseHandle(memoryHandle);
    }
  }
};

std::unordered_map<remixapi_SharedBufferHandle, dxvk::Rc<ApiSharedBuffer>> s_sharedBuffers;
dxvk::Rc<dxvk::RtxSemaphore> s_sharedProducer;
uint64_t s_sharedReadySequence = 0;
uint64_t s_sharedCommittedValue = 0;
uint64_t s_sharedHandleSequence = 0;

remixapi_ErrorCode REMIXAPI_CALL remixapi_CreateSharedBuffer(remixapi_SharedBufferInfo* info) {
  auto* device = tryAsDxvk();
  if (!device) {
    return REMIXAPI_ERROR_CODE_REMIX_DEVICE_WAS_NOT_REGISTERED;
  }
  if (!info || info->sType != REMIXAPI_STRUCT_TYPE_SHARED_BUFFER_INFO || info->pNext || !info->size || info->size > (512ull << 20)) {
    return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
  }
  auto* dxvkDevice = device->GetDXVKDevice().ptr();
  auto resource = dxvk::Rc<ApiSharedBuffer>(new ApiSharedBuffer());
  resource->size = info->size;
  dxvk::DxvkBufferCreateInfo createInfo {};
  createInfo.size = dxvk::align(std::max(info->size, uint64_t(65536)), uint64_t(65536));
  createInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT |
    VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
  createInfo.stages = VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT |
    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR | VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;
  createInfo.access = VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR;
  createInfo.sharing.mode = dxvk::DxvkSharedHandleMode::Export;
  createInfo.sharing.type = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
  resource->buffer = dxvkDevice->createBuffer(createInfo, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
    dxvk::DxvkMemoryStats::Category::RTXBuffer, "Remix API shared immutable buffer");
  const auto& memory = resource->buffer->getBufferHandle().memory;
  VkMemoryGetWin32HandleInfoKHR handleInfo { VK_STRUCTURE_TYPE_MEMORY_GET_WIN32_HANDLE_INFO_KHR };
  handleInfo.memory = memory.memory();
  handleInfo.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
  if (dxvkDevice->vkd()->vkGetMemoryWin32HandleKHR(dxvkDevice->handle(), &handleInfo, &resource->memoryHandle) != VK_SUCCESS) {
    return REMIXAPI_ERROR_CODE_GENERAL_FAILURE;
  }
  std::lock_guard lock { s_mutex };
  if (!s_sharedProducer.ptr()) {
    s_sharedProducer = dxvk::RtxSemaphore::createTimeline(dxvkDevice, "Remix API shared buffer producer", 0, true);
  }
  resource->producer = s_sharedProducer;
  resource->readyValue = ++s_sharedReadySequence;
  auto handle = reinterpret_cast<remixapi_SharedBufferHandle>(++s_sharedHandleSequence);
  s_sharedBuffers.emplace(handle, resource);
  info->buffer = handle;
  info->memoryHandle = reinterpret_cast<uint64_t>(resource->memoryHandle);
  info->allocationSize = memory.length();
  info->bindOffset = memory.offset() + resource->buffer->getSliceHandle().offset;
  info->producerSemaphoreHandle = reinterpret_cast<uint64_t>(resource->producer->sharedHandle());
  info->readyValue = resource->readyValue;
  return REMIXAPI_ERROR_CODE_SUCCESS;
}

remixapi_ErrorCode REMIXAPI_CALL remixapi_CommitSharedBuffers(const remixapi_SharedBufferCommitInfo* info) {
  auto* device = tryAsDxvk();
  if (!device) {
    return REMIXAPI_ERROR_CODE_REMIX_DEVICE_WAS_NOT_REGISTERED;
  }
  if (!info || info->sType != REMIXAPI_STRUCT_TYPE_SHARED_BUFFER_COMMIT_INFO || info->pNext ||
      !info->buffers || !info->bufferCount || info->bufferCount > 4096) {
    return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
  }
  std::lock_guard lock { s_mutex };
  if (info->readyValue <= s_sharedCommittedValue || info->readyValue > s_sharedReadySequence) {
    return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
  }
  std::vector<dxvk::Rc<ApiSharedBuffer>> resources;
  resources.reserve(info->bufferCount);
  std::unordered_set<remixapi_SharedBufferHandle> unique;
  for (uint32_t i = 0; i < info->bufferCount; i++) {
    auto found = s_sharedBuffers.find(info->buffers[i]);
    if (found == s_sharedBuffers.end() || found->second->committed || found->second->readyValue > info->readyValue ||
        !unique.insert(info->buffers[i]).second) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }
    resources.push_back(found->second);
  }
  device->EmitCs([resources, value = info->readyValue, producer = s_sharedProducer](dxvk::DxvkContext* ctx) {
    // Isolate this wait from the presenter's separate client-completion semaphore.
    ctx->flushCommandList();
    auto& commands = ctx->getCommandList();
    commands->addWaitSemaphore(producer->handle(), value);
    std::vector<VkBufferMemoryBarrier> barriers;
    barriers.reserve(resources.size());
    for (const auto& resource : resources) {
      auto slice = resource->buffer->getSliceHandle();
      VkBufferMemoryBarrier barrier { VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER };
      barrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR;
      barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_EXTERNAL;
      barrier.dstQueueFamilyIndex = ctx->getDevice()->queues().graphics.queueFamily;
      barrier.buffer = slice.handle;
      barrier.offset = slice.offset;
      barrier.size = slice.length;
      barriers.push_back(barrier);
      commands->trackResource<dxvk::DxvkAccess::Read>(resource);
    }
    commands->cmdPipelineBarrier(dxvk::DxvkCmdBuffer::ExecBuffer, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
      VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr, barriers.size(), barriers.data(), 0, nullptr);
  });
  for (const auto& resource : resources) {
    resource->committed = true;
  }
  s_sharedCommittedValue = info->readyValue;
  return REMIXAPI_ERROR_CODE_SUCCESS;
}

remixapi_ErrorCode REMIXAPI_CALL remixapi_DestroySharedBuffer(remixapi_SharedBufferHandle buffer) {
  if (!tryAsDxvk()) {
    return REMIXAPI_ERROR_CODE_REMIX_DEVICE_WAS_NOT_REGISTERED;
  }
  std::lock_guard lock { s_mutex };
  return s_sharedBuffers.erase(buffer) ? REMIXAPI_ERROR_CODE_SUCCESS : REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
}

dxvk::DxvkBufferSlice sharedBufferSlice(remixapi_SharedBufferHandle handle, uint64_t offset, uint64_t count, uint64_t stride) {
  std::lock_guard lock { s_mutex };
  auto found = s_sharedBuffers.find(handle);
  if (found == s_sharedBuffers.end() || !found->second->committed || offset % 4 || count > UINT32_MAX) {
    throw dxvk::DxvkError("Invalid shared mesh buffer slice");
  }
  const auto& buffer = found->second->buffer;
  if (offset > found->second->size || count > (found->second->size - offset) / stride) {
    throw dxvk::DxvkError("Shared mesh exceeds its GPU buffer allocation");
  }
  return dxvk::DxvkBufferSlice(buffer, offset, count * stride);
}

dxvk::RasterGeometry allocateSharedSurfaceGeometry(const remixapi_MeshSharedSurface& src) {
  if (!src.vertexCount || !src.indexCount || src.indexCount % 3 || src.reserved ||
      src.flags & ~uint32_t(REMIXAPI_MESH_INFO_SURFACE_TRIANGLES_BIT_TEXCOORD_BOUNDS | REMIXAPI_MESH_INFO_SURFACE_TRIANGLES_BIT_PREVIOUS_POSITIONS)) {
    throw dxvk::DxvkError("Invalid shared mesh surface");
  }
  auto vertices = sharedBufferSlice(src.vertexBuffer, src.vertexOffset, src.vertexCount, sizeof(remixapi_HardcodedVertex));
  auto indices = sharedBufferSlice(src.indexBuffer, src.indexOffset, src.indexCount, sizeof(uint32_t));
  remixapi_MeshInfoSurfaceTriangles description {};
  description.vertices_count = src.vertexCount;
  description.indices_count = src.indexCount;
  description.material = src.material;
  description.flags = src.flags;
  return makeSurfaceGeometry(description, vertices, indices, {}, {}, sizeof(remixapi_HardcodedVertex));
}
