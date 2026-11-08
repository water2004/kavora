/* SPDX-License-Identifier: MIT */
#include <algorithm>
#include <cmath>
#include <cstring>

#include "rtx_night_sky.h"
#include "rtx_asset_data_manager.h"
#include "rtx_context.h"
#include "rtx_shader_manager.h"
#include "rtx_texture_manager.h"
#include "rtx/pass/dome_composite/dome_composite_args.h"
#include <rtx_shaders/generate_night_sky.h>
#include <rtx_shaders/generate_end_background.h>

namespace dxvk {
  namespace {
    class GenerateNightSkyShader : public ManagedShader {
      SHADER_SOURCE(GenerateNightSkyShader, VK_SHADER_STAGE_COMPUTE_BIT, generate_night_sky)
      BEGIN_PARAMETER()
        RW_TEXTURE2D(0)
      END_PARAMETER()
    };

    class GenerateEndBackgroundShader : public ManagedShader {
      SHADER_SOURCE(GenerateEndBackgroundShader, VK_SHADER_STAGE_COMPUTE_BIT, generate_end_background)
      BEGIN_PARAMETER()
        RW_TEXTURE2D(0)
      END_PARAMETER()
    };

    float randomMeteor(uint32_t value) {
      value ^= value >> 16;
      value *= 0x7feb352du;
      value ^= value >> 15;
      value *= 0x846ca68bu;
      value ^= value >> 16;
      return float(value >> 8) / 16777216.0f;
    }

    void setMeteor(NightSkyArgs& args, uint32_t slot, int64_t event, float age, float intensity) {
      const uint32_t seed = uint32_t(event) * 17u + 731u;
      const float duration = 0.8f + 0.4f * randomMeteor(seed);
      if (age < 0 || age >= duration) {
        return;
      }
      const float phase = age / duration;
      const float azimuth = randomMeteor(seed + 1) * 6.28318530718f;
      const float elevation = 0.35f + randomMeteor(seed + 2) * 0.8f;
      const float heading = (randomMeteor(seed + 3) - 0.5f) * 1.6f;
      const Vector3 start(std::cos(azimuth) * std::cos(elevation), std::sin(elevation),
        std::sin(azimuth) * std::cos(elevation));
      const Vector3 horizontal(-std::sin(azimuth), 0, std::cos(azimuth));
      const Vector3 downward(std::cos(azimuth) * std::sin(elevation), -std::cos(elevation),
        std::sin(azimuth) * std::sin(elevation));
      const Vector3 travel = horizontal * std::cos(heading) + downward * std::sin(heading);
      const float headAngle = phase * 0.32f;
      const float tailAngle = std::max(0.0f, headAngle - 0.12f);
      const Vector3 head = start * std::cos(headAngle) + travel * std::sin(headAngle);
      const Vector3 tail = start * std::cos(tailAngle) + travel * std::sin(tailAngle);
      const float envelope = std::min(phase * 8.0f, 1.0f) * std::min((1.0f - phase) * 3.0f, 1.0f);
      args.meteorHead[slot] = Vector4(head, intensity * envelope);
      args.meteorTail[slot] = Vector4(tail, 0);
    }
  }

  void NightSky::createField(const Rc<RtxContext>& ctx, bool end) {
    ScopedGpuProfileZone(ctx, "Generate Celestial Background");
    DxvkImageCreateInfo info = {};
    info.type = VK_IMAGE_TYPE_2D;
    info.format = end ? VK_FORMAT_B10G11R11_UFLOAT_PACK32 : VK_FORMAT_R16_SFLOAT;
    info.extent = {kWidth, kHeight, 1};
    info.mipLevels = end ? 1 : 13;
    info.numLayers = 1;
    info.sampleCount = VK_SAMPLE_COUNT_1_BIT;
    info.tiling = VK_IMAGE_TILING_OPTIMAL;
    info.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    info.stages = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
      | VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    info.access = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    info.layout = VK_IMAGE_LAYOUT_UNDEFINED;
    Rc<DxvkImage> field = ctx->getDevice()->createImage(info, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
      DxvkMemoryStats::Category::RTXRenderTarget, end ? "End celestial background" : "Night sky field");
    DxvkImageViewCreateInfo view = {};
    view.type = VK_IMAGE_VIEW_TYPE_2D;
    view.format = info.format;
    view.aspect = VK_IMAGE_ASPECT_COLOR_BIT;
    view.numLevels = 1;
    view.numLayers = 1;
    view.usage = info.usage;
    Rc<DxvkImageView> fieldView = ctx->getDevice()->createImageView(field, view);
    ctx->changeImageLayout(field, VK_IMAGE_LAYOUT_GENERAL);
    ctx->bindResourceView(0, fieldView, nullptr);
    ctx->bindShader(VK_SHADER_STAGE_COMPUTE_BIT, end
      ? GenerateEndBackgroundShader::getShader() : GenerateNightSkyShader::getShader());
    ctx->dispatch(kWidth / 8, kHeight / 8, 1);
    if (end) {
      m_endField = field;
      m_endFieldView = fieldView;
    } else {
      view.numLevels = info.mipLevels;
      view.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
      m_field = field;
      m_fieldView = ctx->getDevice()->createImageView(field, view);
      ctx->generateMipmaps(m_fieldView, VK_FILTER_LINEAR);
    }
  }

