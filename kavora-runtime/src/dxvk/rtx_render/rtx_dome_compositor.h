/* SPDX-License-Identifier: MIT */
#pragma once

#include <vector>

#include "rtx_lights.h"
#include "rtx_night_sky.h"

namespace dxvk {
  class RtxContext;

  class DomeCompositor {
  public:
    // Composes in submission order into a shared environment sampled by every sky ray path.
    const TextureRef& compose(const Rc<RtxContext>& ctx, const std::vector<const DomeLight*>& layers);
    const TextureRef& background() const { return m_backgroundView.ptr() ? m_backgroundTexture : m_texture; }
    const Matrix4& worldToOutput() const { return m_worldToOutput; }

  private:
    Rc<DxvkImage> m_image;
    Rc<DxvkImageView> m_view;
    TextureRef m_texture;
    Rc<DxvkImage> m_backgroundImage;
    Rc<DxvkImageView> m_backgroundView;
    TextureRef m_backgroundTexture;
    Matrix4 m_worldToOutput;
    NightSky m_nightSky;
  };
}
