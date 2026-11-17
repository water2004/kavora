# SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
# SPDX-License-Identifier: MIT
"""CPU regression for the celestial-disk fast path in air_medium.slang.

The reference evaluates the production four-node Gauss-Legendre disk
quadrature, replacing its LUT with an independently integrated spherical
Rayleigh/Mie atmosphere. This bounds the approximation; it is not a GPU
timing or full-game image claim.
"""
import math
import unittest

R = 6371.0
TOP = 100.0
N = 256
NODES = (-0.861136312, -0.339981044, 0.339981044, 0.861136312)
WEIGHTS = (0.347854845, 0.652145155, 0.652145155, 0.347854845)


def transmittance(height, mu):
    radius = R + height
    disc = (radius * mu) ** 2 + (R + TOP) ** 2 - radius ** 2
    if disc < 0:
        return 0.0
    distance = -radius * mu + math.sqrt(disc)
    step = distance / N
    total = 0.0
    for i in range(N + 1):
        s = i * step
        altitude = math.sqrt(radius * radius + s * s + 2.0 * radius * mu * s) - R
        density = math.exp(-altitude / 8.0) / 8.0 + 0.12 * math.exp(-altitude / 1.2) / 1.2
        total += density * (1 if i in (0, N) else 4 if i & 1 else 2)
    return math.exp(-total * step / 3.0)


def disk_four_point(height, mu, alpha):
    extent = math.sin(alpha) * math.sqrt(max(0.0, 1.0 - mu * mu))
    lower = -math.sqrt(max(0.0, height * (2.0 * R + height))) / (R + height)
    first = max(-1.0, min(1.0, (lower - mu) / max(extent, 1e-6)))
    visible = (math.acos(first) - first * math.sqrt(max(0.0, 1.0 - first * first))) / math.pi
    if visible <= 0:
        return 0.0
    centre = 0.5 * (1.0 + first)
    radius = 0.5 * (1.0 - first)
    weighted = normalization = 0.0
    for node, weight in zip(NODES, WEIGHTS):
        chord = centre + radius * node
        w = weight * math.sqrt(max(0.0, 1.0 - chord * chord))
        weighted += w * transmittance(height, mu + extent * chord)
        normalization += w
    return weighted * visible / max(normalization, 1e-8)


def fast_path_applies(height, mu, alpha):
    extent = math.sin(alpha) * math.sqrt(max(0.0, 1.0 - mu * mu))
    lower = -math.sqrt(max(0.0, height * (2.0 * R + height))) / (R + height)
    return alpha <= 0.01 and mu >= 0.45 and mu - extent > lower


def phase_terms(direction, light_directions, anisotropy):
    rayleigh = []
    mie = []
    g = anisotropy
    for light in light_directions:
        cosine = sum(a * b for a, b in zip(direction, light))
        cosine_squared = cosine * cosine
        rayleigh.append(3.0 / (16.0 * math.pi) * (1.0 + cosine_squared))
        mie.append(3.0 / (8.0 * math.pi) * (1.0 - g * g) * (1.0 + cosine_squared)
                   / ((2.0 + g * g) * (1.0 + g * g - 2.0 * g * cosine) ** 1.5))
    return tuple(rayleigh), tuple(mie)


class AirDiskApproximationTests(unittest.TestCase):
    def test_fast_path_error_is_bounded_for_celestial_disks(self):
        maximum_absolute = 0.0
        maximum_relative = 0.0
        tested = 0
        for height in (0.0, 1.0, 5.0, 10.0, 20.0, 40.0):
            for mu_index in range(81):
                mu = 0.45 + 0.55 * mu_index / 80.0
                for alpha in (0.00465, 0.009, 0.01):
                    if not fast_path_applies(height, mu, alpha):
                        continue
                    reference = disk_four_point(height, mu, alpha)
                    candidate = transmittance(height, mu)
                    absolute = abs(candidate - reference)
                    relative = absolute / max(reference, 1e-6)
                    maximum_absolute = max(maximum_absolute, absolute)
                    maximum_relative = max(maximum_relative, relative)
                    tested += 1
        self.assertGreater(tested, 500)
        self.assertLess(maximum_absolute, 5e-5)
        self.assertLess(maximum_relative, 1e-4)
        print(f"CPU spherical-atmosphere disk check: {tested} cases; "
              f"max abs={maximum_absolute:.7g}, max rel={maximum_relative:.7g}")

    def test_horizon_and_partial_disk_keep_four_point_rule(self):
        for height in (0.0, 1.0, 10.0, 40.0):
            lower = -math.sqrt(max(0.0, height * (2.0 * R + height))) / (R + height)
            for delta in (-0.02, -0.005, 0.0, 0.005, 0.02):
                self.assertFalse(fast_path_applies(height, lower + delta, 0.00465))

    def test_large_emitters_keep_four_point_rule(self):
        for alpha in (0.0101, 0.02, 0.1):
            self.assertFalse(fast_path_applies(5.0, 0.8, alpha))

    def test_ray_phase_is_invariant_across_sample_positions(self):
        direction = (0.31, -0.42, 0.851)
        lights = ((0.1, 0.2, 0.97), (-0.4, 0.8, 0.44))
        hoisted = phase_terms(direction, lights, 0.76)
        for _position in ((0, 0, 0), (0, 8, 0), (1200, 30, -800), (-40000, 20, 70000)):
            self.assertEqual(phase_terms(direction, lights, 0.76), hoisted)


if __name__ == "__main__":
    unittest.main(verbosity=2)
