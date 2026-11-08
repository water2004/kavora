/* SPDX-License-Identifier: MIT */
#include <array>
#include <cmath>
#include <cstddef>
#include <iostream>
#include <stdexcept>

#include "rtx/pass/dome_composite/dome_composite_args.h"

namespace {
  void equal(float actual, float expected) {
    if (std::abs(actual - expected) > 0.00001f) {
      throw std::runtime_error("Dome blend result differs from its blend equation");
    }
  }
}

int main() {
  static_assert(sizeof(DomeCompositeArgs) == 128, "Dome push constants must match the shader");
  static_assert(offsetof(DomeCompositeArgs, outputToWorld) == 48);
  static_assert(offsetof(DomeCompositeArgs, radiance) == 96);
  static_assert(offsetof(DomeCompositeArgs, flags) == 120);
  try {
    const std::array<float, 11> expected { 0.35f, 0.4f, 0.8f, 0.68f, 0.84f, 0.36f, 1.0f, 0.16f, 0.32f, 0.65f, 0.32f };
    for (uint mode = 0; mode < expected.size(); ++mode) {
      equal(blendDomeChannel(0.2f, 0.8f, 0.25f, 1.0f, mode), expected[mode]);
      equal(blendDomeChannel(0.2f, 0.8f, 0.25f, 0.0f, mode), 0.2f);
      equal(blendDomeChannel(0.2f, 0.8f, 0.25f, 0.5f, mode), (0.2f + expected[mode]) * 0.5f);
    }
    equal(blendDomeChannel(2.0f, 3.0f, 1.0f, 1.0f, 6), 5.0f);
    equal(blendDomeChannel(2.0f, 3.0f, 0.0f, 1.0f, 0), 2.0f);
    equal(blendDomeChannel(2.0f, 3.0f, 1.0f, 1.0f, 0), 3.0f);
    const float atmosphere = blendDomeChannel(0.0f, 10.0f, 1.0f, 1.0f, 0);
    equal(blendDomeChannel(atmosphere, 2.0f, 1.0f, 0.25f, 0), 8.0f);
    equal(blendDomeChannel(2.0f, atmosphere, 1.0f, 1.0f, 0), 10.0f);
    // Vacuum preserves celestial radiance; an opaque boundary leaves only in-scattering.
    equal(transmitDomeChannel(2.0f, 0.0f, 1.0f, 1.0f), 2.0f);
    equal(transmitDomeChannel(2.0f, 0.3f, 0.0f, 1.0f), 0.3f);
    equal(transmitDomeChannel(2.0f, 0.3f, 0.25f, 1.0f), 0.8f);
    equal(transmitDomeChannel(2.0f, 0.3f, 0.25f, 0.0f), 2.0f);
    // Successive segments obey L = L1 + T1 * L2 and T = T1 * T2.
    equal(transmitDomeChannel(transmitDomeChannel(2.0f, 0.1f, 0.5f, 1.0f), 0.3f, 0.25f, 1.0f),
      transmitDomeChannel(2.0f, 0.325f, 0.125f, 1.0f));
    std::cout << "Dome blending tests passed\n";
    return 0;
  } catch (const std::exception& error) {
    std::cerr << error.what() << '\n';
    return 1;
  }
}
