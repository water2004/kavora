# SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
# SPDX-License-Identifier: MIT
"""Read back production density instead of matching its source spelling.

These tests require SlangPy and Vulkan (a software ICD is sufficient). They do
not measure game performance or validate the native presentation pipeline.
"""
import argparse
import json
from pathlib import Path

import numpy as np
import slangpy as spy


def constant_weather(probe, textures, coverage, stratus, cloud_type):
    from test_cloud_transport import weather_fixture, weather_resolutions
    low, high = weather_resolutions()
    profile = textures["Weather"].texture.to_numpy(layer=2, mip=3 + high.bit_length() - low.bit_length()).astype(np.float32) / 255
    layers = np.ones((3, *profile.shape), np.float32)
    layers[0] *= [coverage, cloud_type, stratus, 1]
    layers[1] *= [0, 0, 0.35, 1]
    layers[2] = profile
    texture = weather_fixture(probe.device, layers)
    sampler = probe.device.create_sampler(
        min_filter=spy.TextureFilteringMode.linear, mag_filter=spy.TextureFilteringMode.linear,
        address_u=spy.TextureAddressingMode.wrap, address_v=spy.TextureAddressingMode.wrap)
    return {**textures, "Weather": texture.create_view(sampler=sampler)}


def check_family_periodicity(probe, textures, clouds, period):
    rng = np.random.default_rng(64271)
    # Quantization isolates periodicity from rounding p +/- a large world period.
    points = np.column_stack((
        rng.integers(-512, 513, 2048) * 16.0,
        rng.integers(28, 100, 2048) * 16.0,
        rng.integers(-512, 513, 2048) * 16.0,
        np.zeros(2048))).astype(np.float32)
    report = []
    for cloud_type in (0.20, 0.272, 0.35, 0.40, 0.50, 0.60, 0.62, 0.80):
        inputs = constant_weather(probe, textures, 0.60, 0.60, cloud_type)
        for up in ([0, 1, 0], [0, 0, 1]):
            samples = points if up[1] else points[:, [0, 2, 1, 3]]
            parameters = {**clouds, "up": up}
            reference = probe.run("densities", samples, clouds=parameters, textures=inputs)[:, 0]
            assert np.any(reference > 0.1), ("periodicity must exercise occupied cloud", cloud_type)
            assert np.any(reference < 0.01), ("periodicity must include empty space", cloud_type)
            for horizontal in ((period, 0.0), (0.0, period), (-period, period)):
                wind = [horizontal[0], 0, horizontal[1]] if up[1] else [horizontal[0], horizontal[1], 0]
                shifted = probe.run("densities", samples,
                                    clouds={**parameters, "windOffset": wind}, textures=inputs)[:, 0]
                np.testing.assert_allclose(
                    shifted, reference, atol=5e-4, rtol=1e-3,
                    err_msg=f"density changes when wind wraps: type={cloud_type}, up={up}, wind={wind}")
                report.append({"type": cloud_type, "up": up, "wind": wind,
                               "maximumDensityError": float(np.max(np.abs(shifted - reference)))})
    print(f"PASS family periodicity: {len(report)} mixed-type/up/wind cases, "
          f"maximum density error {max(row['maximumDensityError'] for row in report):.7f}")
    return report


def check_erosion_seams(probe, textures, clouds):
    report = []
    # These are the former interpolated-cell boundaries, not cherry-picked
    # coordinates from the candidate's density or shadow error.
    for cloud_type in (0.27, 0.35, 0.40, 0.50, 0.60):
        t = np.clip((cloud_type - 0.20) / 0.42, 0, 1)
        former_period = (2.0 + 6.0 * (1.0 - t * t * (3.0 - 2.0 * t))) * clouds["shapeScale"]
        for coverage in (0.35, 0.50, 0.65, 0.80):
            inputs = constant_weather(probe, textures, coverage, coverage, cloud_type)
            points = np.array([[cell * former_period, height, across, 0]
                               for cell in (-8, -1, 0, 1, 8)
                               for height in (430, 470, 520, 600, 750, 900)
                               for across in (-4913, 171, 5239)], np.float32)
            for axis in (0, 2):
                samples = points if axis == 0 else points[:, [2, 1, 0, 3]]
                before, after = samples.copy(), samples.copy()
                before[:, axis] -= 0.015625
                after[:, axis] += 0.015625
                a = probe.run("densities", before, clouds=clouds, textures=inputs)[:, 0]
                b = probe.run("densities", after, clouds=clouds, textures=inputs)[:, 0]
                error = float(np.max(np.abs(b - a)))
                assert error < 0.01, ("erosion has a finite cell-boundary jump", cloud_type, coverage, axis, error)
                report.append({"type": cloud_type, "coverage": coverage, "axis": axis,
                               "maximumDensityDeltaAcross3cm": error})
    print(f"PASS erosion seams: {len(report)} type/coverage/axis cases, "
          f"maximum 3cm density delta {max(row['maximumDensityDeltaAcross3cm'] for row in report):.7f}")
    return report


