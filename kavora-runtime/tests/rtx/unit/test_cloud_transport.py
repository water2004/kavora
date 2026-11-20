# SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
# SPDX-License-Identifier: MIT
"""Numerical readback checks for production cloud geometry, density and transport.

Install numpy and slangpy. The Vulkan device may use a software ICD. These
checks do not measure game performance or validate presentation.
"""
import argparse
import json
from pathlib import Path

import numpy as np
import slangpy as spy

from cloud_lighting_preview import build_lighting_cache


HERE = Path(__file__).resolve().parent
SHADERS = HERE.parents[2] / "src/dxvk/shaders"
OPTIONS = {"include_paths": [str(HERE), str(SHADERS)], "defines": {"__SLANG__": "1"}}


def cloud_parameters(**changes):
    # These are fixed numerical fixtures, including the 400 m shell used by the
    # geometry probes below. The visual preview passes current native defaults.
    result = {
        "up": [0, 1, 0], "enable": 1, "windOffset": [0, 0, 0],
        "baseHeight": 400.0, "thickness": 1500.0, "shapeScale": 1024.0,
        "extinction": 0.06, "coverage": 0.45, "maxDistance": 12000.0,
        "albedo": 1.0, "steps": 160, "highSteps": 48, "lightSteps": 16,
        "anisotropy": 0.65, "multipleScattering": 0.8, "weather": 0.0,
        "thunder": 0.0, "groundReference": [0, 0, 0], "planetRadius": 6360000.0,
        "wetness": 0.0, "meterToWorld": 1.0, "highMapOrigin": [0., 0.], "highLookupOrigin": [0., 0.],
        "lightMaxDistance": 60000.0, "previousWindDelta": [0, 0, 0],
        "lightingNearDistance": 24000.0,
        "viewAngularFootprint": 0.0, "highBaseHeight": 6000.0,
        "highThickness": 900.0, "highExtinction": 0.0, "highCoverage": 0.4,
        "highMaxDistance": 60000.0, "groundAlbedo": 0.18,
        "lights": [{"directionToLight": [1, 0, 0], "irradiance": [1, 1, 1]},
                   {"directionToLight": [-1, 0, 0], "irradiance": [0, 0, 0]}],
    }
    result.update(changes)
    return result


def cloud_weather_periods():
    # Nominal low weather period and period of the fine cirrus map.
    return 64, 20



class Probe:
    def __init__(self, device):
        self.device = device
        self.kernels = {}
        self.lighting_key = None
        self.lighting_bindings = None
        self.lighting_resources = None
        self.vertical_depth = device.create_texture(type=spy.TextureType.texture_3d, format=spy.Format.rg32_float,
            width=1, height=1, depth=1, usage=spy.TextureUsage.shader_resource,
            data=np.zeros((1, 1, 1, 2), np.float32))
        self.vertical_depth_view = self.vertical_depth.create_view(sampler=device.create_sampler())

    def run(self, entry, inputs, directions=None, clouds=None, textures=None, output_records=1):
        data = np.ascontiguousarray(inputs, dtype=np.float32).reshape(-1, 4)
        if entry not in self.kernels:
            program = self.device.load_program("cloud_transport_contracts.comp.slang", [entry])
            self.kernels[entry] = self.device.create_compute_kernel(program)
        output = self.device.create_buffer(element_count=len(data) * output_records, struct_size=16,
                                           usage=spy.BufferUsage.unordered_access)
        bindings = {
            "Inputs": self.device.create_buffer(data=data, usage=spy.BufferUsage.shader_resource),
            "Results": output, "Count": len(data), "VerticalDepth": self.vertical_depth_view.descriptor_handle_combined,
        }
        if directions is not None:
            bindings["Directions"] = self.device.create_buffer(
                data=np.ascontiguousarray(directions, dtype=np.float32), usage=spy.BufferUsage.shader_resource)
        if clouds is not None:
            bindings["Clouds"] = clouds
        if textures is not None:
            bindings.update({key: value.descriptor_handle_combined for key, value in textures.items()})
        if entry in ("visibility", "lightDepthParts"):
            key = (json.dumps(clouds, sort_keys=True, default=lambda value: np.asarray(value).tolist()),
                   tuple((name, id(value)) for name, value in textures.items()))
            if key != self.lighting_key:
                self.lighting_bindings, self.lighting_resources, _ = build_lighting_cache(
                    self.device, clouds, textures, SHADERS)
                self.lighting_key = key
            bindings.update(self.lighting_bindings)
        self.kernels[entry].dispatch(thread_count=[len(data), 1, 1], vars=bindings)
        return output.to_numpy().view(np.float32).reshape(-1, 4)


