/* SPDX-License-Identifier: MIT */
#pragma once

#include <cmath>
#include <cstdint>

#include "rtx_option.h"
#include "../../util/util_global_time.h"

namespace dxvk {
  struct RtxAnimationTime {
    RTX_OPTION_FLAG("rtx.animation", float, timeOverrideMs, -1.0f, RtxOptionFlags::NoSave,
      "Automation-only shader animation time in milliseconds. A negative value uses the runtime clock. "
      "The host must advance this value every rendered frame. It does not change measured frame times or simulation timers.");

    static bool overridden() {
      const float time = timeOverrideMs();
      return std::isfinite(time) && time >= 0.0f && time < float(1u << 24);
    }

    static uint32_t milliseconds() {
      return overridden()
        ? static_cast<uint32_t>(timeOverrideMs())
        : static_cast<uint32_t>(GlobalTime::get().absoluteTimeMs()) & ((1u << 24) - 1u);
    }

    static float seconds() {
      return milliseconds() / 1000.f;
    }
  };
}
