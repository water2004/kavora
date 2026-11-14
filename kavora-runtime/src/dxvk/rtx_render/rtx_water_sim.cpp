// SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
// SPDX-License-Identifier: MIT
#include <algorithm>
#include <cmath>
#include <random>

#include "rtx_water_sim.h"
#include "rtx_context.h"
#include "rtx_shader_manager.h"
#include "../../util/util_global_time.h"

#include <rtx_shaders/water_sim.h>
#include <rtx_shaders/water_droplet_rings.h>
#include <rtx_shaders/water_impact_effects.h>
#include <rtx_shaders/water_wind_rows.h>
#include <rtx_shaders/water_wind_columns.h>

namespace dxvk {
  namespace {
    class WaterSimShader : public ManagedShader {
      SHADER_SOURCE(WaterSimShader, VK_SHADER_STAGE_COMPUTE_BIT, water_sim)
      PUSH_CONSTANTS(WaterSimStep)
      BEGIN_PARAMETER()
        TEXTURE2D(WATER_SIM_BINDING_PREVIOUS)
        RW_TEXTURE2D(WATER_SIM_BINDING_CURRENT)
        STRUCTURED_BUFFER(WATER_SIM_BINDING_IMPULSES)
      END_PARAMETER()
    };

    class WaterWindRows : public ManagedShader {
      SHADER_SOURCE(WaterWindRows, VK_SHADER_STAGE_COMPUTE_BIT, water_wind_rows)
      PUSH_CONSTANTS(WaterDetailStep)
      BEGIN_PARAMETER()
        STRUCTURED_BUFFER(WATER_DETAIL_BINDING_INPUT)
        RW_STRUCTURED_BUFFER(WATER_DETAIL_BINDING_OUTPUT)
      END_PARAMETER()
    };

    class WaterWindColumns : public ManagedShader {
      SHADER_SOURCE(WaterWindColumns, VK_SHADER_STAGE_COMPUTE_BIT, water_wind_columns)
      BEGIN_PARAMETER()
        STRUCTURED_BUFFER(WATER_DETAIL_BINDING_INPUT)
        { WATER_DETAIL_BINDING_OUTPUT, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_IMAGE_VIEW_TYPE_2D_ARRAY, VK_ACCESS_SHADER_WRITE_BIT },
      END_PARAMETER()
    };

    class WaterDropletRings : public ManagedShader {
      SHADER_SOURCE(WaterDropletRings, VK_SHADER_STAGE_COMPUTE_BIT, water_droplet_rings)
      PUSH_CONSTANTS(WaterDetailStep)
      BEGIN_PARAMETER()
        RW_TEXTURE2D(WATER_DETAIL_BINDING_OUTPUT)
      END_PARAMETER()
    };

    class WaterImpactEffects : public ManagedShader {
      SHADER_SOURCE(WaterImpactEffects, VK_SHADER_STAGE_COMPUTE_BIT, water_impact_effects)
      PUSH_CONSTANTS(WaterImpactStep)
      BEGIN_PARAMETER()
        TEXTURE2D(WATER_SIM_BINDING_PREVIOUS)
        RW_TEXTURE2D(WATER_SIM_BINDING_CURRENT)
        STRUCTURED_BUFFER(WATER_SIM_BINDING_IMPULSES)
      END_PARAMETER()
    };

    constexpr float kCellSize = 0.5f;
    constexpr float kTimeStep = 1.0f / 60.0f;
    constexpr float kWaveSpeed = 5.0f;
    constexpr float kDamping = 0.991f;
    constexpr float kTau = 6.28318530718f;
    constexpr uint32_t kWindBinCount = kWaterWindResolution * kWaterWindResolution;

    Rc<DxvkBuffer> createWaterBuffer(RtxContext* ctx, VkDeviceSize size, const char* name) {
      DxvkBufferCreateInfo info;
      info.size = size;
      info.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
      info.stages = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT;
      info.access = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
      return ctx->getDevice()->createBuffer(info, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
        DxvkMemoryStats::Category::RTXBuffer, name);
    }

