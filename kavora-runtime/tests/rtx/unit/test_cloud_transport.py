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
        "multipleScattering": 0.8, "groundReference": [0, 0, 0], "planetRadius": 6360000.0,
        "wetness": 0.0, "meterToWorld": 1.0, "highMapOrigin": [0., 0.], "highLookupOrigin": [0., 0.],
        "lightMaxDistance": 60000.0, "previousWindDelta": [0, 0, 0],
        "viewAngularFootprint": 0.0, "highBaseHeight": 6000.0,
        "highThickness": 900.0, "highExtinction": 0.0, "highCoverage": 0.4,
        "highMaxDistance": 60000.0, "groundAlbedo": 0.18,
        "lights": [{"directionToLight": [1, 0, 0], "irradiance": [1, 1, 1]},
                   {"directionToLight": [-1, 0, 0], "irradiance": [0, 0, 0]}],
    }
    result.update(changes)
    return result


def cloud_phase_texture(device, shaders=SHADERS):
    data = np.fromfile(shaders.parent / "rtx_render/clouds/revelation_phase.bin", "<f2").reshape(6, 512)
    texture = device.create_texture(type=spy.TextureType.texture_2d, format=spy.Format.r16_float,
        width=512, height=6, mip_count=1, usage=spy.TextureUsage.shader_resource, data=data)
    sampler = device.create_sampler(min_filter=spy.TextureFilteringMode.linear, mag_filter=spy.TextureFilteringMode.linear,
        address_u=spy.TextureAddressingMode.clamp_to_edge, address_v=spy.TextureAddressingMode.clamp_to_edge)
    return texture.create_view(sampler=sampler)


def cloud_weather_periods():
    # Nominal low weather period and period of the fine cirrus map.
    return 64, 20



class Probe:
    def __init__(self, device):
        self.device = device
        self.kernels = {}
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
        self.kernels[entry].dispatch(thread_count=[len(data), 1, 1], vars=bindings)
        return output.to_numpy().view(np.float32).reshape(-1, 4)


def check_pixel_samples(probe):
    inputs = [(x, 37, frame, 0) for x in range(3) for frame in range(16)]
    offsets = probe.run("pixelSample", inputs)[:, 0].reshape(3, 16)
    assert np.all((offsets >= 0) & (offsets < 1))
    for samples in offsets:
        np.testing.assert_array_equal(np.sort((samples * 16).astype(int)), np.arange(16))
    assert not np.array_equal(offsets[0], offsets[1])
    print("PASS pixel samples cover every quadrature stratum with distinct pixel scrambles")


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
    reduced_depth = 1 - 0.8524832
    below = np.array([0, 6, 8, 0]) * reduced_depth
    weights = (d + below) / (2 * d + 8 * reduced_depth)
    np.testing.assert_allclose(actual, weights[:, None] * spectrum, atol=2e-5)
    # The gap sees one column of each material. A perfectly reflecting
    # floor returns the full field.
    gap = probe.run("airSkylight", [origins[2]], [spectrum],
                    cloud_parameters(groundAlbedo=0, highExtinction=.01), {"VerticalDepth": view})
    ice_depth = 1 - 0.77133145
    gap_weight = (d + 8 * reduced_depth) / (2 * d + 8 * (reduced_depth + ice_depth))
    np.testing.assert_allclose(gap[0], gap_weight * spectrum, atol=2e-5)
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