def check_column_history(probe):
    old = [[131072, 0.5, 0, 0]] * 7
    current = [[depth, parallax, weight, 0] for depth, parallax, weight in (
        (131072, 1, 1), (131072, 1, 1), (131072, 1, .25), (-131072, 0, 1),
        (65536, 2, 1), (65536, 0, 1), (65536, 1, 1))]
    result = probe.run("columnHistory", old, current)
    expected = np.outer([1, 1, .25, 0, 0, 1, .5], [2, 4, 8, 1])
    np.testing.assert_allclose(result, expected, atol=1e-5)
    print("PASS cloud observation: bilinear support, shell sides and parallax rejection")


def check_air_skylight(probe):
    column = probe.device.create_texture(type=spy.TextureType.texture_3d, format=spy.Format.rg32_float,
        width=1, height=1, depth=1, usage=spy.TextureUsage.shader_resource,
        data=np.array([[[[2, 6]]]], np.float32))
    view = column.create_view(sampler=probe.device.create_sampler())
    clouds = cloud_parameters(groundAlbedo=0)
    origins = [[0, 0, 0, 0], [0, 1100, 0, 0], [0, 3000, 0, 0], [24000, 0, 0, 0]]
    spectrum = np.array([.5, 1, 2, 0])
    actual = probe.run("airSkylight", origins, [spectrum] * 4, clouds, {"VerticalDepth": view})
    d = 1 / np.sqrt(3)
    reduced_depth = 1 - .5 * (clouds["anisotropy"] - .25)
    below = np.array([0, 6, 8, 0]) * reduced_depth
    weights = (d + below) / (2 * d + 8 * reduced_depth)
    np.testing.assert_allclose(actual, weights[:, None] * spectrum, atol=2e-5)
    # In the gap between two equal columns the upper and lower optical
    # depths match. A perfectly reflecting floor returns the full field.
    gap = probe.run("airSkylight", [origins[2]], [spectrum],
                    cloud_parameters(groundAlbedo=0, highExtinction=.01), {"VerticalDepth": view})
    np.testing.assert_allclose(gap[0], .5 * spectrum, atol=2e-5)
    bright = probe.run("airSkylight", origins, [spectrum] * 4,
                       cloud_parameters(groundAlbedo=1), {"VerticalDepth": view})
    np.testing.assert_allclose(bright, np.broadcast_to(spectrum, bright.shape), atol=2e-5)
    print("PASS air receives transmitted, interior and reflected skylight on the correct sides of a cloud")


def check_weather_column_coordinates(probe):
    uv = [[.013, .983, 500., 0], [.957, .12, 6200., 1], [.49, .52, 1100., 0]]
    for z_up in (False, True):
        up = [0, 0, 1] if z_up else [0, 1, 0]
        clouds = cloud_parameters(up=up, groundReference=[8591., 0., 0.], windOffset=[-60300., 0., 755.])
        coords = probe.run("columnCoordinates", uv, [[0., 0., 0., 0.]] * len(uv), clouds)
        np.testing.assert_allclose(coords[:, :2], np.asarray(uv)[:, :2], atol=1e-6)
        np.testing.assert_allclose(coords[:, 2], np.asarray(uv)[:, 2], atol=.005)
    print("PASS local light position and lookup round trip for both up axes")


def check_column_noise(probe, textures):
    rng = np.random.default_rng(225)
    count = 512
    origins = np.zeros((count, 4), dtype=np.float32)
    origins[:, [0, 2]] = rng.uniform(-30000, 30000, (count, 2))
    directions = rng.normal(size=(count, 3))
    directions[:, 1] = rng.uniform(.2, 1, count)
    directions /= np.linalg.norm(directions, axis=1)[:, None]
    args = cloud_parameters(steps=24, viewAngularFootprint=.002, maxDistance=24000.)
    samples = probe.run("stationaryColumnNoise", origins, np.column_stack([directions, np.zeros(count)]),
                        args, textures, output_records=2)
    data = samples[::2]
    edges = data[(data[:, 0] > .05) & (data[:, 0] < .95)]
    assert len(edges) >= 16
    before, after = np.sqrt(edges[:, 2:].mean(axis=0))
    mean_shift = abs((edges[:, 0] - edges[:, 1]).mean())
    assert 0 < after < before and mean_shift < .02
    print(f"PASS {len(edges)} partly cloudy fixed rays at 24 steps: temporal opacity RMS "
          f"{before:.5f} -> {after:.5f}, mean opacity shift {mean_shift:.5f}")