    std::array<vec4, kWindBinCount * kWaterWindCascades> createWindSpectrum() {
      std::array<vec4, kWindBinCount * kWaterWindCascades> spectrum{};
      std::mt19937 random(0x71c47e3u);
      std::normal_distribution<float> gaussian;
      constexpr int half = int(kWaterWindResolution / 2);
      for (uint32_t cascade = 0; cascade < kWaterWindCascades; ++cascade) {
        const float tile = cascade == 0 ? kWaterWindTileLong : kWaterWindTileShort;
        const float rms = cascade == 0 ? 0.045f : 0.013f;
        const float peak = kTau / (cascade == 0 ? 9.0f : 2.2f);
        const float periodStep = kTau / kWaterWindLoopSeconds;
        float energy = 0.0f;
        for (int y = 0; y < int(kWaterWindResolution); ++y) {
          for (int x = 0; x < int(kWaterWindResolution); ++x) {
            if ((x == 0 && y == 0) || x == half || y == half) {
              continue;
            }
            const float kx = float(x < half ? x : x - 2 * half) * (kTau / tile);
            const float ky = float(y < half ? y : y - 2 * half) * (kTau / tile);
            const float length2 = kx * kx + ky * ky;
            const float waveNumber = std::sqrt(length2);
            const float windDot = (0.8f * kx + 0.6f * ky) / waveNumber;
            const float directional = 0.2f + 0.8f * windDot * windDot;
            const float amplitude = std::exp(-0.5f * peak * peak / length2 - 0.002f * length2) * directional / length2;
            const float re = gaussian(random) * amplitude;
            const float im = gaussian(random) * amplitude;
            const float omega = std::round(std::sqrt(9.81f * waveNumber) / periodStep) * periodStep;
            spectrum[cascade * kWindBinCount + y * kWaterWindResolution + x] = vec4(re, im, omega, 0.0f);
            energy += re * re + im * im;
          }
        }
        const float scale = rms / std::sqrt(2.0f * energy);
        for (uint32_t i = 0; i < kWindBinCount; ++i) {
          auto& bin = spectrum[cascade * kWindBinCount + i];
          bin.x *= scale;
          bin.y *= scale;
        }
      }
      return spectrum;
    }
  }

  void RtxWaterSimulation::addImpulses(const WaterSimImpulse* impulses, uint32_t count, bool reset) {
    if (reset) {
      m_reset = true;
      m_resetImpacts = true;
      m_count = 0;
      m_appliedImpactImpulses = 0;
      m_activity = 0.0f;
      m_accumulatedTime = 0.0f;
      setRain(0.0f);
    }
    if (count != 0) {
      const uint32_t accepted = std::min(count, kWaterSimMaxImpulses - m_count);
      std::copy_n(impulses, accepted, m_impulses.begin() + m_count);
      m_count += accepted;
      m_activity = 5.0f;
    }
  }

  void RtxWaterSimulation::translateSceneOrigin(const Vector3& offset) {
    const vec2 planar { offset.x, RtxOptions::zUp() ? 0.0f : offset.z };
    const ivec2 cells { int32_t(planar.x / kCellSize), int32_t(planar.y / kCellSize) };
    m_originX += cells.x;
    m_originZ += cells.y;
    m_impactOrigin.x += cells.x;
    m_impactOrigin.y += cells.y;
    for (uint32_t i = 0; i < m_count; ++i)
      m_impulses[i].position = m_impulses[i].position + planar;
  }

