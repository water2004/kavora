# SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors
# SPDX-License-Identifier: MIT
"""Verify the shared quadratic cloud-light integral and finite visibility rays."""
import numpy as np
import slangpy as spy

from test_cloud_transport import OPTIONS, Probe, cloud_parameters, uniform_cloud_textures


def low_tau(thickness, start, length, steps, extinction):
    centers = (np.arange(steps) + 0.5) / steps
    altitude = (start + length * centers**2) / thickness
    t = np.clip((altitude - 0.1) / 0.4, 0, 1)
    density = 0.1 + 0.9 * t * t * (3 - 2 * t)
    return length * extinction * np.dot(density, 2 * centers / steps)


def main():
    device = spy.Device(type=spy.DeviceType.vulkan, compiler_options=OPTIONS)
    probe = Probe(device)
    textures = uniform_cloud_textures(device)
    args = cloud_parameters(baseHeight=400, thickness=1500, extinction=0.002,
                            highBaseHeight=2600, highThickness=150, highExtinction=0.004, wetness=1)
    rays = [[0, 0, 0, limit] for limit in (390, 550, 1900, 2680, 4000)]
    rays.extend([[0, 1600, 0, 180], [0, 2670, 0, 20], [0, 2850, 0, 2000]])
    directions = [[0, 1, 0, 0]] * len(rays)
    cases = [(0, 0, 0), (0, 150, 0), (0, 1500, 0), (0, 1500, 80),
             (0, 1500, 150), (1200, 180, 0), (0, 0, 20), (0, 0, 0)]
    for budget in (1, 3, 6, 16):
        args["lightSteps"] = budget
        expected = np.array([[low_tau(1500, start, low, min(budget, 6), .002), high * .004]
                             for start, low, high in cases])
        actual = probe.run("shortLightDepth", rays, directions, args, textures)
        np.testing.assert_allclose(actual[:, :2], expected, atol=2e-4, rtol=0)
        visibility = probe.run("visibility", rays, directions, args, textures)[:, 0]
        np.testing.assert_allclose(visibility, np.exp(-expected.sum(axis=1)), atol=2e-4, rtol=0)

    # A finite grazing ray clips the homogeneous upper shell; an unbounded
    # ray covers only two deck thicknesses at Revelation's grazing-angle cap.
    args["extinction"] = 0
    grazing = [[0, 2670, 0, distance] for distance in (0, 17, 299, 300, 1e6)]
    actual = probe.run("shortLightDepth", grazing, [[1, 0, 0, 0]] * len(grazing), args, textures)
    np.testing.assert_array_equal(actual[:, 0], 0)
    np.testing.assert_allclose(actual[:, 1], np.array([0, 17, 299, 300, 300]) * .004, atol=2e-5)
    disabled = probe.run("visibility", rays, directions, args | {"enable": 0}, textures)
    np.testing.assert_array_equal(disabled[:, 0], 1)
    print("PASS short cloud-light integration: layer depth, finite visibility, budgets and grazing cap")


if __name__ == "__main__":
    main()
