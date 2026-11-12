// SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
// SPDX-License-Identifier: MIT
#pragma once

#include <array>

#include "rtx_options.h"
#include "rtx_resources.h"
#include "rtx/pass/water_sim/water_sim_args.h"

namespace dxvk {
  class RtxContext;

  // Owns the water simulation resources on the runtime command thread.
  class RtxWaterSimulation {
  public:
    void translateSceneOrigin(const Vector3& offset);
    explicit RtxWaterSimulation(DxvkDevice*) {}

    RTX_OPTION("rtx.waterSim", bool, enable, false, "Simulate water disturbances in a camera-centred clipmap.");
    RTX_OPTION("rtx.waterSim", bool, windEnabled, false, "Enable FFT wind waves on simulated water.");
    RTX_OPTION("rtx.waterSim", bool, ringsEnabled, false, "Enable droplet rings from rain and splash spray.");
    RTX_OPTION("rtx.waterSim", bool, sludgeEnabled, false, "Open and heal holes in marked floating sludge.");

    void addImpulses(const WaterSimImpulse* impulses, uint32_t count, bool reset);
    void setRain(float amount) { m_rain = amount; }
    void prepare(RtxContext* ctx, bool hasWater, bool hasSludge);
    const vec4& shaderArgs() const { return m_shaderArgs; }
    const vec4& impactArgs() const { return m_impactArgs; }
    uint32_t effects() const { return m_effects; }
    float rain() const { return m_rain; }
    const Rc<DxvkImageView>& view() const { return m_state[m_current].view; }
    const Rc<DxvkImageView>& windView() const { return m_wind.view; }
    const Rc<DxvkImageView>& ringView() const { return m_rings.view; }
    const Rc<DxvkImageView>& impactView() const { return m_impacts[m_currentImpact].view; }

  private:
    void prepareRipples(RtxContext* ctx, ivec2 origin, float delta);
    void prepareWind(RtxContext* ctx, float time);
    void prepareRings(RtxContext* ctx, float time);
    void prepareImpacts(RtxContext* ctx, ivec2 origin, float delta, uint32_t effects);

    std::array<Resources::Resource, 2> m_state;
    std::array<Resources::Resource, 2> m_impacts;
    Resources::Resource m_wind;
    Resources::Resource m_rings;
    std::array<WaterSimImpulse, kWaterSimMaxImpulses> m_impulses{};
    vec4 m_shaderArgs{ 0.0f };
    vec4 m_impactArgs{ 0.0f };
    Rc<DxvkBuffer> m_impulseBuffer;
    Rc<DxvkBuffer> m_windSeed;
    Rc<DxvkBuffer> m_windScratch;
    int32_t m_originX = 0;
    int32_t m_originZ = 0;
    ivec2 m_impactOrigin{ 0, 0 };
    uint32_t m_count = 0;
    uint32_t m_current = 0;
    uint32_t m_currentImpact = 0;
    uint32_t m_appliedImpactImpulses = 0;
    uint32_t m_effects = 0;
    float m_accumulatedTime = 0.0f;
    float m_activity = 0.0f;
    float m_rain = 0.0f;
    bool m_reset = true;
    bool m_resetImpacts = true;
  };
}