def check_erosion_channels(probe, textures, clouds, period):
    rng = np.random.default_rng(20260619)
    floor = clouds["baseHeight"]
    points = rng.uniform([-period / 2, floor, -period / 2, 0],
                         [period / 2, floor + clouds["thickness"], period / 2, 0], (65536, 4)).astype(np.float32)
    density = probe.run("densities", points, clouds=clouds, textures=textures)[:, 0]
    edge = (density > 0.01) & (density < 0.8)
    assert edge.sum() > 100
    texture = textures["Detail"].texture
    original = texture.to_numpy(mip=0).copy()
    changed = {}
    try:
        for channel, name in enumerate(("wispyLow", "wispyHigh", "billowyLow", "billowyHigh")):
            flat = original.copy()
            flat[..., channel] = round(original[..., channel].mean())
            texture.copy_from_numpy(flat, mip=0)
            result = probe.run("densities", points[edge], clouds=clouds, textures=textures)[:, 0]
            changed[name] = int(np.count_nonzero(np.abs(density[edge] - result) > 0.001))
            assert changed[name] > 0.02 * edge.sum(), (name, "erosion band lost in production density")
    finally:
        texture.copy_from_numpy(original, mip=0)
    print("PASS individual erosion channels affect cloud edges:", changed)
    return changed


def constant_volume(probe, value):
    texture = probe.device.create_texture(
        type=spy.TextureType.texture_3d, format=spy.Format.rgba32_float,
        width=1, height=1, depth=1, mip_count=1, usage=spy.TextureUsage.shader_resource)
    texture.copy_from_numpy(np.asarray(value, np.float32).reshape(1, 1, 1, 4))
    sampler = probe.device.create_sampler(
        min_filter=spy.TextureFilteringMode.linear, mag_filter=spy.TextureFilteringMode.linear,
        address_u=spy.TextureAddressingMode.wrap, address_v=spy.TextureAddressingMode.wrap,
        address_w=spy.TextureAddressingMode.wrap)
    return texture.create_view(sampler=sampler)


def check_profile_compression(probe, textures, clouds):
    horizontal = np.arange(-3584.0, 3585.0, 1024.0)
    heights = np.linspace(clouds["baseHeight"] - 10,
                          clouds["baseHeight"] + clouds["thickness"] + 10, 128)
    points = np.array([[x, height, z, 0] for height in heights
                       for x in horizontal for z in horizontal], np.float32)
    values = {}
    for name, coverage, stratus in (("clear", 0, 0), ("convective", 0.78, 0),
                                    ("mixed", 0.78, 0.5), ("stratocumulus", 0.78, 1)):
        inputs = constant_weather(probe, textures, coverage, stratus, 0.45)
        values[name] = probe.run("densities", points, clouds=clouds, textures=inputs)[:, 0]
        assert np.isfinite(values[name]).all() and np.all((values[name] >= 0) & (values[name] <= 1))
    np.testing.assert_array_equal(values["clear"], 0)
    centroids = {}
    for name in ("convective", "mixed", "stratocumulus"):
        profile = values[name].reshape(len(heights), -1).mean(axis=1)
        assert np.max(profile) > 0.1, ("family has no substantial condensate", name)
        centroids[name] = float(np.dot(heights, profile) / profile.sum())
    assert centroids["stratocumulus"] < centroids["mixed"] < centroids["convective"], centroids
    print("PASS HanPi profile compression at fixed coverage:", centroids)
    return centroids


def check_constant_filtering(probe, textures, clouds):
    inputs = {**textures,
              "Base": constant_volume(probe, [0.82, 0, 0, 0]),
              "Detail": constant_volume(probe, [0.5, 0.5, 0.5, 1])}
    points = np.array([[8043, height, 1240, 0]
                       for height in np.linspace(390, 1910, 512)], np.float32)
    maximum_error = 0.0
    for cumulus, deck in ((0.45, 0.0), (0.55, 1.0), (0.45, 0.55)):
        constant = constant_weather(probe, inputs, cumulus, deck, 0.45)
        reference = probe.run("densities", points, clouds=clouds, textures=constant)[:, 0]
        assert np.any((reference > 0.01) & (reference < 0.99)), (
            "filtering must exercise partial density, not just empty/full plateaus", cumulus, deck)
        for footprint in (32, 64, 128, 256, 1024, 8192):
            samples = points.copy()
            samples[:, 3] = footprint
            filtered = probe.run("densities", samples, clouds=clouds, textures=constant)[:, 0]
            np.testing.assert_allclose(filtered, reference, atol=2e-6, rtol=2e-5,
                                       err_msg="footprint invented variation in a constant family mask")
            maximum_error = max(maximum_error, float(np.max(np.abs(filtered - reference))))
    print(f"PASS constant-mask filtering: 18 family/footprint cases, maximum density error {maximum_error:.7f}")
    return maximum_error


def main():
    from test_cloud_transport import OPTIONS, Probe, cloud_parameters, cloud_textures, cloud_weather_periods

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dump", type=Path)
    args = parser.parse_args()
    device = spy.Device(type=spy.DeviceType.vulkan, compiler_options=OPTIONS)
    probe = Probe(device)
    textures = cloud_textures(device, args.dump)
    clouds = cloud_parameters()
    period = max(cloud_weather_periods()) * clouds["shapeScale"]
    result = {"scope": "Production density readback on Vulkan; not native game validation.",
              "periodicity": check_family_periodicity(probe, textures, clouds, period),
              "erosionSeams": check_erosion_seams(probe, textures, clouds),
              "erosionChannels": check_erosion_channels(probe, textures, clouds, period),
              "familyCentroidsMeters": check_profile_compression(probe, textures, clouds),
              "constantFilterMaximumError": check_constant_filtering(probe, textures, clouds)}
    if args.dump:
        args.dump.mkdir(parents=True, exist_ok=True)
        (args.dump / "cloud-morphology-contracts.json").write_text(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
