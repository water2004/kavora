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
import struct
import time

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
        "albedo": 1.0, "steps": 160, "lightSteps": 16,
        "anisotropy": 0.65, "multipleScattering": 0.8, "weather": 0.0,
        "thunder": 0.0, "groundReference": [0, 0, 0], "planetRadius": 6360000.0,
        "night": 0.0,
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
    print("PASS cloud periods: family bodies and phase lattice divide the host wind wrap")


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

    def run(self, entry, inputs, directions=None, clouds=None, textures=None):
        data = np.ascontiguousarray(inputs, dtype=np.float32).reshape(-1, 4)
        if entry not in self.kernels:
            program = self.device.load_program("cloud_transport_contracts.comp.slang", [entry])
            self.kernels[entry] = self.device.create_compute_kernel(program)
        output = self.device.create_buffer(element_count=len(data), struct_size=16,
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


def production_kernel(device, relative, defines=None, combined_source=False):
    session = device.create_slang_session({**OPTIONS, "defines": {"__SLANG__": "1", **(defines or {})}})
    source = (SHADERS / relative).read_text(encoding="utf-8")
    # ShaderCursor writes a constant buffer. SPIR-V compilation checks the production push layout separately.
    source = source.replace("layout(push_constant)", "")
    source = source.replace("layout(binding = 1)\nSampler2D<float4> CloudCirrusAtlas;",
                            "Sampler2D<float4>.Handle CloudCirrusAtlas;")
    if "CloudCirrusAtlas" in source:
        # Handle introduces a globals buffer: auto-assign the output binding so
        # that buffer cannot alias the native output's explicit binding zero.
        source = source.replace("layout(rgba16f, binding = 0)\n", "")
    if combined_source:
        # SlangPy binds combined samplers through descriptor handles.
        source = source.replace("Sampler3D<float4> Source;", "Sampler3D<float4>.Handle Source;")
        source = source.replace("Sampler2D<float4> Source;", "Sampler2D<float4>.Handle Source;")
    module = session.load_module_from_source(Path(relative).stem.replace(".", "_"), source)
    return device.create_compute_kernel(session.link_program([module], [module.entry_point("main")]))


def cloud_atlas_path():
    return Path(__file__).resolve().parents[4] / "kavora/packaging/runtime-textures/cirrus_density.dds"


def cloud_atlas(device):
    source = cloud_atlas_path().read_bytes()
    header = struct.unpack_from('<31I', source, 4)
    dx10 = struct.unpack_from('<5I', source, 128)
    if source[:4] != b'DDS ' or dx10[:4] != (61, 3, 0, 1):
        raise ValueError('Expected the linear R8_UNORM cirrus density atlas')
    height, width, mip_count = header[2], header[3], header[6]
    texture = device.create_texture(type=spy.TextureType.texture_2d, format=spy.Format.r8_unorm,
                                   width=width, height=height, mip_count=mip_count,
                                   usage=spy.TextureUsage.shader_resource)
    offset = 148
    for mip in range(mip_count):
        w, h = max(1, width >> mip), max(1, height >> mip)
        texture.copy_from_numpy(np.frombuffer(source, dtype=np.uint8, count=w*h, offset=offset).reshape(h, w).copy(), mip=mip)
        offset += w*h
    if offset != len(source):
        raise ValueError('Unexpected density atlas payload length')
    sampler = device.create_sampler(min_filter=spy.TextureFilteringMode.linear,
                                    mag_filter=spy.TextureFilteringMode.linear,
                                    mip_filter=spy.TextureFilteringMode.linear,
                                    max_anisotropy=8,
                                    address_u=spy.TextureAddressingMode.clamp_to_edge,
                                    address_v=spy.TextureAddressingMode.clamp_to_edge)
    return texture.create_view(sampler=sampler)


def cloud_textures(device, dump=None):
    usage = spy.TextureUsage.shader_resource | spy.TextureUsage.unordered_access
    sampler = device.create_sampler(address_u=spy.TextureAddressingMode.wrap,
                                    address_v=spy.TextureAddressingMode.wrap,
                                    address_w=spy.TextureAddressingMode.wrap)
    textures = {}
    noise = {name: production_kernel(device, "rtx/pass/clouds/generate_cloud_noise.comp.slang", defines)
             for name, defines in (("Base", {"CLOUD_NOISE_BASE": "1"}), ("Detail", {}))}
    weather = production_kernel(device, "rtx/pass/clouds/generate_cloud_weather.comp.slang")
    weather_source = (SHADERS / "rtx/pass/clouds/generate_cloud_weather.comp.slang").read_text(encoding="utf-8")
    atlas = cloud_atlas(device) if "CloudCirrusAtlas" in weather_source else None
    filters = {name: production_kernel(device, "rtx/pass/clouds/filter_cloud_noise.comp.slang", defines, True)
               for name, defines in (("Base", {"CLOUD_NOISE_VOLUME": "1", "CLOUD_NOISE_FLOAT": "1"}),
                                     ("Detail", {"CLOUD_NOISE_VOLUME": "1"}), ("Weather", {}))}
    # The preview tool can replay an earlier shader root with its original storage format.
    base_source = (SHADERS / "rtx/pass/clouds/generate_cloud_noise.comp.slang").read_text(encoding="utf-8")
    base_format = spy.Format.rg16_float if "layout(rg16f," in base_source else spy.Format.rgba16_float
    configuration = (SHADERS / "rtx/pass/cloud_noise.h").read_text(encoding="utf-8")
    weather_resolution = int(re.search(r'kCloudWeatherResolution\s*=\s*(\d+)', configuration)[1])
    for name, size, dimensions in (("Base", 128, 3), ("Detail", 32, 3), ("Weather", weather_resolution, 2)):
        floating = name != "Detail"
        image_format = base_format if name == "Base" else (spy.Format.rgba16_float if floating else spy.Format.rgba8_unorm)
        texture = device.create_texture(type=spy.TextureType.texture_3d if dimensions == 3 else spy.TextureType.texture_2d,
                                       format=image_format,
                                       width=size, height=size,
                                       depth=size if dimensions == 3 else 1,
                                       mip_count=int(np.log2(size)) + 1, usage=usage)
        view = texture.create_view(mip=0, mip_count=1)
        weather_started = time.perf_counter() if name == "Weather" else None
        if dimensions == 3:
            noise[name].dispatch(thread_count=[size, size, size], vars={"CloudNoise": view, "cb": {"detail": name == "Detail"}})
        else:
            bindings = {"CloudWeather": view}
            if atlas is not None:
                bindings["CloudCirrusAtlas"] = atlas.descriptor_handle_combined
            weather.dispatch(thread_count=[size, size, 1], vars=bindings)
        data = texture.to_numpy()
        if weather_started is not None:
            print(f"Cloud weather generation and readback: {time.perf_counter() - weather_started:.4f}s")
        assert np.isfinite(data).all(), (name, "generated nonfinite noise")
        assert np.ptp(data[..., 0]) > (0.12 if floating else 32), (name, "degenerate generated noise")
        if name == "Weather":
            cumulus = data[..., 0]
            stratiform = data[..., 1]
            cloud_type = data[..., 2]
            assert np.all((data[..., :4] >= 0) & (data[..., :4] <= 1)), (
                "weather family masks, type and cirrus must stay normalized")
            assert np.ptp(cumulus) > 0.12 and np.ptp(stratiform) > 0.12, (
                "both low-cloud families must retain spatial variation")
            cumulus_p10, cumulus_p90 = np.quantile(cumulus, [0.10, 0.90])
            stratiform_p10, stratiform_p90 = np.quantile(stratiform, [0.10, 0.90])
            type_p10, type_p90 = np.quantile(cloud_type, [0.10, 0.90])
            adjacent_correlation = float(0.5 * (
                np.corrcoef(cumulus[:, :-1].ravel(), cumulus[:, 1:].ravel())[0, 1]
                + np.corrcoef(cumulus[:-1, :].ravel(), cumulus[1:, :].ravel())[0, 1]))
            mesoscale_correlation = float(0.5 * (
                np.corrcoef(cumulus[:, :-32].ravel(), cumulus[:, 32:].ravel())[0, 1]
                + np.corrcoef(cumulus[:-32, :].ravel(), cumulus[32:, :].ravel())[0, 1]))
            regional_correlation = float(0.5 * (
                np.corrcoef(cumulus[:, :-128].ravel(), cumulus[:, 128:].ravel())[0, 1]
                + np.corrcoef(cumulus[:-128, :].ravel(), cumulus[128:, :].ravel())[0, 1]))
            deck_adjacent_correlation = float(0.5 * (
                np.corrcoef(stratiform[:, :-1].ravel(), stratiform[:, 1:].ravel())[0, 1]
                + np.corrcoef(stratiform[:-1, :].ravel(), stratiform[1:, :].ravel())[0, 1]))
            deck_meso_correlation = float(0.5 * (
                np.corrcoef(stratiform[:, :-32].ravel(), stratiform[:, 32:].ravel())[0, 1]
                + np.corrcoef(stratiform[:-32, :].ravel(), stratiform[32:, :].ravel())[0, 1]))
            deck_regional_correlation = float(0.5 * (
                np.corrcoef(stratiform[:, :-128].ravel(), stratiform[:, 128:].ravel())[0, 1]
                + np.corrcoef(stratiform[:-128, :].ravel(), stratiform[128:, :].ravel())[0, 1]))
            family_correlation = float(np.corrcoef(cumulus.ravel(), stratiform.ravel())[0, 1])
            assert cumulus_p10 < 0.25 and cumulus_p90 > 0.65, (
                "cumulus field must retain openings and cloud-rich groups", cumulus_p10, cumulus_p90)
            assert stratiform_p10 < 0.25 and stratiform_p90 > 0.65, (
                "stratiform field must retain broad openings and connected decks", stratiform_p10, stratiform_p90)
            assert type_p10 < 0.30 and type_p90 > 0.65, (
                "weather field must retain both stratiform and developed cloud families", type_p10, type_p90)
            assert adjacent_correlation > 0.95 and mesoscale_correlation > 0.6, (
                "cumulus occupancy must form coherent parent groups", adjacent_correlation, mesoscale_correlation)
            assert 0.1 < regional_correlation < 0.85, (
                "cumulus field must retain regional organization and local variation", regional_correlation)
            assert deck_adjacent_correlation > 0.98 and deck_meso_correlation > 0.75, (
                "stratiform decks must form broader connected structures",
                deck_adjacent_correlation, deck_meso_correlation)
            assert 0.1 < deck_regional_correlation < 0.85, (
                "stratiform field must retain regional organization and openings", deck_regional_correlation)
            assert 0.1 < family_correlation < 0.9, (
                "cloud families must be spatially related but not identical", family_correlation)
            if dump:
                dump.mkdir(parents=True, exist_ok=True)
                np.savez(dump / "cloud-weather-field.npz", cumulus=cumulus, stratiform=stratiform,
                         cloudType=cloud_type, cirrus=data[..., 3])
            print(f"PASS weather morphology: cumulus p10/p90 {cumulus_p10:.4f}/{cumulus_p90:.4f}, "
                  f"stratiform p10/p90 {stratiform_p10:.4f}/{stratiform_p90:.4f}, "
                  f"type p10/p90 {type_p10:.4f}/{type_p90:.4f}, "
                  f"cumulus adjacent/32px/128px correlation "
                  f"{adjacent_correlation:.4f}/{mesoscale_correlation:.4f}/{regional_correlation:.4f}, "
                  f"deck adjacent/32px/128px correlation "
                  f"{deck_adjacent_correlation:.4f}/{deck_meso_correlation:.4f}/{deck_regional_correlation:.4f}, "
                  f"family correlation {family_correlation:.4f}")
        if floating and name != "Weather":
            # The base volume stores each shape mean beside its second moment.
            mean_channels = list(range(0, data.shape[-1], 2))
            moment_channels = [channel + 1 for channel in mean_channels]
            np.testing.assert_allclose(data[..., moment_channels], data[..., mean_channels].astype(np.float32) ** 2,
                                       atol=np.finfo(np.float16).eps, rtol=0, err_msg="unfiltered shape moments")
        # Compare the production filter with independent reference box averages at every level.
        for mip in range(1, texture.mip_count):
            shape = []
            for extent in data.shape[:-1]:
                shape.extend([extent // 2, 2])
            expected = data.reshape(*shape, data.shape[-1]).astype(np.float32).mean(
                axis=tuple(range(1, 2 * dimensions, 2)))
            expected = expected.astype(np.float16) if floating else np.rint(expected).astype(np.uint8)
            source = texture.create_view(mip=mip - 1, mip_count=1, sampler=sampler)
            destination = texture.create_view(mip=mip, mip_count=1)
            extent = size >> mip
            filters[name].dispatch(thread_count=[extent, extent, extent if dimensions == 3 else 1],
                                   vars={"Source": source.descriptor_handle_combined, "Destination": destination})
            data = texture.to_numpy(mip=mip)
            # UNORM interpolation may round each axis before the final texture write.
            np.testing.assert_allclose(data, expected, atol=8e-4 if floating else 2,
                                       rtol=0, err_msg=f"{name} mip {mip}")
        textures[name] = texture.create_view(sampler=sampler)
    print("PASS production noise and mip filters: 2D and 3D reference averages")
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
    filtered_points[:, 3] = 128
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


def check_shape_phase(probe, textures):
    clouds = cloud_parameters(coverage=1.0, weather=1.0)
    scale = clouds["shapeScale"]
    period = cloud_weather_periods()[0] * scale
    rng = np.random.default_rng(87123)
    # Eight-meter coordinates keep the period shifts exactly representable in float32.
    horizontal = np.rint(rng.uniform(-2 * period, 2 * period, (2048, 2)) / 8) * 8
    points = np.column_stack([horizontal[:, 0], np.zeros(len(horizontal)),
                              horizontal[:, 1], np.zeros(len(horizontal))]).astype(np.float32)
    weather = {"Weather": textures["Weather"]}

    def phase(samples, parameters=clouds):
        return probe.run("columnPhases", samples, clouds=parameters, textures=weather)

    values = phase(points)
    assert np.isfinite(values).all() and np.all(values[:, 3] > 0)
    assert np.max(np.abs(values[:, :3])) <= 0.250001
    for offset in ([period, 0, 0, 0], [0, 0, period, 0], [-period, 0, period, 0]):
        np.testing.assert_allclose(phase(points + offset)[:, :3], values[:, :3], atol=1e-7, rtol=0,
                                   err_msg="shape phase must share the low-weather period")
    for controls in ({"coverage": 0.45, "weather": 0.0},
                     {"coverage": 0.8, "weather": 0.5, "thunder": 0.7, "night": 1.0}):
        changed = phase(points, clouds | controls)
        active = changed[:, 3] > 0
        assert np.any(active)
        np.testing.assert_array_equal(changed[active, :3], values[active, :3])
        np.testing.assert_array_equal(changed[~active, :3], 0)

    maximum_derivative = 0.0
    delta = 16.0
    for axis in (0, 2):
        offset = np.zeros(4)
        offset[axis] = delta
        derivative = (phase(points + offset)[:, :3] - phase(points - offset)[:, :3]) * (scale / (2 * delta))
        maximum_derivative = max(maximum_derivative, np.max(np.abs(derivative)))
    assert maximum_derivative <= 0.2345, ("shape phase exceeds its no-fold derivative bound", maximum_derivative)

    # Check both lattice and wrap boundaries, including negative coordinates.
    configuration = (SHADERS / "rtx/pass/cloud_noise.h").read_text()
    base_period_match = re.search(r"kCloudBasePeriod\s*=\s*(\d+)", configuration)
    base_period = int(base_period_match[1]) if base_period_match else 1
    edges = np.array([[cell * 4 * base_period * scale, 0, across, 0]
                      for cell in range(-33, 34) for across in (-7351.5, 2191.25, 18421.75)], np.float32)
    for axis in (0, 2):
        samples = edges if axis == 0 else edges[:, [2, 1, 0, 3]]
        offset = np.zeros(4)
        offset[axis] = 8
        before = phase(samples - offset)[:, :3]
        center = phase(samples)[:, :3]
        after = phase(samples + offset)[:, :3]
        np.testing.assert_allclose(before, after, atol=1e-6, rtol=0,
                                   err_msg="shape phase has a discontinuous cell boundary")
        slope_jump = (before + after - 2 * center) * (scale / 8)
        assert np.max(np.abs(slope_jump)) < 1e-4, "shape phase has a slope discontinuity"
    print(f"PASS low shape phase: bounds, UI independence, negative/wrapped cell continuity, "
          f"maximum sampled derivative {maximum_derivative:.6f}")


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
    weather = device.create_texture(type=spy.TextureType.texture_2d, format=spy.Format.rgba32_float,
                                    width=1, height=1, usage=spy.TextureUsage.shader_resource)
    weather.copy_from_numpy(np.zeros((1, 1, 4), np.float32))
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
            # A vertical ray has no horizontal span in the two-dimensional high
            # field. Layer thickness must not select a coarser horizontal mip.
            density = probe.run("densities", [[x, center_height, z, 0.0]],
                                clouds=column, textures=textures)[0, 0]
            # Smooth ramps over [0, .15] and [.65, 1] enclose area .75; the midpoint
            # lies on the unit plateau. This is an independent Beer-law column reference.
            optical_depth = float(density) * 0.75 * thickness * clouds["highExtinction"]
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


def high_column_reference(origin, direction, base, thickness, extinction, distance, radius):
    """Integrate a constant horizontal field between spherical profile knots."""
    origin = np.asarray(origin, np.float64)
    direction = np.asarray(direction, np.float64)
    point = origin + [0, radius, 0]
    projection = point @ direction
    boundaries = [0.0, distance]
    for altitude in (base, base + 0.15 * thickness, base + 0.65 * thickness, base + thickness):
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
        top = np.clip((height - 0.65) / 0.35, 0, 1)
        profile = bottom * bottom * (3 - 2 * bottom) * (1 - top * top * (3 - 2 * top))
        depth += 0.5 * (end - begin) * np.dot(weights, profile) * extinction
    return depth


def check_high_filtered_columns(probe, textures):
    weather = probe.device.create_texture(type=spy.TextureType.texture_2d, format=spy.Format.rgba32_float,
                                          width=1, height=1, mip_count=1, usage=spy.TextureUsage.shader_resource)
    weather.copy_from_numpy(np.ones((1, 1, 4), dtype=np.float32))
    sampler = probe.device.create_sampler(address_u=spy.TextureAddressingMode.wrap,
                                          address_v=spy.TextureAddressingMode.wrap)
    constant = {**textures, "Weather": weather.create_view(sampler=sampler)}
    radius = 6370937.0
    maximum_error = 0.0
    maximum_angular_delta = 0.0
    cases = 0
    for thickness in (50.0, 200.0):
        base = 6400.0 - 0.5 * thickness
        extinction = 0.005 / (0.75 * thickness)
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
                parameters = clouds | {"steps": steps, "viewAngularFootprint": footprint}
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
    for layer, base, height, extinction in ((0, 400.0, 540.0, 3e-4), (1, 6000.0, 6400.0, 2e-5)):
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
        change = np.max(np.abs(np.diff(values, axis=1)))
        assert change < 0.01, ("shadow grid jumps when a second shell interval appears", layer, change)
        maximum_change = max(maximum_change, change)
    print(f"PASS below-horizon shell tangencies: maximum transmission change {maximum_change:.6f}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dump", type=Path, help="Optional scratch directory for numerical diagnostics")
    args = parser.parse_args()
    device = spy.Device(type=spy.DeviceType.vulkan, compiler_options=OPTIONS)
    probe = Probe(device)
    check_morphology_configuration()
    check_homogeneous(probe)
    check_intervals(probe)
    check_layer_intervals(probe)
    check_light_profiles(probe)
    textures = cloud_textures(device, args.dump)
    check_density(probe, textures, args.dump)
    check_horizon_cloud_openings(probe, textures)
    check_low_wind_wrap(probe, textures)
    from test_cloud_morphology import check_family_periodicity, check_erosion_seams
    fixtures = cloud_parameters()
    period = max(cloud_weather_periods()) * fixtures["shapeScale"]
    check_family_periodicity(probe, textures, fixtures, period)
    check_erosion_seams(probe, textures, fixtures)
    check_shape_phase(probe, textures)
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
