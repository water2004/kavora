/* SPDX-License-Identifier: MIT */
#include "../../../src/dxvk/shaders/rtx/utility/shader_types.h"
#include "../../../src/dxvk/shaders/rtx/utility/volume_integral.h"

#include <array>
#include <cstdio>
#include <cstdlib>
#include <limits>
#include <random>

namespace {
  struct Segment {
    double radiance = 0;
    double transmittance = 1;
  };

  void require(bool condition, const char* message) {
    if (!condition) {
      std::fprintf(stderr, "FAIL: %s\n", message);
      std::exit(1);
    }
  }

  Segment integrate(float scatteringExtinction, float absorption, float source, float length) {
    float extinction = scatteringExtinction + absorption;
    float transmittance = dxvk::volumeTransmittance(extinction, length);
    return { source * dxvk::volumeSourceIntegral(extinction, length, transmittance), transmittance };
  }

  Segment compose(Segment leading, Segment trailing) {
    return { leading.radiance + leading.transmittance * trailing.radiance, leading.transmittance * trailing.transmittance };
  }

  double reference(float extinction, float source, float length) {
    // Independent double-precision solution of dL/ds = source - extinction * L.
    return extinction > 0 ? -source * std::expm1(-double(extinction) * length) / extinction : double(source) * length;
  }

  void verify(Segment actual, Segment expected, const char* message) {
    require(std::abs(actual.radiance - expected.radiance) < 0.00002 * std::max(1.0, std::abs(expected.radiance))
      && std::abs(actual.transmittance - expected.transmittance) < 0.00002, message);
  }
}

int main() {
  require(dxvk::volumeSourceIntegral(0, 0, 1) == 0, "Empty segment emits light");
  require(dxvk::volumeSourceIntegral(0, 7, 1) == 7, "Zero extinction loses its finite integral");
  require(dxvk::volumeTransmittance(0, std::numeric_limits<float>::infinity()) == 1,
    "A clear-air sky miss loses its background");
  require(dxvk::volumeTransmittance(0.02f, std::numeric_limits<float>::infinity()) == 0,
    "An absorbing medium transmits an infinite ray");
  require(dxvk::volumeTransmittance(20, 0) == 1, "A zero-length ray absorbs light");
  verify(integrate(0, 0.2f, 0, 5), { 0, std::exp(-1.0) }, "Pure absorption is not Beer-Lambert transport");

  std::mt19937 random(0x20261108);
  std::uniform_real_distribution<float> extinctions(0, 0.3f), sources(0, 2), lengths(0, 80), fractions(0, 1);
  double maximumError = 0;
  for (int test = 0; test < 2000; ++test) {
    float fog = extinctions(random);
    float absorption = extinctions(random);
    float source = sources(random);
    float length = lengths(random);
    float totalExtinction = fog + absorption;
    Segment expected = { reference(totalExtinction, source, length), std::exp(-double(totalExtinction) * length) };
    Segment whole = integrate(fog, absorption, source, length);
    verify(whole, expected, "Joint absorption and scattering differ from the analytic solution");

    // Transparent cutouts change the partition, never the medium or its source.
    std::array<float, 33> cuts = {};
    cuts.back() = length;
    for (size_t i = 1; i + 1 < cuts.size(); ++i) {
      cuts[i] = fractions(random) * length;
    }
    std::sort(cuts.begin(), cuts.end());
    Segment split;
    for (size_t i = 1; i < cuts.size(); ++i) {
      split = compose(split, integrate(fog, absorption, source, cuts[i] - cuts[i - 1]));
    }
    verify(split, expected, "Transparent planes change accumulated radiance or attenuation");
    maximumError = std::max(maximumError, std::abs(split.radiance - expected.radiance));
  }

  // A fog slab has source-free absorbing medium both before and behind it.
  Segment slab = compose(integrate(0, 0.02f, 0, 5),
    compose(integrate(0.1f, 0.02f, 0.1f, 10), integrate(0, 0.02f, 0, 15)));
  verify(slab, { std::exp(-0.1) * reference(0.12f, 0.1f, 10), std::exp(-1.6) },
    "Absorption outside the fog interval is lost or applied to the wrong source");

  // Include channels with no absorption alongside nearly opaque channels.
  for (float absorption : { 0.0f, 1e-8f, 0.02f, 20.0f }) {
    Segment empty = integrate(0, absorption, 0, 0);
    verify(empty, {}, "A zero-length segment changes a channel");
    Segment infinite = integrate(0, absorption, 0, std::numeric_limits<float>::max());
    require(std::isfinite(infinite.radiance) && infinite.radiance == 0,
      "An infinite source-free ray produces nonfinite radiance");
    require(infinite.transmittance == (absorption == 0 ? 1 : 0), "Infinite-distance absorption has the wrong limit");
  }
  std::printf("Volume transport: 2000 analytic and 32-cutout cases passed; max radiance error %.9g.\n", maximumError);
  return 0;
}
