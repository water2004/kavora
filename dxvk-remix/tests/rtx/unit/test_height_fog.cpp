/* SPDX-License-Identifier: MIT */
#include "../../../src/dxvk/shaders/rtx/utility/height_fog.h"

#include <cstdio>
#include <cstdlib>
#include <random>

namespace {
  void require(bool condition, const char* message) {
    if (!condition) {
      std::fprintf(stderr, "FAIL: %s\n", message);
      std::exit(1);
    }
  }

  double reference(double from, double to, double base, double falloff) {
    double result = 0;
    constexpr int steps = 8192;
    for (int i = 0; i < steps; ++i) {
      double y = from + (to - from) * (i + 0.5) / steps;
      result += std::exp(-std::max(y - base, 0.0) / falloff);
    }
    return result / steps;
  }
}

int main() {
  using dxvk::heightFogSegmentDensity;
  require(heightFogSegmentDensity(-1000, 1000, 64, 0) == 1, "Disabled height fog changes density");
  require(heightFogSegmentDensity(-100, 64, 64, 30) == 1, "Density below the base is not one");
  require(std::abs(heightFogSegmentDensity(94, 94, 64, 30) - std::exp(-1.0f)) < 1e-6f,
    "Horizontal ray does not preserve its local density");
  require(heightFogSegmentDensity(100000, 100001, 64, 1) == 0, "High-altitude density does not vanish");
  std::mt19937 random(0x20261103);
  std::uniform_real_distribution<float> heights(-256, 512), bases(-64, 192), falloffs(1, 128);
  for (int i = 0; i < 2000; ++i) {
    float from = heights(random), to = heights(random), base = bases(random), falloff = falloffs(random);
    float value = heightFogSegmentDensity(from, to, base, falloff);
    require(value >= 0 && value <= 1 && std::isfinite(value), "Density is outside physical bounds");
    require(std::abs(value - reference(from, to, base, falloff)) < 0.0002,
      "Analytic optical depth differs from numerical integration");
    require(value == heightFogSegmentDensity(to, from, base, falloff), "Reversing a segment changes optical depth");
    require(std::abs(value - heightFogSegmentDensity(from * 100, to * 100, base * 100, falloff * 100)) < 0.00001f,
      "Changing scene units changes optical depth");
  }
  std::puts("Height fog conserves integrated optical depth across ray directions and scene units.");
  return 0;
}
