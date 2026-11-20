# SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
# SPDX-License-Identifier: MIT
"""Numerical readback checks for production cloud geometry, density and transport.

Install numpy and slangpy. The Vulkan device may use a software ICD. These
checks do not measure game performance or validate presentation.
"""
import argparse
import json
from pathlib import Path
import re

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
        "night": 0.0, "meterToWorld": 1.0,
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


def check_morphology_configuration():
    # Resource-period arithmetic is a configuration contract. Visual quality is
    # tested by density readback and rendered images, not source-code spelling.
    source = (SHADERS / "rtx/pass/cloud_noise.h").read_text()
    base = int(re.search(r"kCloudBasePeriod\s*=\s*(\d+)", source).group(1))
    low, high = cloud_weather_periods()
    assert base > 0 and low % (4 * base) == 0
    assert max(low, high) % min(low, high) == 0
    print("PASS cloud periods: base, detail and weather tiles divide the host wind wrap")


def cloud_weather_periods():
    source = (SHADERS / "rtx/pass/cloud_noise.h").read_text()
    return tuple(int(re.search(rf"{name}\s*=\s*(\d+)", source).group(1))
                 for name in ("kCloudWeatherPeriod", "kCloudHighWeatherPeriod"))


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
    uv = [[.003, .992, 500., 0], [.957, .12, 6200., 1], [.49, .52, 1100., 0]]
    for z_up in (False, True):
        up = [0, 0, 1] if z_up else [0, 1, 0]
        clouds = cloud_parameters(up=up, groundReference=[8591., 0., 0.], windOffset=[-60300., 0., 755.])
        period = clouds["shapeScale"] * cloud_weather_periods()[0]
        across = [0., period if z_up else 0., 0. if z_up else period, 0.]
        coords = probe.run("columnCoordinates", uv, [[0., 0., 0., 0.], across, [-period, 0., 0., 0.]], clouds)
        np.testing.assert_allclose(coords[:, :2], np.asarray(uv)[:, :2], atol=1e-6)
        np.testing.assert_allclose(coords[:, 2], np.asarray(uv)[:, 2], atol=.005)
    print("PASS baked weather columns return to their texel across tile edges, wind offsets and both up axes")


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


def check_cloud_silhouettes(probe, textures):
    # Neighboring view columns at low elevation through the same weather field.
    # Only rays that switch between empty and occupied samples exercise this bug.
    azimuth = (np.arange(96) * .003 + np.arange(4)[:, None] * (np.pi / 2)).ravel()
    azimuth, elevation = np.meshgrid(azimuth, .09 + np.arange(24) * .003)
    directions = np.stack([np.cos(elevation) * np.cos(azimuth), np.sin(elevation),
                           np.cos(elevation) * np.sin(azimuth), np.zeros_like(azimuth)], axis=-1).reshape(-1, 4)
    args = cloud_parameters(steps=24, viewAngularFootprint=.003, maxDistance=24000.)
    samples = probe.run("stationaryColumnNoise", np.zeros_like(directions), directions, args, textures,
                        output_records=2)
    misses, reference = samples[1::2, :2].T
    edge = (misses > 0) & (misses < 32) & (reference > .01)
    assert edge.sum() >= 32, "fixture must cover intermittent silhouettes"
    moments = samples[::2][edge]
    error = moments[:, 2:] + (moments[:, :2] - reference[edge, None]) ** 2
    raw, filtered = np.sqrt(error.mean(axis=0))
    bias = (moments[:, 1] - reference[edge]).mean()
    assert filtered < .5 * raw and abs(bias) < .01, (raw, filtered, bias)
    print(f"PASS {edge.sum()} intermittent silhouette columns, fixed 24-step march: "
          f"opacity RMSE {raw:.5f} -> {filtered:.5f} vs independent phases, mean error {bias:+.5f}")


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
TEXTURE_FILES = {"Base": "cloud_base.bin", "Detail": "cloud_erosion.bin", "Weather": "cloud_weather.bin"}