def check_homogeneous(probe):
    samples = np.array([(sigma, 100.0, 0.93, steps)
                        for sigma in (0.0, 1e-4, 0.01, 0.2)
                        for steps in (1, 7, 64, 256)], dtype=np.float32)
    actual = probe.run("homogeneous", samples)
    transmission = np.exp(-samples[:, 0].astype(np.float64) * samples[:, 1])
    expected = np.column_stack([(1 - transmission)[:, None] * samples[:, 2, None] * [1, 2, 4],
                                transmission])
    np.testing.assert_allclose(actual, expected, atol=3e-5, rtol=5e-4)
    print("PASS homogeneous Beer integration: 16 density/subdivision cases")




def shell_interval(clouds, origin, direction, limit):
    radius = clouds["planetRadius"]
    point = np.asarray(origin, np.float64) + [0, radius, 0]
    direction = np.asarray(direction, np.float64)

    def roots(altitude):
        b = point @ direction
        c = point @ point - (radius + altitude) ** 2
        disc = b * b - c
        return (-1, -1) if disc < 0 else (-b - np.sqrt(disc), -b + np.sqrt(disc))

    outer = roots(clouds["baseHeight"] + clouds["thickness"])
    begin, end = max(0, outer[0]), min(limit, clouds["maxDistance"], outer[1])
    inner = roots(clouds["baseHeight"])
    if inner[1] > begin:
        if inner[0] <= begin:
            begin = inner[1]
        else:
            end = min(end, inner[0])
    return [begin, end] if end > begin else [0, 0]


def check_intervals(probe):
    clouds = cloud_parameters(maxDistance=60000.0)
    origins, directions, expected = [], [], []
    for height in (-50, 0, 399, 400, 401, 900, 1899, 1900, 1901, 3000):
        for angle in (-90, -10, -1, -0.01, 0, 0.01, 1, 10, 90):
            radians = np.radians(angle)
            origin = [250, height, -170]
            direction = [np.cos(radians), np.sin(radians), 0]
            origins.append([*origin, 60000])
            directions.append([*direction, 0])
            expected.append(shell_interval(clouds, origin, direction, 60000))
    actual = probe.run("intervals", origins, directions, clouds)[:, :2]
    np.testing.assert_allclose(actual, expected, atol=0.3, rtol=4e-5)
    assert np.isfinite(actual).all() and np.all(actual[:, 1] >= actual[:, 0])
    print("PASS cloud-shell intersections: 90 above/inside/below and grazing rays")


def check_layer_intervals(probe):
    clouds = cloud_parameters(highExtinction=0.01)
    origins, directions, expected = [], [], []
    for layer, base, thickness in ((0, clouds["baseHeight"], clouds["thickness"]),
                                   (1, clouds["highBaseHeight"], clouds["highThickness"])):
        for height in (0, base + 0.5 * thickness, 10000):
            for angle in (-10, -3, -1, 0, 1, 10):
                ray = np.array([np.cos(np.radians(angle)), np.sin(np.radians(angle)), 0])
                origin = np.array([0, height, 0])
                center_relative = origin + [0, clouds["planetRadius"], 0]
                b = center_relative @ ray
                roots = []
                for altitude in (base, base + thickness):
                    c = center_relative @ center_relative - (clouds["planetRadius"] + altitude) ** 2
                    d = b * b - c
                    if d >= 0:
                        roots.extend([-b - np.sqrt(d), -b + np.sqrt(d)])
                endpoints = sorted(set([0.0, 1e6, *[t for t in roots if 0 < t < 1e6]]))
                spans = []
                for begin, end in zip(endpoints[:-1], endpoints[1:]):
                    radius = np.linalg.norm(center_relative + ray * (begin + end) * 0.5)
                    if clouds["planetRadius"] + base < radius < clouds["planetRadius"] + base + thickness:
                        spans.extend([begin, end])
                expected.append(spans + [0.0] * (4 - len(spans)))
                origins.append([*origin, 1e6])
                directions.append([*ray, layer])
    actual = probe.run("layerIntervals", origins, directions, clouds)
    np.testing.assert_allclose(actual, expected, atol=1.0, rtol=2e-5)
    assert np.count_nonzero(actual[:, 3]) > 0, "grazing rays must cover both shell crossings"
    disabled = probe.run("layerIntervals", origins, directions, cloud_parameters(enable=0))
    np.testing.assert_array_equal(disabled, 0)
    print("PASS layered shells: both crossings, high layer and disabled transport")


