# SPDX-License-Identifier: MIT
"""CPU contracts for the primary atmosphere column layout and reconstruction."""

from itertools import permutations
from math import exp, inf
from pathlib import Path
import re
import unittest


GBUFFER = (
    Path(__file__).resolve().parents[3]
    / "src/dxvk/shaders/rtx/pass/gbuffer"
)


def sort_block_endpoints(a, b, c, d):
    lo = min(a, b), min(c, d)
    hi = max(a, b), max(c, d)
    middle = max(lo), min(hi)
    return min(lo), min(middle), max(middle), max(hi)


def coarse_endpoints(depths):
    finite = [distance for distance in depths if distance != inf]
    if not finite:
        return (inf,) * 4
    near, far = min(finite), max(finite)
    return near, near + (far - near) * 0.5, far, inf if inf in depths else far


class AtmosphereColumnCoverage(unittest.TestCase):
    def test_standard_column_preserves_each_surface_and_miss(self):
        depths = (0.125, 63.0, 8100.0, inf)
        for endpoints in permutations(depths):
            self.assertEqual(sort_block_endpoints(*endpoints), depths)

    def test_edge_column_supports_repeated_endpoints(self):
        self.assertEqual(sort_block_endpoints(100, inf, 100, inf), (100, 100, inf, inf))
        self.assertEqual(sort_block_endpoints(inf, inf, inf, inf), (inf,) * 4)

    def test_mixed_tile_never_interpolates_toward_sky_from_finite_depth(self):
        anchors = coarse_endpoints((17, 63, 51, inf, 32))
        self.assertEqual(anchors, (17, 40, 63, inf))
        for depth in (17, 32, 51, 63):
            upper = sum(anchor < depth for anchor in anchors[:3])
            self.assertLess(anchors[upper], inf)

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

    def test_full_resolution_stage_does_not_integrate_a_tail(self):
        shader = (GBUFFER / "gbuffer_primary_atmosphere.comp.slang").read_text()
        self.assertIn("sampleAtmosphereColumn", shader)
        self.assertNotIn("integrateAtmosphere", shader)
        self.assertNotIn("CloudWeatherSampler", shader)
        layout = (GBUFFER / "primary_atmosphere_cache.h").read_text()
        self.assertEqual(int(re.search(r"COLUMN_ENDPOINTS (\d+)", layout)[1]), 4)
        self.assertEqual(int(re.search(r"COLUMN_ENDPOINT_WORDS (\d+)", layout)[1]), 8)


if __name__ == "__main__":
    unittest.main()