  void RtxWaterSimulation::prepare(RtxContext* ctx, bool hasWater, bool hasSludge) {
    m_shaderArgs.simulationClip = vec4(0.0f);
    m_shaderArgs.impactClip = {};
    m_shaderArgs.effects = 0;
    const bool sludge = sludgeEnabled() && hasSludge;
    const bool surface = hasWater || sludge;
    const float rippleScale = std::clamp(rippleStrength(), 0.0f, 2.0f);
    const float windScale = std::clamp(windStrength(), 0.0f, 2.0f);
    const float ringScale = std::clamp(ringStrength(), 0.0f, 2.0f);
    const bool ripples = surface && enable() && rippleScale > 0.0f;
    const bool wind = surface && windEnabled() && windScale > 0.0f;
    const bool rings = surface && ringsEnabled() && ringScale > 0.0f;
    const uint32_t impactEffects = (sludge ? WATER_EFFECT_SLUDGE : 0u) | (rings ? WATER_EFFECT_RINGS : 0u);
    const float delta = std::clamp(GlobalTime::get().deltaTime(), 0.0f, 4.0f * kTimeStep);
    const uint64_t timeMs = GlobalTime::get().absoluteTimeMs();

    if (wind) {
      prepareWind(ctx, float(timeMs % uint64_t(kWaterWindLoopSeconds * 1000.0f)) * 0.001f, windScale);
      m_shaderArgs.effects |= WATER_EFFECT_WIND;
    } else if (m_windSeed != nullptr) {
      m_wind = {};
      m_windSeed = nullptr;
      m_windScratch = nullptr;
    }
    if (!ripples) {
      m_state = {};
      m_reset = true;
      m_accumulatedTime = 0.0f;
    }
    if (!impactEffects && m_impacts[0].view != nullptr) {
      m_impacts = {};
      m_resetImpacts = true;
      m_appliedImpactImpulses = 0;
    }
    if (!rings) {
      m_rings = {};
    }
    const bool active = m_count || m_activity > 0.0f;
    const bool runRipples = ripples && active;
    const bool runImpacts = impactEffects && active;
    if (rings && (m_shaderArgs.rain > 0.0f || runImpacts)) {
      prepareRings(ctx, float(timeMs % 4096000u) * 0.001f, ringScale);
      m_shaderArgs.effects |= WATER_EFFECT_RINGS;
    }
    if (!runRipples && !runImpacts) {
      m_reset = true;
      m_resetImpacts = true;
      m_count = 0;
      m_appliedImpactImpulses = 0;
      m_activity = 0.0f;
      m_accumulatedTime = 0.0f;
      if (!ripples && !impactEffects) {
        m_impulseBuffer = nullptr;
      }
      return;
    }

    const auto& camera = ctx->getSceneManager().getCamera();
    const Vector3 position = camera.getPosition();
    const ivec2 origin{ int32_t(std::floor(position.x / kCellSize)) - int32_t(kWaterSimResolution / 2),
      int32_t(std::floor((RtxOptions::zUp() ? position.y : position.z) / kCellSize)) - int32_t(kWaterSimResolution / 2) };
    if (m_impulseBuffer == nullptr) {
      m_impulseBuffer = createWaterBuffer(ctx, sizeof(m_impulses), "Water impulse upload");
    }
    if (m_count) {
      ctx->writeToBuffer(m_impulseBuffer, 0, m_count * sizeof(WaterSimImpulse), m_impulses.data());
    }
    if (runImpacts) {
      prepareImpacts(ctx, origin, delta, impactEffects);
      m_shaderArgs.effects |= WATER_EFFECT_IMPACTS | (impactEffects & WATER_EFFECT_SLUDGE);
    }
    if (runRipples) {
      prepareRipples(ctx, origin, delta, rippleScale);
    } else {
      m_count = 0;
    }
    if (!m_count) {
      m_appliedImpactImpulses = 0;
    }
    m_activity = std::max(0.0f, m_activity - delta);
  }

  void RtxWaterSimulation::prepareRipples(RtxContext* ctx, ivec2 origin, float delta, float strength) {
    m_accumulatedTime += delta;
    const uint32_t steps = std::min(4u, uint32_t(m_accumulatedTime / kTimeStep));
    m_accumulatedTime -= steps * kTimeStep;

    if (steps != 0) {
      ScopedGpuProfileZone(ctx, "Water Simulation");
      if (m_state[0].view == nullptr) {
        Rc<DxvkContext> owner(ctx);
        for (auto& state : m_state) {
          state = Resources::createImageResource(owner, "Water ripple clipmap",
            VkExtent3D{ kWaterSimResolution, kWaterSimResolution, 1 }, VK_FORMAT_R16G16B16A16_SFLOAT);
        }
      }
      ctx->bindResourceBuffer(WATER_SIM_BINDING_IMPULSES, DxvkBufferSlice(m_impulseBuffer));
      ctx->bindShader(VK_SHADER_STAGE_COMPUTE_BIT, WaterSimShader::getShader());
      for (uint32_t step = 0; step < steps; ++step) {
        WaterSimStep args{};
        args.shift = { origin.x - m_originX, origin.y - m_originZ };
        args.origin = vec2(float(origin.x) * kCellSize, float(origin.y) * kCellSize);
        args.cellSize = kCellSize;
        args.travel = (kWaveSpeed * kWaveSpeed * kTimeStep) / (kCellSize * kCellSize);
        args.damping = kDamping;
        args.timeStep = kTimeStep;
        args.impulseCount = m_count;
        args.reset = m_reset;
        ctx->bindResourceView(WATER_SIM_BINDING_PREVIOUS, m_state[m_current].view, nullptr);
        ctx->bindResourceView(WATER_SIM_BINDING_CURRENT, m_state[m_current ^ 1u].view, nullptr);
        ctx->pushConstants(0, sizeof(args), &args);
        ctx->dispatch(kWaterSimResolution / 8, kWaterSimResolution / 8, 1);
        m_current ^= 1u;
        m_originX = origin.x;
        m_originZ = origin.y;
        m_count = 0;
        m_reset = false;
      }
    }
    if (!m_reset && m_state[m_current].view != nullptr) {
      m_shaderArgs.simulationClip = vec4(float(m_originX) * kCellSize, float(m_originZ) * kCellSize,
        1.0f / (kCellSize * kWaterSimResolution), strength);
      m_shaderArgs.effects |= WATER_EFFECT_RIPPLES;
    }
  }