def check_light_profiles(probe):
    profile = np.array([[0, 0, 0, 0], [0.12, 0.02, 0.005, 400],
                        [0.8, 0.2, 0.05, 1900], [1.0, 0.7, 0.4, 6500]])
    heights = np.array([-100, 0, 100, 400, 700, 1900, 3000, 6500, 8000])
    inputs = np.column_stack([heights, np.zeros((len(heights), 3))])
    light = {"directionToLight": [1, 0, 0], "irradiance": [0.04, 0.06, 0.09],
             "hasRadianceProfile": 1, "radianceProfile": profile.tolist()}
    actual = probe.run("lightProfiles", inputs, clouds=cloud_parameters(lights=[light, light]))[:, :3]
    expected = np.column_stack([np.interp(heights, profile[:, 3], profile[:, channel]) for channel in range(3)])
    np.testing.assert_allclose(actual, expected, atol=2e-6, rtol=0)
    light["hasRadianceProfile"] = 0
    fallback = probe.run("lightProfiles", inputs, clouds=cloud_parameters(lights=[light, light]))[:, :3]
    np.testing.assert_allclose(fallback, np.tile(light["irradiance"], (len(heights), 1)), atol=2e-6, rtol=0)
    print("PASS altitude irradiance: profile boundaries, ground darkness and legacy fallback")


CLOUD_ASSETS = SHADERS.parent / "rtx_render/clouds"
TEXTURE_FILES = {"Base": "revelation_body.bin", "Detail": "revelation_curl.bin", "Weather": "revelation_maps.bin"}


def weather_resolutions():
    return 1024, 1024


def weather_fixture(device, layers):
    layers = np.asarray(layers, np.float32)
    n = layers.shape[1]
    texture = device.create_texture(type=spy.TextureType.texture_2d_array, format=spy.Format.rgba32_float,
                                    width=n, height=n, array_length=3, usage=spy.TextureUsage.shader_resource)
    for layer in range(3):
        texture.copy_from_numpy(np.ascontiguousarray(layers[layer]), layer=layer)
    return texture


def cloud_textures(device, dump=None):
    sampler = device.create_sampler(min_filter=spy.TextureFilteringMode.linear,
                                    mag_filter=spy.TextureFilteringMode.linear, mip_filter=spy.TextureFilteringMode.point,
                                    address_u=spy.TextureAddressingMode.wrap, address_v=spy.TextureAddressingMode.wrap,
                                    address_w=spy.TextureAddressingMode.wrap)
    textures = {}
    for name, side, channels, fmt in (("Base", 128, 1, spy.Format.r8_unorm),
                                      ("Detail", 64, 4, spy.Format.rgba8_unorm)):
        data = np.fromfile(CLOUD_ASSETS / TEXTURE_FILES[name], dtype=np.uint8)
        tex = device.create_texture(type=spy.TextureType.texture_3d, format=fmt,
                                    width=side, height=side, depth=side, usage=spy.TextureUsage.shader_resource)
        shape = (side, side, side) if channels == 1 else (side, side, side, channels)
        tex.copy_from_numpy(data.reshape(shape))
        textures[name] = tex.create_view(sampler=sampler)

    weather = device.create_texture(type=spy.TextureType.texture_2d_array, format=spy.Format.rgba8_unorm,
                                    width=1024, height=1024, array_length=2, mip_count=6,
                                    usage=spy.TextureUsage.shader_resource)
    raw = np.fromfile(CLOUD_ASSETS / TEXTURE_FILES["Weather"], dtype=np.uint8)
    offset = 0
    for layer, mip in ((0, 0), (0, 2), (0, 5), (0, 1), (1, 1), (1, 0)):
        side = 1024 >> mip
        size = side * side * 4
        weather.copy_from_numpy(raw[offset:offset + size].reshape(side, side, 4).copy(), layer=layer, mip=mip)
        offset += size
    assert offset == len(raw)
    textures["Weather"] = weather.create_view(sampler=sampler)
    return textures


def light_parameters(direction, **changes):
    return cloud_parameters(lights=[{"directionToLight": list(direction), "irradiance": [1, 1, 1]},
                                   {"directionToLight": list(-np.asarray(direction)), "irradiance": [0, 0, 0]}],
                            **changes)