def weather_resolutions():
    source = (SHADERS / "rtx/pass/cloud_noise.h").read_text()
    return tuple(int(re.search(rf"{name}\s*=\s*(\d+)", source)[1]) for name in
                 ("kCloudWeatherResolution", "kCloudHighWeatherResolution"))


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
                                    mag_filter=spy.TextureFilteringMode.linear, mip_filter=spy.TextureFilteringMode.linear,
                                    address_u=spy.TextureAddressingMode.wrap, address_v=spy.TextureAddressingMode.wrap,
                                    address_w=spy.TextureAddressingMode.wrap)
    textures = {}
    low_resolution, high_resolution = weather_resolutions()
    for name, filename in TEXTURE_FILES.items():
        weather = name == "Weather"
        base = name == "Base"
        side = high_resolution if weather else 64
        texture = device.create_texture(type=spy.TextureType.texture_2d_array if weather else spy.TextureType.texture_3d,
            format=spy.Format.rg16_float if base else spy.Format.rgba8_unorm, width=side, height=side,
            depth=1 if weather else side, array_length=3 if weather else 1, mip_count=side.bit_length(),
            usage=spy.TextureUsage.shader_resource)
        raw = (CLOUD_ASSETS / filename).read_bytes()
        offset = 0
        for mip in range(side.bit_length()):
            n = side >> mip
            for layer in range(3 if weather else 1):
                if weather and layer != 1 and n > low_resolution:
                    continue
                shape = (n, n, 4) if weather else (n, n, n, 2 if base else 4)
                size = np.prod(shape) * (2 if base else 1)
                a = np.frombuffer(raw[offset:offset + size], dtype="<f2" if base else "u1").reshape(shape)
                texture.copy_from_numpy(a.copy(), layer=layer, mip=mip)
                offset += size
                if mip == 0:
                    assert np.isfinite(a).all() and a[..., 0].max() > a[..., 0].min()
                if weather and layer == 2 and n == low_resolution:
                    # Profile peaks and radial modulation must survive quantization.
                    assert np.all(np.ptp(a[..., :3], axis=1).mean(axis=0) > 100)
                    assert np.all(np.ptp(a[..., :3], axis=0).max(axis=0) > 10)
                    peaks = a[..., :3].argmax(axis=1).mean(axis=0)
                    assert np.all(np.diff(peaks) > 0), peaks
        assert offset == len(raw)
        textures[name] = texture.create_view(sampler=sampler)
    return textures


def weather_tile_points(clouds, altitude, resolution=192):
    period = cloud_weather_periods()[0] * clouds["shapeScale"]
    axis = np.linspace(-0.5 * period, 0.5 * period, resolution, endpoint=False, dtype=np.float32)
    x, z = np.meshgrid(axis, axis)
    radius = clouds["planetRadius"]
    y = np.sqrt((radius + altitude) ** 2 - x.astype(np.float64) ** 2 - z.astype(np.float64) ** 2) - radius
    points = np.column_stack([x.ravel(), y.ravel(), z.ravel(), np.zeros(x.size)])
    return axis, points


def check_density(probe, textures, dump):
    clouds = cloud_parameters()
    axis, points = weather_tile_points(clouds, 520)
    shape = (len(axis), len(axis))
    fields = []
    for weather in (0.0, 0.5, 1.0):
        field = probe.run("densities", points, clouds=cloud_parameters(weather=weather), textures=textures)[:, 0]
        assert np.isfinite(field).all() and np.all((0 <= field) & (field <= 1))
        fields.append(field)
    assert np.mean(fields[0]) > 0.001, "fair-weather density has collapsed"
    assert np.mean(fields[2]) > np.mean(fields[0]), "rain should build a denser layer"

    coverage_fields = []
    for coverage in (0.2, 0.45, 0.8, 1.0):
        field = probe.run("densities", points,
                          clouds=cloud_parameters(coverage=coverage), textures=textures)[:, 0]
        assert np.isfinite(field).all() and np.all((0 <= field) & (field <= 1))
        coverage_fields.append(field)
    coverage_means = np.array([np.mean(field) for field in coverage_fields])
    assert np.all(np.diff(coverage_means) >= -1e-5), (
        "increasing cloud amount must not reduce occupied cloud density", coverage_means)

    filtered_points = points.copy()
    # A larger footprint must actually span several weather texels.
    filtered_points[:, 3] = 4 * clouds["shapeScale"] * cloud_weather_periods()[0] / 512
    filtered = probe.run("densities", filtered_points, clouds=cloud_parameters(), textures=textures)[:, 0]
    assert np.isfinite(filtered).all() and np.all((0 <= filtered) & (filtered <= 1))
    assert np.std(filtered) < np.std(fields[0]), "a larger footprint should suppress unresolved variation"
    if dump:
        dump.mkdir(parents=True, exist_ok=True)
        np.savez(dump / "cloud-density.npz", axis=axis, clear=fields[0].reshape(shape),
                 rain=fields[2].reshape(shape), filtered=filtered.reshape(shape),
                 coverage_020=coverage_fields[0].reshape(shape), coverage_045=coverage_fields[1].reshape(shape),
                 coverage_080=coverage_fields[2].reshape(shape), coverage_100=coverage_fields[3].reshape(shape))
    print(f"PASS production density: clear/rain means {np.mean(fields[0]):.5f}/{np.mean(fields[2]):.5f}, "
          f"coverage sweep {coverage_means.tolist()}")


