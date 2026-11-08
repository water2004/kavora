/* SPDX-License-Identifier: MIT */
// Included inside rtx_remix_api.cpp's anonymous namespace before material conversion.

  struct ApiTexture {
    dxvk::Rc<dxvk::DxvkImageView> view;
    dxvk::Rc<dxvk::AssetData> asset;
    dxvk::remixapiTexture::TextureInfo info;
    std::vector<dxvk::Rc<dxvk::DxvkImageView>> mipViews;
    bool updatable = false;
    uint64_t key;
  };

  std::unordered_map<remixapi_TextureHandle, std::shared_ptr<ApiTexture>> s_apiTextures;
  uint64_t s_nextApiTexture = 0;

  std::shared_ptr<ApiTexture> lookupApiTexture(remixapi_TextureHandle handle) {
    auto found = s_apiTextures.find(handle);
    return found == s_apiTextures.end() ? nullptr : found->second;
  }

  dxvk::TextureRef resolveApiTexture(dxvk::DxvkContext& ctx, const std::shared_ptr<ApiTexture>& texture) {
    if (!texture) {
      return {};
    }
    if (texture->view.ptr()) {
      return dxvk::TextureRef(texture->view, texture->key);
    }
    if (texture->asset.ptr()) {
      auto uploaded = ctx.getCommonObjects()->getTextureManager().preloadTextureAsset(texture->asset, dxvk::ColorSpace::AUTO, false);
      return dxvk::TextureRef(uploaded);
    }
    return {};
  }

  VkFormat apiTextureFormat(uint32_t format) {
    constexpr VkFormat formats[] = {
      VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_R8G8B8A8_SRGB, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_R8G8B8A8_SRGB,
      VK_FORMAT_R8_UNORM, VK_FORMAT_R8G8_UNORM, VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_R32G32B32A32_SFLOAT, VK_FORMAT_R32_SFLOAT
    };
    return format < std::size(formats) ? formats[format] : VK_FORMAT_UNDEFINED;
  }

  using ApiTextureUpload = dxvk::remixapiTexture::Upload;

  void uploadApiTexture(dxvk::DxvkContext& ctx, const ApiTexture& texture,
      const std::vector<ApiTextureUpload>& uploads) {
    ScopedCpuProfileZoneN("API texture: upload dirty regions");
    std::vector<dxvk::remixapiTexture::Rect> dirty;
    for (auto& upload : uploads) {
      VkImageSubresourceLayers subresource { VK_IMAGE_ASPECT_COLOR_BIT, upload.mip, 0, 1 };
      VkOffset3D offset { int32_t(upload.rect.left), int32_t(upload.rect.top), 0 };
      VkExtent3D extent { upload.rect.right - upload.rect.left, upload.rect.bottom - upload.rect.top, 1 };
      ctx.updateImage(texture.view->image(), subresource, offset, extent, upload.pixels.data(), upload.rowPitch,
        VkDeviceSize(upload.rowPitch) * extent.height);
      if (!upload.mip) {
        dirty.push_back(upload.rect);
      }
    }
    dxvk::generateApiTextureMips(ctx, texture.info, texture.mipViews, dxvk::remixapiTexture::planMips(texture.info, std::move(dirty)));
    dxvk::captureApiTextureMips(ctx, texture.info, texture.view);
  }

  remixapi_ErrorCode REMIXAPI_CALL remixapi_CreateTextureFromMemory(const remixapi_TextureFromMemoryInfo* info,
      remixapi_TextureHandle* out_handle) {
    if (!info || !out_handle || info->sType != REMIXAPI_STRUCT_TYPE_TEXTURE_FROM_MEMORY_INFO ||
        !info->pixelData || !info->width || !info->height || !dxvk::remixapiTexture::pixelSize(info->format) || info->purpose > 5) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }
    const remixapi_TextureMipChainEXT* mipChain = nullptr;
    bool immutable = false;
    for (const void* next = info->pNext; next; next = pnext::detail::getPNext(next)) {
      switch (pnext::detail::getStructType(next)) {
      case REMIXAPI_STRUCT_TYPE_TEXTURE_MIP_CHAIN_EXT:
        if (mipChain) {
          return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
        }
        mipChain = static_cast<const remixapi_TextureMipChainEXT*>(next);
        break;
      case REMIXAPI_STRUCT_TYPE_TEXTURE_IMMUTABLE_EXT:
        if (immutable) {
          return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
        }
        immutable = true;
        break;
      default:
        return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
      }
    }
    if (mipChain) {
      if (!mipChain->mipLevels || mipChain->mipLevels > 32 || info->purpose == 2 || info->purpose == 3) {
        return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
      }
      uint64_t expected = 0;
      uint32_t width = info->width;
      uint32_t height = info->height;
      for (uint32_t mip = 0; mip < mipChain->mipLevels; mip++) {
        uint64_t size = uint64_t(width) * height;
        if (size > (UINT64_MAX - expected) / dxvk::remixapiTexture::pixelSize(info->format)) {
          return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
        }
        expected += size * dxvk::remixapiTexture::pixelSize(info->format);
        if (width == 1 && height == 1 && mip + 1 < mipChain->mipLevels) {
          return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
        }
        width = std::max(1u, width / 2);
        height = std::max(1u, height / 2);
      }
      if (expected != mipChain->dataSize) {
        return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
      }
    }
    std::lock_guard lock { s_mutex };
    auto remixDevice = tryAsDxvk();
    if (!remixDevice) {
      return REMIXAPI_ERROR_CODE_REMIX_DEVICE_WAS_NOT_REGISTERED;
    }
    try {
      auto texture = std::make_shared<ApiTexture>();
      texture->info = dxvk::remixapiTexture::TextureInfo(info->width, info->height, info->format, info->purpose,
        mipChain ? mipChain->mipLevels : 0);
      texture->updatable = !immutable;
      auto uploads = dxvk::remixapiTexture::initialUploads(texture->info, info->format, info->pixelData);
      texture->key = dxvk::RtxTextureManager::getUniqueKey();
      auto device = remixDevice->GetDXVKDevice();
      dxvk::DxvkImageCreateInfo imageInfo {};
      imageInfo.type = VK_IMAGE_TYPE_2D;
      const auto sampleFormat = apiTextureFormat(texture->info.format);
      imageInfo.format = sampleFormat == VK_FORMAT_R8G8B8A8_SRGB ? VK_FORMAT_R8G8B8A8_UNORM : sampleFormat;
      if (imageInfo.format != sampleFormat) {
        imageInfo.flags |= VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT;
      }
      imageInfo.sampleCount = VK_SAMPLE_COUNT_1_BIT;
      imageInfo.extent = { info->width, info->height, 1 };
      imageInfo.numLayers = 1;
      imageInfo.mipLevels = uint32_t(texture->info.levels.size());
      const bool generatedMips = !texture->info.authored && imageInfo.mipLevels > 1;
      imageInfo.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
      if (generatedMips) {
        imageInfo.usage |= VK_IMAGE_USAGE_STORAGE_BIT;
      }
      imageInfo.stages = VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;
      imageInfo.access = VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
      imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
      imageInfo.layout = VK_IMAGE_LAYOUT_GENERAL;
      auto image = device->createImage(imageInfo, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, dxvk::DxvkMemoryStats::Category::RTXMaterialTexture,
        "Remix API texture");
      image->setHash(info->hash ? info->hash : texture->key);
      dxvk::DxvkImageViewCreateInfo viewInfo {};
      viewInfo.type = VK_IMAGE_VIEW_TYPE_2D;
      viewInfo.format = sampleFormat;
      viewInfo.usage = VK_IMAGE_USAGE_SAMPLED_BIT;
      viewInfo.aspect = VK_IMAGE_ASPECT_COLOR_BIT;
      viewInfo.numLevels = imageInfo.mipLevels;
      viewInfo.numLayers = 1;
      // BGRA bytes use a storage-capable RGBA image and are swizzled only for material sampling.
      if (texture->info.format == 2 || texture->info.format == 3) {
        viewInfo.swizzle.r = VK_COMPONENT_SWIZZLE_B;
        viewInfo.swizzle.b = VK_COMPONENT_SWIZZLE_R;
      }
      texture->view = device->createImageView(image, viewInfo);
      if (generatedMips) {
        viewInfo.format = imageInfo.format;
        viewInfo.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT;
        viewInfo.swizzle = { VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY,
          VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY };
        viewInfo.numLevels = 1;
        for (uint32_t mip = 0; mip < imageInfo.mipLevels; mip++) {
          viewInfo.minLevel = mip;
          texture->mipViews.push_back(device->createImageView(image, viewInfo));
        }
      }
      remixDevice->EmitCs([texture, uploads = std::move(uploads)](dxvk::DxvkContext* ctx) {
        uploadApiTexture(*ctx, *texture, uploads);
      });
      auto handle = reinterpret_cast<remixapi_TextureHandle>(++s_nextApiTexture);
      static const bool memorySnapshots = dxvk::env::getEnvVar("RTX_MEMORY_SNAPSHOTS") == "1";
      if (memorySnapshots && texture->updatable) {
        uint64_t bytes = 0;
        for (const auto level : texture->info.levels) {
          bytes += uint64_t(level.width) * level.height * dxvk::remixapiTexture::pixelSize(texture->info.format);
        }
        dxvk::Logger::info(dxvk::str::format("API mutable texture: id=", s_nextApiTexture, " width=", info->width,
          " height=", info->height, " mipPayloadBytes=", bytes, " persistentCpuPixelBytes=0"));
      }
      s_apiTextures.emplace(handle, std::move(texture));
      *out_handle = handle;
      return REMIXAPI_ERROR_CODE_SUCCESS;
    } catch (const std::invalid_argument&) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    } catch (const std::exception& exception) {
      dxvk::Logger::err(dxvk::str::format("Remix API texture creation failed: ", exception.what()));
      return REMIXAPI_ERROR_CODE_GENERAL_FAILURE;
    }
  }

  remixapi_ErrorCode REMIXAPI_CALL remixapi_CreateTextureFromFile(remixapi_Path filePath, remixapi_TextureHandle* out_handle) {
    if (!filePath || !filePath[0] || !out_handle) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }
    std::lock_guard lock { s_mutex };
    if (!tryAsDxvk()) {
      return REMIXAPI_ERROR_CODE_REMIX_DEVICE_WAS_NOT_REGISTERED;
    }
    try {
      auto asset = dxvk::AssetDataManager::get().findAsset(std::filesystem::path(filePath).string());
      if (!asset.ptr() || asset->info().type != dxvk::AssetType::Image2D) {
        return REMIXAPI_ERROR_CODE_GENERAL_FAILURE;
      }
      auto texture = std::make_shared<ApiTexture>();
      texture->asset = asset;
      texture->key = dxvk::RtxTextureManager::getUniqueKey();
      auto handle = reinterpret_cast<remixapi_TextureHandle>(++s_nextApiTexture);
      s_apiTextures.emplace(handle, std::move(texture));
      *out_handle = handle;
      return REMIXAPI_ERROR_CODE_SUCCESS;
    } catch (const std::exception& exception) {
      dxvk::Logger::err(dxvk::str::format("Remix API texture file failed: ", exception.what()));
      return REMIXAPI_ERROR_CODE_GENERAL_FAILURE;
    }
  }

  remixapi_ErrorCode REMIXAPI_CALL remixapi_DestroyTexture(remixapi_TextureHandle handle) {
    if (!handle) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }
    std::lock_guard lock { s_mutex };
    if (!tryAsDxvk()) {
      return REMIXAPI_ERROR_CODE_REMIX_DEVICE_WAS_NOT_REGISTERED;
    }
    return s_apiTextures.erase(handle) ? REMIXAPI_ERROR_CODE_SUCCESS : REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
  }

  remixapi_ErrorCode REMIXAPI_CALL remixapi_UpdateTextureFromMemory(const remixapi_TexturePatchInfo* info) {
    ScopedCpuProfileZoneN("API texture: update batch");
    if (!info || info->sType != REMIXAPI_STRUCT_TYPE_TEXTURE_PATCH_INFO || info->pNext || (info->entryCount && !info->entries)) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }
    std::lock_guard lock { s_mutex };
    auto remixDevice = tryAsDxvk();
    if (!remixDevice) {
      return REMIXAPI_ERROR_CODE_REMIX_DEVICE_WAS_NOT_REGISTERED;
    }
    try {
      // Validate the complete batch before changing any CPU or GPU texture.
      for (uint32_t i = 0; i < info->entryCount; i++) {
        const auto& entry = info->entries[i];
        auto texture = lookupApiTexture(entry.handle);
        if (!texture || !texture->updatable || (entry.patchCount && !entry.patches)) {
          return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
        }
        for (uint32_t p = 0; p < entry.patchCount; p++) {
          const auto& patch = entry.patches[p];
          if (!patch.pixelData || !texture->info.validPatch(patch.mipLevel, patch.dstX, patch.dstY, patch.width, patch.height, patch.rowPitch, patch.format) ||
              uint64_t(patch.rowPitch) * patch.height > std::numeric_limits<size_t>::max()) {
            return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
          }
        }
      }
      struct Batch {
        std::shared_ptr<ApiTexture> texture;
        std::vector<ApiTextureUpload> uploads;
      };
      std::vector<Batch> batches(info->entryCount);
      bool hasUpdates = false;
      for (uint32_t i = 0; i < info->entryCount; ++i) {
        const auto& entry = info->entries[i];
        if (!entry.patchCount) {
          continue;
        }
        hasUpdates = true;
        auto texture = lookupApiTexture(entry.handle);
        batches[i].texture = texture;
        batches[i].uploads.reserve(entry.patchCount);
        for (uint32_t p = 0; p < entry.patchCount; ++p) {
          const auto& patch = entry.patches[p];
          batches[i].uploads.push_back(dxvk::remixapiTexture::snapshotPatch(patch.mipLevel,
            { patch.dstX, patch.dstY, patch.dstX + patch.width, patch.dstY + patch.height },
            patch.format, patch.pixelData, patch.rowPitch));
        }
      }
      if (hasUpdates) {
        remixDevice->EmitCs([batches = std::move(batches)](dxvk::DxvkContext* ctx) {
          for (const auto& batch : batches) {
            if (batch.texture) {
              uploadApiTexture(*ctx, *batch.texture, batch.uploads);
            }
          }
        });
      }
      return REMIXAPI_ERROR_CODE_SUCCESS;
    } catch (const std::exception& exception) {
      dxvk::Logger::err(dxvk::str::format("Remix API texture update failed: ", exception.what()));
      return REMIXAPI_ERROR_CODE_GENERAL_FAILURE;
    }
  }