def check_visibility(probe, textures, dump):
    positions = [[x, 540, z] for x in (-1200, -400, 400, 1200) for z in (-700, 0, 700)]
    angles = (0.0, 0.001, 1.0, 1.001, 3.0, 3.001, 10.0, 10.001, 45.0, 45.001, 90.0)
    actual, reference = [], []
    for angle in angles:
        radians = np.radians(angle)
        direction = [np.cos(radians), np.sin(radians), 0]
        clouds = light_parameters(direction)
        origins = [[*position, 60000] for position in positions]
        directions = [[*direction, 0]] * len(positions)
        actual.append(probe.run("visibility", origins, directions, clouds, textures)[:, 0])
        reference.append(probe.run("visibilityReference", origins, directions, clouds, textures)[:, 0])
    values = np.stack(actual, axis=1)
    reference = np.stack(reference, axis=1)
    assert np.isfinite(values).all() and np.all((0 <= values) & (values <= 1))
    differences = np.abs(values[:, 0:10:2] - values[:, 1:10:2])
    mean_error = np.mean(np.abs(values - reference))
    maximum_error = np.max(np.abs(values - reference))
    partial = int(np.count_nonzero((reference > 0.01) & (reference < 0.99)))
    assert partial > 0, "the reference set must contain partial cloud shadows"
    if dump:
        dump.mkdir(parents=True, exist_ok=True)
        np.savez(dump / "cloud-visibility.npz", actual=values, reference=reference, angles=angles,
                 positions=positions, referenceStepMeters=2.0)
    assert np.max(differences) < 0.025, ("low-sun temporal discontinuity", np.max(differences))
    # Keep an independent small-step comparison: finite cache footprints are an
    # approximation, not permission to lose a distant, optically thick blocker.
    assert mean_error < 0.05, ("cached shadows diverge from the fine reference", mean_error)
    assert maximum_error < 0.45, ("cached shadows miss a significant cloud cell", maximum_error)
    print(f"PASS cloud visibility: mean/max 2 m reference error {mean_error:.5f}/{maximum_error:.5f}, "
          f"angular delta {np.max(differences):.6f}, partial shadows {partial}/{reference.size}")


def check_cloud_boundary_visibility(probe, textures, dump):
    """Select actual cloud boundaries by density, independently of shadow error."""
    clouds = cloud_parameters(baseHeight=800.0, extinction=0.03, maxDistance=24000.0,
                              highMaxDistance=24000.0, groundReference=[8043.0, 0.0, 1240.0])
    axis = np.arange(-6144.0, 6145.0, 256.0)
    x, z = np.meshgrid(axis + 8043.0, axis + 1240.0)
    points = np.concatenate([np.column_stack([x.ravel(), np.full(x.size, height), z.ravel(), np.zeros(x.size)])
                             for height in (860, 1000, 1200, 1450, 1750, 2050)])
    densities = probe.run("densities", points, clouds=clouds, textures=textures)[:, 0]
    candidates = np.flatnonzero((densities >= 0.05) & (densities <= 0.85))
    assert len(candidates) >= 24, "the fixed user region must exercise actual cloud boundaries"
    selected = candidates[np.linspace(0, len(candidates) - 1, 24, dtype=int)]
    positions = points[selected, :3]
    rays = np.column_stack([positions, np.full(len(positions), 60000.0)])
    angles = (0.2, 0.201, 3.0, 3.001, 40.0, 40.001, 90.0)
    azimuth = np.radians(30.0)
    for distance in (24000.0, 96000.0):
        actual, reference = [], []
        for angle in angles:
            elevation = np.radians(angle)
            direction = [np.sin(azimuth) * np.cos(elevation), np.sin(elevation),
                         np.cos(azimuth) * np.cos(elevation)]
            parameters = clouds | {"maxDistance": distance, "highMaxDistance": distance,
                                   "lights": light_parameters(direction)["lights"]}
            directions = [[*direction, 0]] * len(positions)
            actual.append(probe.run("visibility", rays, directions, parameters, textures)[:, 0])
            reference.append(probe.run("visibilityReference", rays, directions, parameters, textures)[:, 0])
        actual = np.stack(actual, axis=1)
        reference = np.stack(reference, axis=1)
        error = np.abs(actual - reference)
        partial = int(np.count_nonzero((reference > 0.01) & (reference < 0.99)))
        assert partial >= 12, "boundary validation must include translucent lit/shadow transitions"
        if dump:
            suffix = "" if distance == 24000 else "-96000"
            np.savez(dump / f"cloud-boundary-visibility{suffix}.npz", actual=actual, reference=reference,
                     angles=angles, positions=positions, selectedDensities=densities[selected],
                     candidateCount=len(candidates), referenceStepMeters=2.0)
        assert np.mean(error) < 0.05, ("cloud-boundary mean shadow error", distance, np.mean(error))
        assert np.max(error) < 0.45, ("cloud-boundary maximum shadow error", distance, np.max(error))
        angular_delta = np.max(np.abs(actual[:, :6:2] - actual[:, 1:6:2]))
        assert angular_delta < 0.025, ("cloud-boundary solar-angle discontinuity", distance, angular_delta)
        print(f"PASS user-region cloud boundaries at {distance:.0f}m: {len(candidates)} density candidates, "
              f"partial shadows {partial}/{reference.size}, mean/max 2 m error "
              f"{np.mean(error):.5f}/{np.max(error):.5f}, angular delta {angular_delta:.6f}")
    return positions