def check_low_wind_wrap(probe, textures):
    clouds = cloud_parameters(baseHeight=800.0, extinction=0.03)
    period = max(cloud_weather_periods()) * clouds["shapeScale"]
    # Power-of-two spacing keeps both p and p +/- period exactly representable
    # in float32. A thirds-of-a-metre lattice would compare different rounded
    # coordinates after the shift instead of isolating field periodicity.
    _, points = weather_tile_points(clouds, 920, resolution=256)
    reference = probe.run("densities", points, clouds=clouds, textures=textures)[:, 0]
    assert np.any(reference > 0.1), "wind wrapping must exercise occupied cumulus"
    maximum_error = 0.0
    for wind in ([period, 0, 0], [0, 0, period], [-period, 0, period]):
        wrapped = probe.run("densities", points, clouds=clouds | {"windOffset": wind}, textures=textures)[:, 0]
        np.testing.assert_allclose(wrapped, reference, atol=5e-4, rtol=1e-3,
                                   err_msg="low-cloud density jumps at the shared wind wrap")
        maximum_error = max(maximum_error, np.max(np.abs(wrapped - reference)))
    print(f"PASS low-layer wind wrap: {period:.0f}m, maximum density difference {maximum_error:.7f}")


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


def check_horizon_cloud_openings(probe, textures):
    origin_azimuth = np.linspace(0.0, 2.0 * np.pi, 12, endpoint=False)
    ray_azimuth = np.linspace(0.0, 2.0 * np.pi, 12, endpoint=False)
    elevation = np.radians(10.0)
    horizontal = np.cos(elevation)
    directions = np.column_stack((
        horizontal * np.cos(np.tile(ray_azimuth, len(origin_azimuth))),
        np.full(len(origin_azimuth) * len(ray_azimuth), np.sin(elevation)),
        horizontal * np.sin(np.tile(ray_azimuth, len(origin_azimuth))),
        np.zeros(len(origin_azimuth) * len(ray_azimuth))))
    origin_x = 8043.0 + 8192.0 * np.cos(origin_azimuth)
    origin_z = 1240.0 + 8192.0 * np.sin(origin_azimuth)
    origins = np.repeat(np.column_stack((origin_x, np.full_like(origin_x, 139.0), origin_z)),
                        len(ray_azimuth), axis=0)
    rays = np.column_stack((origins, np.full(len(origins), 1700.0)))

    cases = (
        ("broken", cloud_parameters(coverage=0.45, weather=0.0, thunder=0.0)),
        ("cloud-rich deck", cloud_parameters(coverage=0.85, weather=0.0, thunder=0.0)),
    )
    for name, clouds in cases:
        transmission = probe.run("viewTransmission", rays, directions, clouds, textures)[:, 0]
        assert np.isfinite(transmission).all()
        assert np.all((0.0 <= transmission) & (transmission <= 1.0))
        open_count = int(np.count_nonzero(transmission > 0.1))
        cloud_count = int(np.count_nonzero(transmission < 0.95))
        assert open_count >= 4, (
            f"{name} horizon rays lost all meaningful openings", open_count)
        assert cloud_count >= 4, (
            f"{name} horizon rays lost all cloud-bearing directions", cloud_count)
        assert np.quantile(transmission, 0.9) > 0.1, (
            f"{name} horizon field collapsed into a continuous opaque band")
        print(f"PASS horizon cloud openings ({name}): open/cloud-bearing rays "
              f"{open_count}/{cloud_count}, transmission p10/p50/p90 "
              f"{np.quantile(transmission, 0.1):.4f}/"
              f"{np.quantile(transmission, 0.5):.4f}/"
              f"{np.quantile(transmission, 0.9):.4f}")



