/* SPDX-License-Identifier: MIT */
#include <algorithm>

#include "rtx_dome_compositor.h"
#include "rtx_context.h"
#include "rtx_shader_manager.h"
#include "rtx/pass/dome_composite/dome_composite_args.h"
#include "dome_composite_layout_asserts.h"
#include <rtx_shaders/dome_composite.h>
#include <rtx_shaders/dome_composite_night.h>
#include <rtx_shaders/dome_composite_end.h>

namespace dxvk {
  namespace {
    class DomeCompositeShader : public ManagedShader {
      SHADER_SOURCE(DomeCompositeShader, VK_SHADER_STAGE_COMPUTE_BIT, dome_composite)
      PUSH_CONSTANTS(DomeCompositeArgs)
      BEGIN_PARAMETER()
        SAMPLER2D(DOME_COMPOSITE_SOURCE)
        SAMPLER2D(DOME_COMPOSITE_NIGHT_FIELD)
        SAMPLER2D(DOME_COMPOSITE_GALAXY)
        SAMPLER2D(DOME_COMPOSITE_TRANSMITTANCE)
        CONSTANT_BUFFER(DOME_COMPOSITE_NIGHT_ARGS)
        { DOME_COMPOSITE_OUTPUT, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_IMAGE_VIEW_TYPE_2D,
          VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT },
        { DOME_COMPOSITE_BACKGROUND, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_IMAGE_VIEW_TYPE_2D,
          VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT },
      END_PARAMETER()
    };
  }