def check_cloud_cache_transition(probe, textures, positions, dump):
    clouds = cloud_parameters(baseHeight=800.0, extinction=0.03, maxDistance=96000.0,
                              highMaxDistance=96000.0)
    rays = np.column_stack([positions, np.full(len(positions), 60000.0)])
    reports = []
    for angle in (0.2, 40.0):
        elevation, azimuth = np.radians([angle, 30.0])
        direction = [np.sin(azimuth) * np.cos(elevation), np.sin(elevation),
                     np.cos(azimuth) * np.cos(elevation)]
        parameters = clouds | {"lights": light_parameters(direction)["lights"]}
        directions = [[*direction, 0]] * len(positions)
        for edge in (18000.0, 24000.0):
            actual, reference = [], []
            for delta in (-1.0, 0.0, 1.0):
                anchor = [float(positions[0, 0] - edge + delta), 0.0, 1240.0]
                args = parameters | {"groundReference": anchor}
                actual.append(probe.run("visibility", rays, directions, args, textures)[:, 0])
                reference.append(probe.run("visibilityReference", rays, directions, args, textures)[:, 0])
            actual = np.stack(actual, axis=1)
            reference = np.stack(reference, axis=1)
            change_error = np.max(np.abs(np.diff(actual, axis=1) - np.diff(reference, axis=1)))
            assert change_error < 0.025, ("cascade transition jumps", angle, edge, change_error)
            if dump:
                np.savez(dump / f"cloud-cache-transition-{angle}-{edge:.0f}.npz", actual=actual,
                         reference=reference, positions=positions, anchorOffsets=[-1, 0, 1])
            reports.append(float(change_error))
    # A globally empty field must not sample padding between packed cascades.
    # Coverage is a regional bias, so explicitly provide zero weather moments.
    device = probe.device
    weather = weather_fixture(device, np.zeros((3, 1, 1, 4), np.float32))
    sampler = device.create_sampler(min_filter=spy.TextureFilteringMode.linear,
                                    mag_filter=spy.TextureFilteringMode.linear,
                                    address_u=spy.TextureAddressingMode.wrap,
                                    address_v=spy.TextureAddressingMode.wrap)
    empty_textures = textures | {"Weather": weather.create_view(sampler=sampler)}
    empty_args = clouds | {"groundReference": [8043.0, 0.0, 1240.0],
                           "lights": light_parameters([0, 0, 1])["lights"]}
    empty_rays = [[8043.0 + d, 1200, 1240, 60000] for d in
                  (0, 17999, 18000, 18001, 23999, 24000, 24001, 60000, 96001)]
    empty = probe.run("visibility", empty_rays, [[0, 0, 1, 0]] * len(empty_rays),
                      empty_args, empty_textures)[:, 0]
    np.testing.assert_array_equal(empty, np.ones_like(empty))
    print(f"PASS cloud cache cascades: 1m transition change error {max(reports):.6f}, "
          f"{len(empty)} empty-field rays including the outside-domain fallback")


