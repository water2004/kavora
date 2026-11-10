// SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
// SPDX-License-Identifier: MIT
#include <algorithm>
#include <cmath>

#include "rtx_water_sim.h"
#include "rtx_context.h"
#include "rtx_shader_manager.h"
#include "../../util/util_global_time.h"

#include <rtx_shaders/water_sim.h>

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

    constexpr float kCellSize = 0.5f;
    constexpr float kTimeStep = 1.0f / 60.0f;
    constexpr float kWaveSpeed = 5.0f;
    constexpr float kDamping = 0.991f;
  }

  void RtxWaterSimulation::addImpulses(const WaterSimImpulse* impulses, uint32_t count, bool reset) {
    if (reset) {
      m_reset = true;
      m_count = 0;
      m_activity = 0.0f;
      m_accumulatedTime = 0.0f;
    }
    if (count != 0) {
      const uint32_t accepted = std::min(count, kWaterSimMaxImpulses - m_count);
      std::copy_n(impulses, accepted, m_impulses.begin() + m_count);
      m_count += accepted;
      m_activity = 5.0f;
    }
  }

  void RtxWaterSimulation::prepare(RtxContext* ctx, bool hasWater) {
    m_shaderArgs = vec4(0.0f);
    if (!enable() || !hasWater) {
      m_state = {};
      m_impulseBuffer = nullptr;
      addImpulses(nullptr, 0, true);
      return;
    }

    const float delta = std::clamp(GlobalTime::get().deltaTime(), 0.0f, 4.0f * kTimeStep);
    m_activity = std::max(0.0f, m_activity - delta);
    if (m_activity == 0.0f && m_count == 0) {
      m_reset = true;
      return;
    }

    const auto& camera = ctx->getSceneManager().getCamera();
    const Vector3 position = camera.getPosition();
    const bool zUp = RtxOptions::zUp();
    const int32_t originX = int32_t(std::floor(position.x / kCellSize)) - int32_t(kWaterSimResolution / 2);
    const int32_t originZ = int32_t(std::floor((zUp ? position.y : position.z) / kCellSize)) - int32_t(kWaterSimResolution / 2);
    m_accumulatedTime += delta;
    const uint32_t steps = std::min(4u, uint32_t(m_accumulatedTime / kTimeStep));
    m_accumulatedTime -= steps * kTimeStep;

    if (steps != 0) {
      ScopedGpuProfileZone(ctx, "Water Simulation");
      if (m_impulseBuffer == nullptr) {
        Rc<DxvkContext> owner(ctx);
        for (auto& state : m_state) {
          state = Resources::createImageResource(owner, "Water ripple clipmap",
            VkExtent3D{ kWaterSimResolution, kWaterSimResolution, 1 }, VK_FORMAT_R16G16B16A16_SFLOAT);
        }
        DxvkBufferCreateInfo info;
        info.size = sizeof(m_impulses);
        info.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        info.stages = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT;
        info.access = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
        m_impulseBuffer = ctx->getDevice()->createBuffer(info, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
          DxvkMemoryStats::Category::RTXBuffer, "Water impulse upload");
      }
      if (m_count) {
        ctx->writeToBuffer(m_impulseBuffer, 0, m_count * sizeof(WaterSimImpulse), m_impulses.data());
      }
      ctx->bindResourceBuffer(WATER_SIM_BINDING_IMPULSES, DxvkBufferSlice(m_impulseBuffer));
      ctx->bindShader(VK_SHADER_STAGE_COMPUTE_BIT, WaterSimShader::getShader());
      for (uint32_t step = 0; step < steps; ++step) {
        WaterSimStep args{};
        args.shift = { originX - m_originX, originZ - m_originZ };
        args.origin = vec2(float(originX) * kCellSize, float(originZ) * kCellSize);
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
        m_originX = originX;
        m_originZ = originZ;
        m_count = 0;
        m_reset = false;
      }
    }
    if (!m_reset && m_state[m_current].view != nullptr) {
      m_shaderArgs = vec4(float(m_originX) * kCellSize, float(m_originZ) * kCellSize,
        1.0f / (kCellSize * kWaterSimResolution), 1.0f);
    }
  }
}
