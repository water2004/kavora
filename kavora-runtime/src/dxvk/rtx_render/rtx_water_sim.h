// SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
// SPDX-License-Identifier: MIT
#pragma once

#include <array>

#include "rtx_options.h"
#include "rtx_resources.h"
#include "rtx/pass/water_sim/water_sim_args.h"

namespace dxvk {
  class RtxContext;

  // Owns the camera-centred ripple clipmap. Only the runtime command thread mutates it.
  class RtxWaterSimulation {
  public:
    explicit RtxWaterSimulation(DxvkDevice*) {}

    RTX_OPTION("rtx.waterSim", bool, enable, false, "Simulate water disturbances in a camera-centred clipmap.");

    void addImpulses(const WaterSimImpulse* impulses, uint32_t count, bool reset);
    void prepare(RtxContext* ctx, bool hasWater);
    const vec4& shaderArgs() const { return m_shaderArgs; }
    const Rc<DxvkImageView>& view() const { return m_state[m_current].view; }

  private:
    std::array<Resources::Resource, 2> m_state;
    std::array<WaterSimImpulse, kWaterSimMaxImpulses> m_impulses{};
    vec4 m_shaderArgs{ 0.0f };
    Rc<DxvkBuffer> m_impulseBuffer;
    int32_t m_originX = 0;
    int32_t m_originZ = 0;
    uint32_t m_count = 0;
    uint32_t m_current = 0;
    float m_accumulatedTime = 0.0f;
    float m_activity = 0.0f;
    bool m_reset = true;
  };
}