def check_light_support(probe, textures):
    angle = np.radians(10)
    direction = [np.cos(angle), np.sin(angle), 0]
    clouds = light_parameters(direction)
    local_ranges = np.array([-100, 0, 13.5, 32, 64, 127.5, 513.25, 943, 1024, 2048, 6000])
    rays = np.tile([-400, 540, -700, 5000], (len(local_ranges), 1))
    directions = np.column_stack([np.tile(direction, (len(local_ranges), 1)), local_ranges])
    parts = probe.run("lightDepthParts", rays, directions, clouds, textures)[:, :3]
    assert np.isfinite(parts).all() and np.all(parts >= 0)
    assert np.all(np.diff(parts[:, 0]) >= -2e-5), "local optical depth must be monotone"
    np.testing.assert_allclose(parts[:, 2], parts[0, 2], atol=1e-4, rtol=2e-6,
                               err_msg="changing local range must not resample the light ray")
    np.testing.assert_array_equal(parts[:2, 0], 0)
    assert parts[-1, 1] == 0
    # Domains outside the cache remain bounded, including tiny user shape scales.
    for quality in (4, 8, 12, 16):
        for scale in (1, 64, 1024):
            parameters = light_parameters(direction, shapeScale=scale, lightSteps=quality)
            result = probe.run("lightDepthParts", [[100000, 540, -700, 60000]], [[*direction, 0]],
                               parameters, textures)[0]
            assert np.isfinite(result).all() and np.all(result[:2] >= 0)
            assert result[3] == 2 * quality, "uncached light rays must retain a fixed query budget"
    print("PASS shared light prefixes: local/external partition and bounded outside-domain rays")







def check_high_column_transmission(probe, textures):
    positions = [(x, z) for x in (-6144.0, -2048.0, 2048.0, 6144.0)
                 for z in (-6144.0, -2048.0, 2048.0, 6144.0)]
    positions.append((8183.0, 1171.0))
    center_height = 6400.0
    actual, expected = [], []
    for thickness in (100.0, 200.0, 800.0):
        clouds = cloud_parameters(extinction=0.0, highBaseHeight=center_height - 0.5 * thickness,
                                  highThickness=thickness, highExtinction=1.2 / thickness,
                                  highCoverage=0.5, highMaxDistance=96000.0,
                                  highMapOrigin=[51428, 663955])
        distance = center_height + 0.5 * thickness + 100.0
        for x, z in positions:
            # Put the planet tangent beneath this column so radial altitude equals world Y.
            column = clouds | {"groundReference": [x, 0.0, z]}
            result = probe.run("viewTransmission", [[x, 0.0, z, distance]], [[0.0, 1.0, 0.0, 0.0]],
                               column, textures)[0]
            # Integrate the resolved coverage- and type-dependent vertical band.
            unit_height = (np.arange(512) + 0.5) / 512 - 0.5
            samples = np.column_stack([np.full(512, x), center_height + thickness * unit_height,
                                       np.full(512, z), np.zeros(512)])
            density = probe.run("densities", samples, clouds=column, textures=textures)[:, 0]
            optical_depth = float(np.mean(density)) * thickness * clouds["highExtinction"]
            actual.append(result[0])
            expected.append(np.exp(-optical_depth))
    actual = np.asarray(actual)
    expected = np.asarray(expected)
    assert np.isfinite(actual).all() and np.all((0 <= actual) & (actual <= 1))
    assert np.any(expected < 1.0 - 1e-5), "column integration must exercise nonzero cloud density"
    np.testing.assert_allclose(actual, expected, atol=2e-6, rtol=2e-5,
                               err_msg="changing high-layer thickness must preserve its optical column")
    print(f"PASS high-layer view columns: 51 columns at 100/200/800m, "
          f"maximum Beer-law error {np.max(np.abs(actual - expected)):.7f}")


def uniform_cloud_textures(device):
    sampler = device.create_sampler()
    weather = weather_fixture(device, np.ones((3, 1, 1, 4), np.float32)).create_view(sampler=sampler)
    volume = device.create_texture(type=spy.TextureType.texture_3d, format=spy.Format.rgba32_float,
                                   width=1, height=1, depth=1, usage=spy.TextureUsage.shader_resource,
                                   data=np.ones((1, 1, 1, 4), np.float32)).create_view(sampler=sampler)
    return {"Weather": weather, "Base": volume, "Detail": volume}