  void RtxWaterSimulation::prepareWind(RtxContext* ctx, float time, float strength) {
    ScopedGpuProfileZone(ctx, "Water Wind FFT");
    if (m_windSeed == nullptr) {
      Rc<DxvkContext> owner(ctx);
      m_wind = Resources::createImageResource(owner, "Water wind cascades",
        VkExtent3D{ kWaterWindResolution, kWaterWindResolution, 1 }, VK_FORMAT_R16G16B16A16_SFLOAT,
        kWaterWindCascades, VK_IMAGE_TYPE_2D, VK_IMAGE_VIEW_TYPE_2D_ARRAY);
      const auto spectrum = createWindSpectrum();
      m_windSeed = createWaterBuffer(ctx, sizeof(spectrum), "Water wind spectrum");
      m_windScratch = createWaterBuffer(ctx, sizeof(spectrum), "Water wind FFT scratch");
      ctx->writeToBuffer(m_windSeed, 0, sizeof(spectrum), spectrum.data());
    }
    const WaterDetailStep args{ time, strength };
    ctx->bindShader(VK_SHADER_STAGE_COMPUTE_BIT, WaterWindRows::getShader());
    ctx->bindResourceBuffer(WATER_DETAIL_BINDING_INPUT, DxvkBufferSlice(m_windSeed));
    ctx->bindResourceBuffer(WATER_DETAIL_BINDING_OUTPUT, DxvkBufferSlice(m_windScratch));
    ctx->pushConstants(0, sizeof(args), &args);
    ctx->dispatch(kWaterWindResolution, kWaterWindCascades, 1);
    ctx->bindShader(VK_SHADER_STAGE_COMPUTE_BIT, WaterWindColumns::getShader());
    ctx->bindResourceBuffer(WATER_DETAIL_BINDING_INPUT, DxvkBufferSlice(m_windScratch));
    ctx->bindResourceView(WATER_DETAIL_BINDING_OUTPUT, m_wind.view, nullptr);
    ctx->dispatch(kWaterWindResolution, kWaterWindCascades, 1);
  }

  void RtxWaterSimulation::prepareRings(RtxContext* ctx, float time, float strength) {
    ScopedGpuProfileZone(ctx, "Water Droplet Rings");
    if (m_rings.view == nullptr) {
      Rc<DxvkContext> owner(ctx);
      m_rings = Resources::createImageResource(owner, "Water droplet rings",
        VkExtent3D{ kWaterRingResolution, kWaterRingResolution, 1 }, VK_FORMAT_R16G16_SFLOAT);
    }
    const WaterDetailStep args{ time, strength };
    ctx->bindShader(VK_SHADER_STAGE_COMPUTE_BIT, WaterDropletRings::getShader());
    ctx->bindResourceView(WATER_DETAIL_BINDING_OUTPUT, m_rings.view, nullptr);
    ctx->pushConstants(0, sizeof(args), &args);
    ctx->dispatch(kWaterRingResolution / 8, kWaterRingResolution / 8, 1);
  }

  void RtxWaterSimulation::prepareImpacts(RtxContext* ctx, ivec2 origin, float delta, uint32_t effects) {
    ScopedGpuProfileZone(ctx, "Water Impact Effects");
    if (m_impacts[0].view == nullptr) {
      Rc<DxvkContext> owner(ctx);
      for (auto& state : m_impacts) {
        state = Resources::createImageResource(owner, "Water impact clipmap",
          VkExtent3D{ kWaterSimResolution, kWaterSimResolution, 1 }, VK_FORMAT_R16G16_SFLOAT);
      }
    }
    const vec2 worldOrigin(float(origin.x) * kCellSize, float(origin.y) * kCellSize);
    const WaterImpactStep args{ { origin.x - m_impactOrigin.x, origin.y - m_impactOrigin.y }, worldOrigin, kCellSize, delta,
      m_appliedImpactImpulses, m_count, effects, m_resetImpacts };
    ctx->bindShader(VK_SHADER_STAGE_COMPUTE_BIT, WaterImpactEffects::getShader());
    ctx->bindResourceBuffer(WATER_SIM_BINDING_IMPULSES, DxvkBufferSlice(m_impulseBuffer));
    ctx->bindResourceView(WATER_SIM_BINDING_PREVIOUS, m_impacts[m_currentImpact].view, nullptr);
    ctx->bindResourceView(WATER_SIM_BINDING_CURRENT, m_impacts[m_currentImpact ^ 1u].view, nullptr);
    ctx->pushConstants(0, sizeof(args), &args);
    ctx->dispatch(kWaterSimResolution / 8, kWaterSimResolution / 8, 1);
    m_currentImpact ^= 1u;
    m_impactOrigin = origin;
    m_resetImpacts = false;
    m_appliedImpactImpulses = m_count;
    m_shaderArgs.impactClip = vec3(worldOrigin.x, worldOrigin.y, 1.0f / (kCellSize * kWaterSimResolution));
  }
}