def check_high_density(probe, textures, dump):
    axis = np.linspace(-8192, 8192, 128, endpoint=False, dtype=np.float32)
    x, z = np.meshgrid(axis, axis)
    clouds = cloud_parameters(extinction=0.0, highExtinction=0.01)
    points = np.column_stack([x.ravel(), np.full(x.size, 6250), z.ravel(), np.zeros(x.size)])
    actual = probe.run("densities", points, clouds=clouds, textures=textures)[:, 0]
    assert np.isfinite(actual).all() and np.all((0 <= actual) & (actual <= 1))
    assert 0.001 < np.mean(actual) < 0.9, "the high layer must retain variable thin coverage"

    previous = np.zeros_like(actual)
    for coverage in (0.0, 0.1, 0.35, 0.7, 1.0):
        density = probe.run("densities", points, clouds=clouds | {"highCoverage": coverage},
                            textures=textures)[:, 0]
        assert np.isfinite(density).all() and np.all((0 <= density) & (density <= 1))
        if coverage == 0:
            np.testing.assert_array_equal(density, 0)
        assert np.all(density >= previous - 1e-6), "increasing high coverage must not remove condensate"
        previous = density

    # The high field retains its own period and also repeats at the shared host wrap.
    low_period, high_period = cloud_weather_periods()
    for periods in sorted({high_period, max(low_period, high_period)}):
        period = periods * clouds["shapeScale"]
        for wind in ([period, 0, 0], [0, 0, period], [-period, 0, period]):
            wrapped = probe.run("densities", points, clouds=clouds | {"windOffset": wind},
                                textures=textures)[:, 0]
            np.testing.assert_allclose(wrapped, actual, atol=1e-5, rtol=1e-4,
                                       err_msg="high clouds jump when the host wraps wind")

    for height in (5800, 7100):
        points[:, 1] = height
        outside = probe.run("densities", points, clouds=clouds, textures=textures)[:, 0]
        np.testing.assert_array_equal(outside, 0)
    if dump:
        np.savez(dump / "cloud-high-density.npz", axis=axis, density=actual.reshape(x.shape))
    print(f"PASS high-layer density: bounds, coverage monotonicity, wind wrap, mean {np.mean(actual):.5f}")


def check_high_column_transmission(probe, textures):
    positions = [(x, z) for x in (-6144.0, -2048.0, 2048.0, 6144.0)
                 for z in (-6144.0, -2048.0, 2048.0, 6144.0)]
    positions.append((8183.0, 1171.0))
    center_height = 6400.0
    actual, expected = [], []
    for thickness in (100.0, 200.0, 800.0):
        clouds = cloud_parameters(extinction=0.0, highBaseHeight=center_height - 0.5 * thickness,
                                  highThickness=thickness, highExtinction=1.2 / thickness,
                                  highCoverage=0.35, highMaxDistance=96000.0)
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


def check_distant_storm_horizon(probe, textures):
    # Horizontal rays from the ground reach this shell well beyond either detail budget.
    clouds = cloud_parameters(baseHeight=800.0, coverage=.85, weather=1., thunder=1., extinction=.03)
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


