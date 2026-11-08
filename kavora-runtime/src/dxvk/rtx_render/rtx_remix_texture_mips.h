/* SPDX-License-Identifier: MIT */
#pragma once
#include "dxvk_context.h"
#include "rtx_remix_texture_updates.h"

namespace dxvk {
  void generateApiTextureMips(DxvkContext& ctx, const remixapiTexture::TextureInfo& info,
    const std::vector<Rc<DxvkImageView>>& views, const remixapiTexture::MipPlan& plan);
  void captureApiTextureMips(DxvkContext& ctx, const remixapiTexture::TextureInfo& info, const Rc<DxvkImageView>& view);
}