def cloud_mips(data, spatial_dims, levels):
    for level in range(levels):
        yield level, data
        if level + 1 < levels:
            shape = tuple(dim for side in data.shape[:spatial_dims] for dim in (side // 2, 2))
            shape += data.shape[spatial_dims:]
            data = np.rint(data.reshape(shape).mean(axis=tuple(range(1, 2 * spatial_dims, 2)))).astype(data.dtype)


def cloud_textures(device, dump=None):
    sampler = device.create_sampler(min_filter=spy.TextureFilteringMode.linear,
                                    mag_filter=spy.TextureFilteringMode.linear, mip_filter=spy.TextureFilteringMode.linear,
                                    address_u=spy.TextureAddressingMode.wrap, address_v=spy.TextureAddressingMode.wrap,
                                    address_w=spy.TextureAddressingMode.wrap)
    textures = {}
    for name, side, channels, fmt in (("Base", 128, 1, spy.Format.rg16_unorm),
                                      ("Detail", 64, 4, spy.Format.rgba8_unorm)):
        data = np.fromfile(CLOUD_ASSETS / TEXTURE_FILES[name], dtype=np.uint8)
        levels = side.bit_length()
        tex = device.create_texture(type=spy.TextureType.texture_3d, format=fmt,
                                    width=side, height=side, depth=side, mip_count=levels,
                                    usage=spy.TextureUsage.shader_resource)
        shape = (side, side, side) if channels == 1 else (side, side, side, channels)
        data = data.reshape(shape)
        if name == "Base":
            value = data.astype(np.uint32)
            data = np.stack((value * 257, (value * value * 257 + 127) // 255), axis=-1).astype(np.uint16)
        for mip, pixels in cloud_mips(data, 3, levels):
            tex.copy_from_numpy(pixels, mip=mip)
        textures[name] = tex.create_view(sampler=sampler)

    weather = device.create_texture(type=spy.TextureType.texture_2d_array, format=spy.Format.rgba8_unorm,
                                    width=1024, height=1024, array_length=3, mip_count=6,
                                    usage=spy.TextureUsage.shader_resource)
    raw = np.fromfile(CLOUD_ASSETS / TEXTURE_FILES["Weather"], dtype=np.uint8)
    offset = 0
    sources = {}
    for layer, mip in ((0, 0), (2, 2), (2, 5), (2, 1), (1, 1), (1, 0)):
        side = 1024 >> mip
        size = side * side * 4
        sources[layer, mip] = raw[offset:offset + size].reshape(side, side, 4).copy()
        offset += size
    assert offset == len(raw)
    curl = sources.pop((1, 1))[:, :, :2]
    sources[0, 0][:514, :514, 2:] = np.pad(curl, ((1, 1), (1, 1), (0, 0)), mode="wrap")
    for layer in (0, 1):
        for mip, pixels in cloud_mips(sources.pop((layer, 0)), 2, 6):
            weather.copy_from_numpy(pixels, layer=layer, mip=mip)
    for (layer, mip), pixels in sources.items():
        weather.copy_from_numpy(pixels, layer=layer, mip=mip)
    textures["Weather"] = weather.create_view(sampler=sampler)
    return textures


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
    clouds = cloud_parameters(baseHeight=800.0, coverage=.85, wetness=1., extinction=.03)
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
    # Split within the same full shell. The two pieces must use the original grid.
    midpoint = .5 * (entry + exit_distance)
    middle = transmission(midpoint, 24000., .00003)
    assert np.any((middle > .05) & (middle < .95))
    rays = np.tile([0., y, 0., exit_distance + 10.], (len(angle), 1))
    directions[:, 3] = midpoint
    split = probe.run("viewTransmissionSplits", rays, directions,
                      clouds | {"maxDistance": 24000., "extinction": .00003}, textures)
    np.testing.assert_allclose(split[:, 0], middle, atol=2e-5)
    np.testing.assert_allclose(transmission(exit_distance + 10., 24000., .00003),
                               split[:, 0] * split[:, 1], atol=2e-5)
    print(f"PASS storm horizon at {entry / 1000:.1f} km: foreground is clear, "
          f"median cloud transmission {np.median(full):.5f} at both detail ranges")


def check_upper_deck_illumination(probe, textures):
    # Separate sun and moon fluxes. A unit upper-boundary response and a black
    # ground isolate the interface from the network and the clear-sky source.
    from cloud_lighting_preview import build_lighting_cache
    clouds = cloud_parameters(highExtinction=.02, groundAlbedo=0, multipleScattering=1,
                              activeCloudLights=0)
    clouds['lights'] = [dict(directionToLight=[0, 1, 0], irradiance=rgb)
                        for rgb in ([2, 0, 0], [0, 0, 3])]
    _, resources, _ = build_lighting_cache(probe.device, clouds, textures, SHADERS)
    local = resources[0]
    packed = np.zeros_like(local.to_numpy())
    packed[:, -1, :, :] = [.3, .1]
    local.copy_from_numpy(packed)
    neural = probe.device.create_texture(type=spy.TextureType.texture_3d, format=spy.Format.rgba32_float,
        width=1, height=1, depth=1, usage=spy.TextureUsage.shader_resource,
        data=np.ones((1, 1, 1, 4), np.float32))
    views = dict(textures, VerticalDepth=resources[1], Neural=neural.create_view(sampler=probe.device.create_sampler()))
    points = [[0, 900, 0, 0], [0, 6500, 0, 1]]
    for strength in (0., .5, 1.):
        rgb = probe.run("cloudBoundaryLight", points, clouds=clouds | {'multipleScattering': strength}, textures=views)
        expected = np.array([[.6, 0, .3], [0, 0, 0]]) * (strength / np.pi)
        np.testing.assert_allclose(rgb[:, :3], expected, atol=5e-5)
    print("PASS upper deck illuminates the lower sky boundary, both lights and the single-scattering limit")


def check_distant_cloud_sequence(probe, textures):
    # The midpoint toggles between cloud and a gap on the first two sightlines.
    # The third crosses a threshold with sparse unfiltered cells.
    elapsed = np.linspace(0, .783, 48)
    start_angles = np.array([28., 53., 284.])
    yaw = np.radians(np.ravel(start_angles[:, None] + .05 * elapsed / elapsed[-1]))
    elevation = np.radians(3.)
    rays = np.column_stack((np.sin(yaw) * np.cos(elevation), np.full(len(yaw), np.sin(elevation)),
                            np.cos(yaw) * np.cos(elevation), np.tile(elapsed, len(start_angles))))
    clouds = cloud_parameters(baseHeight=1244., thickness=2000., shapeScale=1000., extinction=.06,
                              coverage=.5, maxDistance=24000., viewAngularFootprint=.0026, steps=64)
    transport = probe.run("distantCloudSequence", np.tile([0., 128., 0., 0.], (len(yaw), 1)), rays,
                          clouds, textures).reshape(len(start_angles), 48, 4)
    assert np.all(np.max(np.abs(np.diff(transport[:2, :, 1], axis=1)), axis=1) > .95)
    np.testing.assert_allclose(transport[:2, :, 0], transport[:2, :, 2], atol=1e-4)
    np.testing.assert_allclose(transport[2, :, 0], transport[2, :, 2], atol=3e-3)
    assert np.max(np.abs(np.diff(transport[:, :, 0], axis=1))) < .005
    print("PASS nonuniform distant clouds during a 48-frame camera turn with wind")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dump", type=Path, help="Optional scratch directory for numerical diagnostics")
    args = parser.parse_args()
    device = spy.Device(type=spy.DeviceType.vulkan, compiler_options=OPTIONS)
    probe = Probe(device)
    check_pixel_samples(probe)
    check_air_skylight(probe)
    check_weather_column_coordinates(probe)
    check_homogeneous(probe)
    check_intervals(probe)
    check_layer_intervals(probe)
    check_light_profiles(probe)
    textures = cloud_textures(device, args.dump)
    check_upper_deck_illumination(probe, textures)
    uniform = uniform_cloud_textures(device)
    check_distant_storm_horizon(probe, uniform)
    check_distant_cloud_sequence(probe, textures)
    check_high_column_transmission(probe, textures)


if __name__ == "__main__":
    main()
