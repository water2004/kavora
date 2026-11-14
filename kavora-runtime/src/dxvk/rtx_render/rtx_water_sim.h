// SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
// SPDX-License-Identifier: MIT
#pragma once

#include <array>
#include <cmath>

#include "rtx_options.h"
#include "rtx_resources.h"
#include "rtx/pass/water_sim/water_sim_args.h"
#include "rtx/pass/water_sim/water_surface_args.h"

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
    void setRain(float amount) { m_shaderArgs.rain = amount; }
    void prepare(RtxContext* ctx, bool hasWater, bool hasSludge);
    __forceinline WaterSurfaceArgs shaderArgs(int32_t originX, int32_t originZ) const {
      auto args = m_shaderArgs;
      // Both FFT cascades and the rain tiles repeat at this interval.
      args.tileOrigin = vec2(float(originX % 672), float(originZ % 672));
      args.gustPhase = float(std::remainder(
        double(originX) * double(0.058f) + double(originZ) * double(0.041f), 6.2831853071795864769));
      return args;
    }
    uint32_t effects() const { return m_shaderArgs.effects; }
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
    WaterSurfaceArgs m_shaderArgs{};
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
    float m_accumulatedTime = 0.0f;
    float m_activity = 0.0f;
    bool m_reset = true;
    bool m_resetImpacts = true;
  };
}