def check_distant_storm_horizon(probe, textures):
    # Horizontal rays reach this shell beyond either detail budget. Uniform
    # overcast isolates the transport under test from weather-map gaps.
    clouds = cloud_parameters(baseHeight=800.0, coverage=.85, wetness=1., weather=1., thunder=1., extinction=.03)
    y = -58.
    radius = clouds["planetRadius"]
    entry, exit_distance = (np.sqrt((h - y) * (2 * radius + h + y))
                            for h in (800., 800. + clouds["thickness"]))
    angle = np.linspace(0., 2 * np.pi, 32, endpoint=False)
    directions = np.column_stack((np.cos(angle), np.zeros_like(angle), np.sin(angle), np.zeros_like(angle)))
    def transmission(distance, detail_range, extinction=.03):
        rays = np.tile([0., y, 0., distance], (len(angle), 1))
        params = clouds | {"maxDistance": detail_range, "extinction": extinction}
        return probe.run("viewTransmission", rays, directions, params, textures)[:, 0]
    np.testing.assert_array_equal(transmission(entry - 10., 24000.), 1.)
    full = transmission(exit_distance + 10., 24000.)
    assert np.median(full) < .1, "Overcast shell disappeared beyond the resolved cloud range"
    np.testing.assert_allclose(full, transmission(exit_distance + 10., 48000.), atol=2e-5)
    # A translucent column also exercises a meaningful, unsaturated prefix.
    middle = transmission(.5 * (entry + exit_distance), 24000., .00003)
    assert np.any((middle > .05) & (middle < .95))
    np.testing.assert_allclose(transmission(exit_distance + 10., 24000., .00003), middle ** 2, atol=2e-5)
    print(f"PASS storm horizon at {entry / 1000:.1f} km: foreground is clear, "
          f"median cloud transmission {np.median(full):.5f} at both detail ranges")








def check_tangent_visibility(probe, textures):
    maximum_change = 0.0
    offsets = np.radians([-0.001, -0.0001, 0.0, 0.0001, 0.001])
    # Reduced extinction keeps the tangent rays measurable instead of completely opaque.
    for layer, base, height, extinction in ((0, 400.0, 540.0, 1.5e-4), (1, 6000.0, 6400.0, 2e-5)):
        clouds = cloud_parameters(wetness=1., extinction=extinction if layer == 0 else 0.0,
                                  highExtinction=extinction if layer == 1 else 0.0)
        origins, directions = [], []
        for x in (-1200.0, 0.0, 1200.0):
            for z in (-700.0, 700.0):
                radial_y = clouds["planetRadius"] + height
                radial_xy = np.hypot(x, radial_y)
                c = x * x + z * z + radial_y * radial_y - (clouds["planetRadius"] + base) ** 2
                tangent = np.arcsin(-np.sqrt(c) / radial_xy) - np.arctan2(x, radial_y)
                for angle in tangent + offsets:
                    origins.append([x, height, z, 400000.0])
                    directions.append([np.cos(angle), np.sin(angle), 0.0, 0.0])
        values = probe.run("visibility", origins, directions, clouds, textures)[:, 0].reshape(-1, len(offsets))
        assert np.isfinite(values).all() and np.all((0 <= values) & (values <= 1))
        assert np.any((values > 0.01) & (values < 0.99)), "tangent continuity must exercise visible attenuation"
        differences = np.diff(values, axis=1)
        if layer == 0:
            reference = probe.run("visibilityReference", origins, directions, clouds, textures)[:, 0]
            # Remove the real change in shadow along the perturbed ray before measuring artifacts.
            differences -= np.diff(reference.reshape(values.shape), axis=1)
        change = np.max(np.abs(differences))
        assert change < 0.01, ("shadow grid jumps when a second shell interval appears", layer, change)
        maximum_change = max(maximum_change, change)
    print(f"PASS below-horizon shell tangencies: maximum excess transmission change {maximum_change:.6f}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dump", type=Path, help="Optional scratch directory for numerical diagnostics")
    args = parser.parse_args()
    device = spy.Device(type=spy.DeviceType.vulkan, compiler_options=OPTIONS)
    probe = Probe(device)
    check_column_history(probe)
    check_air_skylight(probe)
    check_weather_column_coordinates(probe)
    check_homogeneous(probe)
    check_intervals(probe)
    check_layer_intervals(probe)
    check_light_profiles(probe)
    textures = cloud_textures(device, args.dump)
    check_column_noise(probe, textures)
    uniform = uniform_cloud_textures(device)
    check_distant_storm_horizon(probe, uniform)
    check_high_column_transmission(probe, textures)
    check_visibility(probe, textures, args.dump)
    cloud_boundaries = check_cloud_boundary_visibility(probe, textures, args.dump)
    check_cloud_cache_transition(probe, textures, cloud_boundaries, args.dump)
    check_light_support(probe, textures)
    check_tangent_visibility(probe, uniform)


if __name__ == "__main__":
    main()
