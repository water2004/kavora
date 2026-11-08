/* SPDX-License-Identifier: MIT */
#pragma once

#include "rtx_lights.h"
#include "rtx/pass/dome_composite/night_sky_args.h"

namespace dxvk {
  class RtxContext;

  struct EndSkyOptions {
    RTX_OPTION("rtx.endSky", bool, enabled, true, "Show the End's ringed planet, accretion flow, nebula and stars.");
    RTX_OPTION_ARGS("rtx.endSky", float, intensity, 1.0f, "End environment radiance multiplier.", args.minValue = 0.0f, args.maxValue = 4.0f);
    RTX_OPTION_ARGS("rtx.endSky", float, backgroundIntensity, 1.0f, "Brightness of the End's stellar and nebular background.", args.minValue = 0.0f, args.maxValue = 4.0f);
    RTX_OPTION_ARGS("rtx.endSky", float, haloIntensity, 1.0f, "Brightness of the extended halo around the accretion flow.", args.minValue = 0.0f, args.maxValue = 4.0f);
    RTX_OPTION_ARGS("rtx.endSky", float, animationSpeed, 1.0f, "World-synchronized End animation speed. Zero freezes the environment.", args.minValue = 0.0f, args.maxValue = 4.0f);
    RTX_OPTION_ARGS("rtx.endSky", float, rotation, 0.0f, "End environment azimuth, in degrees.", args.minValue = 0.0f, args.maxValue = 360.0f);
    RTX_OPTION_ARGS("rtx.endSky", float, elevation, 0.0f, "Pitch of the complete cosmic environment, in degrees, without a ground plane or horizon clip.", args.minValue = -90.0f, args.maxValue = 90.0f);
  };

  class NightSky {
  public:
    static constexpr uint32_t kWidth = 4096;
    static constexpr uint32_t kHeight = 2048;
    bool prepare(const Rc<RtxContext>& ctx);
    void bind(const Rc<RtxContext>& ctx);
    static bool isEndScene() { return sceneType() == 2; }

    RTX_OPTION("rtx.nightSky", bool, enabled, true, "Show stars, the Milky Way and meteors through the shared ray-traced environment.");
    RTX_OPTION_ARGS("rtx.nightSky", float, starIntensity, 1.0f, "Starlight radiance multiplier.", args.minValue = 0.0f, args.maxValue = 4.0f);
    RTX_OPTION_ARGS("rtx.nightSky", float, milkyWayIntensity, 1.0f, "Milky Way radiance multiplier.", args.minValue = 0.0f, args.maxValue = 4.0f);
    RTX_OPTION_ARGS("rtx.nightSky", float, meteorRate, 6.0f, "Average meteor events per world minute. Zero disables meteors.", args.minValue = 0.0f, args.maxValue = 60.0f);
    RTX_OPTION_ARGS("rtx.nightSky", float, meteorIntensity, 1.0f, "Meteor radiance multiplier.", args.minValue = 0.0f, args.maxValue = 8.0f);
    RTX_OPTION_FLAG("rtx.nightSky", uint32_t, sceneType, 0, RtxOptionFlags::NoSave, "Host sky: 0: None, 1: Celestial, 2: End.");
    RTX_OPTION_FLAG("rtx.nightSky", float, visibility, 0.0f, RtxOptionFlags::NoSave, "Host star visibility before weather and cloud attenuation.");
    RTX_OPTION_FLAG("rtx.nightSky", float, starAngle, 0.0f, RtxOptionFlags::NoSave, "Host star rotation, in turns.");
    RTX_OPTION_FLAG("rtx.nightSky", uint32_t, moonPhase, 0, RtxOptionFlags::NoSave, "Host lunar phase: 0: Full, 2: Third quarter, 4: New, 6: First quarter.");
    RTX_OPTION_FLAG("rtx.nightSky", float, worldTimeSeconds, 0.0f, RtxOptionFlags::NoSave, "World-synchronized celestial animation time.");

  private:
    void createField(const Rc<RtxContext>& ctx, bool end);
    Rc<DxvkImage> m_field;
    Rc<DxvkImageView> m_fieldView;
    TextureRef m_environment;
    Rc<DxvkImage> m_endField;
    Rc<DxvkImageView> m_endFieldView;
    Rc<DxvkBuffer> m_constants;
    NightSkyArgs m_lastArgs = {};
    bool m_hasArgs = false;
  };
}