def high_column_reference(origin, direction, base, thickness, extinction, distance, radius):
    """Integrate a constant horizontal field between spherical profile knots."""
    origin = np.asarray(origin, np.float64)
    direction = np.asarray(direction, np.float64)
    point = origin + [0, radius, 0]
    projection = point @ direction
    boundaries = [0.0, distance]
    # White weather gives a full band with smooth ramps over [0, .15] and [.85, 1].
    for altitude in (base, base + 0.15 * thickness, base + 0.85 * thickness, base + thickness):
        height = origin[1]
        squared = origin[0] ** 2 + origin[2] ** 2 + (height - altitude) * (2 * radius + height + altitude)
        discriminant = projection * projection - squared
        if discriminant >= 0:
            root = np.sqrt(discriminant)
            boundaries.extend(t for t in (-projection - root, -projection + root) if 0 < t < distance)
    boundaries = sorted(set(boundaries))
    nodes, weights = np.polynomial.legendre.leggauss(48)
    depth = 0.0
    for begin, end in zip(boundaries[:-1], boundaries[1:]):
        middle = 0.5 * (begin + end)
        altitude = np.linalg.norm(point + direction * middle) - radius
        if not base < altitude < base + thickness:
            continue
        samples = middle + 0.5 * (end - begin) * nodes
        positions = point[None, :] + samples[:, None] * direction
        height = (np.linalg.norm(positions, axis=1) - radius - base) / thickness
        bottom = np.clip(height / 0.15, 0, 1)
        top = np.clip((height - 0.85) / 0.15, 0, 1)
        profile = bottom * bottom * (3 - 2 * bottom) * (1 - top * top * (3 - 2 * top))
        depth += 0.5 * (end - begin) * np.dot(weights, profile) * extinction
    return depth


def check_high_filtered_columns(probe, textures):
    weather = weather_fixture(probe.device, np.ones((3, 1, 1, 4), np.float32))
    sampler = probe.device.create_sampler(address_u=spy.TextureAddressingMode.wrap,
                                          address_v=spy.TextureAddressingMode.wrap)
    constant = {**textures, "Weather": weather.create_view(sampler=sampler)}
    radius = 6370937.0
    maximum_error = 0.0
    maximum_angular_delta = 0.0
    cases = 0
    for thickness in (50.0, 200.0):
        base = 6400.0 - 0.5 * thickness
        extinction = 0.005 / (0.85 * thickness)
        origins, directions = [], []
        for height, angles in ((139.0, (90, 45, 20, 10, 5, 0)),
                                (6400.0, (-0.4, -0.001, 0, 0.001, 0.4, 90)), (7000.0, (-90,))):
            for angle in np.radians(angles):
                origins.append([0, height, 0, 1e6])
                directions.append([np.cos(angle), np.sin(angle), 0, 0])
        origins = np.asarray(origins, dtype=np.float32)
        directions = np.asarray(directions, dtype=np.float32)
        expected = np.array([high_column_reference(origin[:3], direction[:3], base, thickness,
                                                   extinction, origin[3], radius)
                             for origin, direction in zip(origins, directions)])
        # Keep the numerical rays before distance fading, including spherical
        # horizontal intersections beyond the ordinary game view range.
        clouds = cloud_parameters(extinction=0.0, highExtinction=extinction, highBaseHeight=base,
                                  highThickness=thickness, highCoverage=1.0, highMaxDistance=1e6,
                                  maxDistance=1e6, planetRadius=radius)
        for steps in (64, 96, 160, 320):
            for footprint in (0.0, 0.00191831747, 0.00383663494, 0.01):
                parameters = clouds | {"highSteps": steps, "viewAngularFootprint": footprint}
                transmission = probe.run("viewTransmission", origins, directions, parameters, constant)[:, 0]
                assert np.isfinite(transmission).all() and np.all((0 < transmission) & (transmission <= 1))
                depth = -np.log(transmission.astype(np.float64))
                np.testing.assert_allclose(depth, expected, atol=2e-5, rtol=0.005,
                                           err_msg="high-layer filtering must preserve its spherical column")
                maximum_error = max(maximum_error, np.max(np.abs(depth - expected) / expected))
                cases += len(origins)
        tangent = -np.arccos((radius + base) / (radius + 6400.0))
        angles = np.array([center + offset for center in (0.0, tangent)
                           for offset in np.radians([-0.001, -0.0001, 0, 0.0001, 0.001])])
        rays = np.tile([0, 6400, 0, 1e6], (len(angles), 1))
        sunlight = np.column_stack([np.cos(angles), np.sin(angles), np.zeros((len(angles), 2))])
        visibility = probe.run("visibility", rays, sunlight, clouds, constant)[:, 0].reshape(2, -1)
        assert np.isfinite(visibility).all() and np.all((0 <= visibility) & (visibility <= 1))
        assert np.any((visibility > 0.01) & (visibility < 0.99))
        delta = np.max(np.abs(np.diff(visibility, axis=1)))
        assert delta < 0.01, ("high-layer visibility jumps at a small solar-angle change", delta)
        maximum_angular_delta = max(maximum_angular_delta, delta)
    print(f"PASS filtered high-layer columns: {cases} thickness/angle/step/footprint cases, "
          f"maximum relative depth error {maximum_error:.6f}, solar-angle delta {maximum_angular_delta:.6f}")


