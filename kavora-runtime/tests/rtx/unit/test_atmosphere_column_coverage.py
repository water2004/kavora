# SPDX-License-Identifier: MIT
"""Contracts for primary pixel integration and the shared haze prefixes."""

from math import exp
from pathlib import Path
import unittest


GBUFFER = (
    Path(__file__).resolve().parents[3]
    / "src/dxvk/shaders/rtx/pass/gbuffer"
)


class AtmosphereColumnCoverage(unittest.TestCase):
    def test_transfer_is_interpolated_in_optical_depth(self):
        near, end, depth = 20.0, 60.0, 33.0
        extinction, equilibrium_radiance = 0.04, 3.5
        from_t, to_t = exp(-extinction * near), exp(-extinction * end)
        fraction = (depth - near) / (end - near)
        partial_t = (to_t / from_t) ** fraction
        weight = (1 - partial_t) / (1 - to_t / from_t)
        reconstructed = equilibrium_radiance * (1 - from_t + (from_t - to_t) * weight)
        self.assertAlmostEqual(from_t * partial_t, exp(-extinction * depth))
        self.assertAlmostEqual(reconstructed, equilibrium_radiance * (1 - exp(-extinction * depth)))

    def test_each_primary_ray_uses_its_own_endpoint(self):
        shader = (GBUFFER / "gbuffer_primary_atmosphere.comp.slang").read_text()
        self.assertIn("loadAtmosphereEndpoint(pixel)", shader)
        self.assertIn("storePrimaryAtmosphere(pixel, transfer)", shader)
        self.assertIn("float2(pixel) + 0.5f", shader)
        self.assertIn("cloudPixelSampleOffset(pixel, cb.frameIdx)", shader)


if __name__ == "__main__":
    unittest.main()