  const TextureRef& DomeCompositor::compose(const Rc<RtxContext>& ctx, const std::vector<const DomeLight*>& layers) {
    ScopedGpuProfileZone(ctx, "Composite Dome Lights");
    const bool nightActive = m_nightSky.prepare(ctx);
    // A uniform angular output preserves celestial point shapes under sky-layer transforms.
    m_worldToOutput = Matrix4();
    if (!RtxOptions::zUp()) {
      std::swap(m_worldToOutput[1], m_worldToOutput[2]);
    }
    const bool separateBackground = std::any_of(layers.begin(), layers.end(),
      [](const DomeLight* layer) { return layer->backgroundOnly; });
    const Matrix4 outputToWorld = inverse(m_worldToOutput);
    VkExtent3D extent { 1, 1, 1 };
    for (const DomeLight* layer : layers) {
      if (const DxvkImageView* view = layer->texture.getImageView()) {
        extent.width = std::max(extent.width, view->imageInfo().extent.width);
        extent.height = std::max(extent.height, view->imageInfo().extent.height);
      }
    }
    if (nightActive || separateBackground) {
      extent.width = std::max(extent.width, NightSky::kWidth);
      extent.height = std::max(extent.height, NightSky::kHeight);
    }
    if (!m_image.ptr() || m_image->info().extent.width != extent.width || m_image->info().extent.height != extent.height) {
      DxvkImageCreateInfo imageInfo = {};
      imageInfo.type = VK_IMAGE_TYPE_2D;
      imageInfo.format = VK_FORMAT_B10G11R11_UFLOAT_PACK32;
      imageInfo.extent = extent;
      imageInfo.mipLevels = 1;
      imageInfo.numLayers = 1;
      imageInfo.sampleCount = VK_SAMPLE_COUNT_1_BIT;
      imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
      imageInfo.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT;
      imageInfo.stages = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;
      imageInfo.access = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
      imageInfo.layout = VK_IMAGE_LAYOUT_UNDEFINED;
      m_image = ctx->getDevice()->createImage(imageInfo, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
        DxvkMemoryStats::Category::RTXRenderTarget, "Composed dome lights");
      DxvkImageViewCreateInfo viewInfo = {};
      viewInfo.type = VK_IMAGE_VIEW_TYPE_2D;
      viewInfo.format = imageInfo.format;
      viewInfo.aspect = VK_IMAGE_ASPECT_COLOR_BIT;
      viewInfo.numLevels = 1;
      viewInfo.numLayers = 1;
      viewInfo.usage = imageInfo.usage;
      m_view = ctx->getDevice()->createImageView(m_image, viewInfo);
      ctx->changeImageLayout(m_image, VK_IMAGE_LAYOUT_GENERAL);
      m_texture = TextureRef(m_view);
    }
    if (separateBackground) {
      if (!m_backgroundImage.ptr() || m_backgroundImage->info().extent.width != extent.width
          || m_backgroundImage->info().extent.height != extent.height) {
        m_backgroundImage = ctx->getDevice()->createImage(m_image->info(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
          DxvkMemoryStats::Category::RTXRenderTarget, "Visible dome background");
        m_backgroundView = ctx->getDevice()->createImageView(m_backgroundImage, m_view->info());
        ctx->changeImageLayout(m_backgroundImage, VK_IMAGE_LAYOUT_GENERAL);
        m_backgroundTexture = TextureRef(m_backgroundView);
      }
    } else {
      m_backgroundTexture = {};
      m_backgroundView = nullptr;
      m_backgroundImage = nullptr;
    }
    const Rc<DxvkSampler> sampler = ctx->getResourceManager().getSampler(
      VK_FILTER_LINEAR, VK_SAMPLER_MIPMAP_MODE_NEAREST, VK_SAMPLER_ADDRESS_MODE_REPEAT,
      VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
    ctx->bindResourceSampler(DOME_COMPOSITE_SOURCE, sampler);
    ctx->bindResourceSampler(DOME_COMPOSITE_TRANSMITTANCE, sampler);
    ctx->bindResourceView(DOME_COMPOSITE_OUTPUT, m_view, nullptr);
    ctx->bindResourceView(DOME_COMPOSITE_BACKGROUND, m_backgroundView, nullptr);
    if (nightActive) {
      m_nightSky.bind(ctx);
    }
    bool first = true;
    for (const DomeLight* layer : layers) {
      Rc<DxvkShader> shader = DomeCompositeShader::getShader();
      if (first && nightActive) {
        shader = NightSky::isEndScene()
          ? GET_SHADER_VARIANT(VK_SHADER_STAGE_COMPUTE_BIT, DomeCompositeShader, dome_composite_end)
          : GET_SHADER_VARIANT(VK_SHADER_STAGE_COMPUTE_BIT, DomeCompositeShader, dome_composite_night);
      }
      ctx->bindShader(VK_SHADER_STAGE_COMPUTE_BIT, shader);
      DomeCompositeArgs args = {};
      args.worldToLight = mat4x3(layer->worldToLight);
      args.outputToWorld = mat4x3(outputToWorld);
      args.radiance = layer->radiance;
      args.opacity = layer->opacity;
      args.blendType = layer->blendType;
      args.width = extent.width;
      args.height = extent.height;
      args.flags = (first ? kDomeFirstLayer : 0) | (layer->texture.getImageView() ? kDomeHasTexture : 0)
        | (layer->transmittance.getImageView() ? kDomeHasTransmittance : 0)
        | (layer->projection ? kDomeRectilinear : 0)
        | (layer->projection == 2 ? kDomeLunarSurface | ((NightSky::moonPhase() & 7u) << kDomeMoonPhaseShift) : 0)
        | (layer->backgroundOnly ? kDomeBackgroundOnly : 0)
        | (separateBackground ? kDomeSeparateBackground : 0);
      ctx->bindResourceView(DOME_COMPOSITE_SOURCE, layer->texture.getImageView(), nullptr);
      ctx->bindResourceView(DOME_COMPOSITE_TRANSMITTANCE, layer->transmittance.getImageView(), nullptr);
      ctx->pushConstants(0, sizeof(args), &args);
      ctx->dispatch((extent.width + 7) / 8, (extent.height + 7) / 8, 1);
      first = false;
    }
    return m_texture;
  }
}