def check_layer_height_footprints(probe):
    for up in ([0, 1, 0], [0, 0, 1]):
        clouds = cloud_parameters(up=up)
        vertical = np.asarray(up)
        origins, directions, expected = [], [], []
        for height in (0.0, 6400.0):
            for cosine, sine in ((1.0, 0.0), (0.0, 1.0), (0.6, 0.8)):
                direction = cosine * vertical + [sine, 0, 0]
                for span in (8.0, 50.0, 500.0):
                    radial = cosine * span + sine * sine * span * span / (4 * (clouds["planetRadius"] + height))
                    for pixel in (0.0, 5.0, 1000.0):
                        origins.append([*(height * vertical), span])
                        directions.append([*direction, pixel])
                        expected.append([max(radial, pixel * sine), radial])
        actual = probe.run("heightFootprints", origins, directions, clouds)
        np.testing.assert_allclose(actual[:, :2], expected, atol=2e-5, rtol=1e-6)
        np.testing.assert_array_equal(actual[:, 0], actual[:, 3], err_msg="default footprint must retain low-layer semantics")
        np.testing.assert_array_equal(actual[:, 1], actual[:, 2], err_msg="high height integration must ignore the pixel cone")
    print("PASS layer height footprints: low/default pixel filtering unchanged, high radial support, Y-up and Z-up")


def check_tangent_visibility(probe, textures):
    maximum_change = 0.0
    offsets = np.radians([-0.001, -0.0001, 0.0, 0.0001, 0.001])
    # Reduced extinction keeps the tangent rays measurable instead of completely opaque.
    for layer, base, height, extinction in ((0, 400.0, 540.0, 1.5e-4), (1, 6000.0, 6400.0, 2e-5)):
        clouds = cloud_parameters(extinction=extinction if layer == 0 else 0.0,
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
    check_morphology_configuration()
    check_column_history(probe)
    check_air_skylight(probe)
    check_weather_column_coordinates(probe)
    check_homogeneous(probe)
    check_intervals(probe)
    check_layer_intervals(probe)
    check_light_profiles(probe)
    textures = cloud_textures(device, args.dump)
    check_column_noise(probe, textures)
    check_cloud_silhouettes(probe, textures)
    check_density(probe, textures, args.dump)
    check_horizon_cloud_openings(probe, textures)
    check_distant_storm_horizon(probe, textures)
    check_low_wind_wrap(probe, textures)
    from test_cloud_morphology import check_family_periodicity, check_erosion_seams
    fixtures = cloud_parameters()
    period = max(cloud_weather_periods()) * fixtures["shapeScale"]
    check_family_periodicity(probe, textures, fixtures, period)
    check_erosion_seams(probe, textures, fixtures)
    check_high_density(probe, textures, args.dump)
    check_high_column_transmission(probe, textures)
    check_high_filtered_columns(probe, textures)
    check_layer_height_footprints(probe)
    check_visibility(probe, textures, args.dump)
    cloud_boundaries = check_cloud_boundary_visibility(probe, textures, args.dump)
    check_cloud_cache_transition(probe, textures, cloud_boundaries, args.dump)
    check_light_support(probe, textures)
    check_tangent_visibility(probe, textures)


if __name__ == "__main__":
    main()