  bool NightSky::prepare(const Rc<RtxContext>& ctx) {
    NightSkyArgs args = {};
    const auto releaseTexture = [](TextureRef& reference) {
      const auto& texture = reference.getManagedTexture();
      if (texture.ptr()) {
        // Host skies pin their full mip chain only while selected.
        texture->m_canDemote = true;
        reference.demote();
      }
      reference = {};
    };
    const bool end = isEndScene();
    if (!end) {
      m_endFieldView = nullptr;
      m_endField = nullptr;
    }
    const bool active = end ? EndSkyOptions::enabled()
      : enabled() && sceneType() == 1 && visibility() > 0
        && (starIntensity() > 0 || milkyWayIntensity() > 0 || (meteorRate() > 0 && meteorIntensity() > 0));
    if (!active) {
      if (!sceneType() || (end ? !EndSkyOptions::enabled() : !enabled())) {
        m_fieldView = nullptr;
        m_field = nullptr;
        releaseTexture(m_environment);
      }
      m_endFieldView = nullptr;
      m_endField = nullptr;
      return false;
    }
    const auto prepareTexture = [&ctx](TextureRef& reference, const char* path) {
      if (!reference.isValid()) {
        const Rc<AssetData> asset = AssetDataManager::get().findAsset(path);
        if (asset == nullptr) {
          throw DxvkError(str::format("Missing sky texture: ", path));
        }
        reference = TextureRef(ctx->getCommonObjects()->getTextureManager().preloadTextureAsset(asset, ColorSpace::AUTO, true));
      }
      uint32_t textureIndex = BINDING_INDEX_INVALID;
      ctx->getCommonObjects()->getSceneManager().trackTexture(reference, textureIndex, true, false);
      return reference.getImageView() != nullptr;
    };
    if (end) {
      m_fieldView = nullptr;
      m_field = nullptr;
      releaseTexture(m_environment);
      if (!m_endField.ptr()) {
        createField(ctx, true);
      }
    } else {
      if (!m_field.ptr()) {
        createField(ctx, false);
      }
      if (!prepareTexture(m_environment, "textures/milky_way.dds")) {
        return false;
      }
    }
    const float angle = (end ? EndSkyOptions::rotation() / 360.0f : starAngle()) * 6.28318530718f;
    const float tilt = (end ? EndSkyOptions::elevation() : 30.0f) * 0.01745329252f;
    args.rotation = Vector4(std::cos(angle), std::sin(angle), std::cos(tilt), std::sin(tilt));
    args.intensity = Vector4(end ? 0.0f : starIntensity(),
      end ? EndSkyOptions::intensity() : milkyWayIntensity(),
      end ? 1.0f : std::clamp(visibility(), 0.0f, 1.0f), RtxOptions::zUp() ? 1.0f : 0.0f);
    if (end) {
      args.endParameters = Vector4(worldTimeSeconds() * EndSkyOptions::animationSpeed(),
        EndSkyOptions::backgroundIntensity(), EndSkyOptions::haloIntensity(), 0);
    }
    if (!end && meteorRate() > 0 && meteorIntensity() > 0) {
      const double period = 60.0 / meteorRate();
      const double time = std::max(0.0, double(worldTimeSeconds()));
      const int64_t event = int64_t(std::floor(time / period));
      for (uint32_t slot = 0; slot < 2; slot++) {
        const int64_t index = event - slot;
        const double start = double(index) * period + period * 0.7 * randomMeteor(uint32_t(index) * 17u + 739u);
        setMeteor(args, slot, index, float(time - start), meteorIntensity() * 3.0f);
      }
    }
    if (!m_constants.ptr()) {
      DxvkBufferCreateInfo info = {};
      info.size = sizeof(args);
      info.usage = VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
      info.stages = VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
      info.access = VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_UNIFORM_READ_BIT;
      m_constants = ctx->getDevice()->createBuffer(info, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
        DxvkMemoryStats::Category::RTXBuffer, "Night sky parameters");
    }
    if (!m_hasArgs || std::memcmp(&m_lastArgs, &args, sizeof(args)) != 0) {
      ctx->invalidateBuffer(m_constants, m_constants->allocSlice());
      ctx->writeToBuffer(m_constants, 0, sizeof(args), &args);
      m_lastArgs = args;
      m_hasArgs = true;
    }
    return active;
  }

  void NightSky::bind(const Rc<RtxContext>& ctx) {
    ctx->bindResourceView(DOME_COMPOSITE_NIGHT_FIELD, m_fieldView, nullptr);
    ctx->bindResourceSampler(DOME_COMPOSITE_NIGHT_FIELD, ctx->getResourceManager().getSampler(
      VK_FILTER_LINEAR, VK_SAMPLER_MIPMAP_MODE_LINEAR, VK_SAMPLER_ADDRESS_MODE_REPEAT,
      VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, VkClearColorValue(), 0, true));
    ctx->bindResourceView(DOME_COMPOSITE_GALAXY,
      isEndScene() ? m_endFieldView.ptr() : m_environment.getImageView(), nullptr);
    ctx->bindResourceSampler(DOME_COMPOSITE_GALAXY, ctx->getResourceManager().getSampler(
      VK_FILTER_LINEAR, VK_SAMPLER_MIPMAP_MODE_LINEAR, VK_SAMPLER_ADDRESS_MODE_REPEAT,
      VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, VkClearColorValue(), 0, true));
    ctx->bindResourceBuffer(DOME_COMPOSITE_NIGHT_ARGS, DxvkBufferSlice(m_constants));
  }
}
