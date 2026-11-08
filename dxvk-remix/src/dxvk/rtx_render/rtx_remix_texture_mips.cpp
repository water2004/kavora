/* SPDX-License-Identifier: MIT */
#include <filesystem>
#include <fstream>
#include <windows.h>

#include "rtx_remix_texture_mips.h"
#include "dxvk_device.h"
#include "dxvk_scoped_annotation.h"
#include "rtx_shader_manager.h"
#include "rtx/pass/texture_mips/texture_mip_args.h"
#include <rtx_shaders/texture_mip.h>
#include "../../util/util_env.h"

namespace dxvk {
  namespace {
    class ApiTextureMipShader : public ManagedShader {
      SHADER_SOURCE(ApiTextureMipShader, VK_SHADER_STAGE_COMPUTE_BIT, texture_mip)
      PUSH_CONSTANTS(TextureMipArgs)
      BEGIN_PARAMETER()
        { TEXTURE_MIP_SOURCE, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, VK_IMAGE_VIEW_TYPE_2D, VK_ACCESS_SHADER_READ_BIT },
        { TEXTURE_MIP_OUTPUT, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_IMAGE_VIEW_TYPE_2D, VK_ACCESS_SHADER_WRITE_BIT },
        STRUCTURED_BUFFER(TEXTURE_MIP_TILES)
      END_PARAMETER()
    };
    PREWARM_SHADER_PIPELINE(ApiTextureMipShader);
  }

  void generateApiTextureMips(DxvkContext& ctx, const remixapiTexture::TextureInfo& info,
      const std::vector<Rc<DxvkImageView>>& views, const remixapiTexture::MipPlan& plan) {
    if (plan.tiles.empty()) {
      return;
    }
    ScopedGpuProfileZone((&ctx), "API texture dirty mips");
    DxvkBufferCreateInfo bufferInfo {};
    bufferInfo.size = plan.tiles.size() * sizeof(remixapiTexture::MipTile);
    bufferInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    bufferInfo.stages = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
    bufferInfo.access = VK_ACCESS_SHADER_READ_BIT;
    auto tiles = ctx.getDevice()->createBuffer(bufferInfo, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
      DxvkMemoryStats::Category::RTXBuffer, "API texture dirty mip tiles");
    memcpy(tiles->mapPtr(0), plan.tiles.data(), size_t(bufferInfo.size));
    ctx.bindResourceBuffer(TEXTURE_MIP_TILES, DxvkBufferSlice(tiles));
    ctx.bindShader(VK_SHADER_STAGE_COMPUTE_BIT, ApiTextureMipShader::getShader());
    ctx.setPushConstantBank(DxvkPushConstantBank::RTX);
    for (const auto& dispatch : plan.dispatches) {
      const auto source = info.levels[dispatch.mip - 1];
      const auto target = info.levels[dispatch.mip];
      TextureMipArgs args { source.width, source.height, target.width, target.height,
        dispatch.firstTile, dispatch.tileCount, std::min(dispatch.tileCount, 65535u), info.format, info.purpose };
      ctx.bindResourceView(TEXTURE_MIP_SOURCE, views[dispatch.mip - 1], nullptr);
      ctx.bindResourceView(TEXTURE_MIP_OUTPUT, views[dispatch.mip], nullptr);
      ctx.pushConstants(0, sizeof(args), &args);
      ctx.dispatch(args.groupsX, (args.tileCount + args.groupsX - 1) / args.groupsX, 1);
    }
  }

  void captureApiTextureMips(DxvkContext& ctx, const remixapiTexture::TextureInfo& info, const Rc<DxvkImageView>& view) {
    static const std::string directory = env::getEnvVar("RTX_API_TEXTURE_CAPTURE_PATH");
    if (directory.empty()) {
      return;
    }
    static const uint64_t hash = std::stoull(env::getEnvVar("RTX_API_TEXTURE_CAPTURE_HASH"), nullptr, 0);
    if (view->image()->getHash() != hash) {
      return;
    }
    static uint32_t sequence = 0;
    std::vector<VkDeviceSize> offsets;
    VkDeviceSize bytes = 0;
    for (auto level : info.levels) {
      bytes = align(bytes, VkDeviceSize(4));
      offsets.push_back(bytes);
      bytes += VkDeviceSize(level.width) * level.height * remixapiTexture::pixelSize(info.format);
    }
    DxvkBufferCreateInfo bufferInfo {};
    bufferInfo.size = bytes;
    bufferInfo.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    bufferInfo.stages = VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_HOST_BIT;
    bufferInfo.access = VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_HOST_READ_BIT;
    auto readback = ctx.getDevice()->createBuffer(bufferInfo, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
      DxvkMemoryStats::Category::RTXBuffer, "API texture mip capture");
    for (uint32_t mip = 0; mip < info.levels.size(); mip++) {
      auto level = info.levels[mip];
      ctx.copyImageToBuffer(readback, offsets[mip], 1, 1, view->image(),
        { VK_IMAGE_ASPECT_COLOR_BIT, mip, 0, 1 }, { 0, 0, 0 }, { level.width, level.height, 1 });
    }
    ctx.emitMemoryBarrier(0, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
      VK_PIPELINE_STAGE_HOST_BIT, VK_ACCESS_HOST_READ_BIT);
    Rc<sync::Fence> complete = new sync::Fence();
    ctx.signal(complete, 1);
    ctx.flushCommandList();
    complete->wait(1);
    std::filesystem::create_directories(directory);
    const auto path = std::filesystem::path(directory) / str::format("texture-", GetCurrentProcessId(), "-", sequence++, ".bin");
    const auto temporary = path.string() + ".tmp";
    std::ofstream file(temporary, std::ios::binary);
    const uint32_t header[] = { info.levels[0].width, info.levels[0].height, info.format, info.purpose, uint32_t(info.levels.size()) };
    file.write(reinterpret_cast<const char*>(header), sizeof(header));
    for (uint32_t mip = 0; mip < info.levels.size(); mip++) {
      const auto level = info.levels[mip];
      file.write(static_cast<const char*>(readback->mapPtr(offsets[mip])), size_t(level.width) * level.height * remixapiTexture::pixelSize(info.format));
    }
    file.close();
    std::filesystem::rename(temporary, path);
  }
}
