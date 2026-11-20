# SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
# SPDX-License-Identifier: MIT
"""Read back bounded cloud view-ray grids and neural atlas coordinates."""
import argparse
import json
from pathlib import Path
import numpy as np
import slangpy as spy

from test_cloud_transport import SHADERS, cloud_parameters, cloud_phase_texture, cloud_weather_periods, weather_resolutions

HERE = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dump', type=Path)
    options = parser.parse_args()
    device = spy.Device(type=spy.DeviceType.vulkan, compiler_options={
        'include_paths': [str(HERE), str(SHADERS)], 'defines': {'__SLANG__': '1'}})
    sampler = device.create_sampler(min_filter=spy.TextureFilteringMode.linear,
        mag_filter=spy.TextureFilteringMode.linear, mip_filter=spy.TextureFilteringMode.point,
        address_u=spy.TextureAddressingMode.clamp_to_edge,
        address_v=spy.TextureAddressingMode.clamp_to_edge,
        address_w=spy.TextureAddressingMode.clamp_to_edge)
    def texture(data, fmt):
        tex = device.create_texture(type=spy.TextureType.texture_3d, format=fmt,
            width=data.shape[2], height=data.shape[1], depth=data.shape[0],
            usage=spy.TextureUsage.shader_resource)
        tex.copy_from_numpy(data)
        return tex.create_view(sampler=sampler)
    phase_table = cloud_phase_texture(device)
    programs = {}
    def run(name, origins, directions, args, neural=None):
        origins = np.asarray(origins, np.float32).reshape(-1, 4)
        directions = np.asarray(directions, np.float32).reshape(-1, 4)
        if name not in programs:
            programs[name] = device.create_compute_kernel(device.load_program('cloud_lighting_contracts.comp.slang', [name]))
        output = device.create_buffer(element_count=len(origins), struct_size=16,
            usage=spy.BufferUsage.unordered_access)
        bindings = {'Origins': device.create_buffer(data=origins, usage=spy.BufferUsage.shader_resource),
            'Directions': device.create_buffer(data=directions, usage=spy.BufferUsage.shader_resource),
            'Results': output, 'Count': len(origins), 'Clouds': args}
        if name == 'rayPhases':
            bindings['Phase'] = phase_table.descriptor_handle_combined
        if neural is not None:
            bindings['UnusedLighting'] = neural.descriptor_handle_combined
        programs[name].dispatch(thread_count=[len(origins), 1, 1], vars=bindings)
        return output.to_numpy().view(np.float32).reshape(-1, 4)

    args = cloud_parameters(baseHeight=800.0, thickness=1500.0, extinction=0.03,
        maxDistance=24000.0, highMaxDistance=24000.0)
    report = {'scope': 'Production cloud view-grid and neural atlas mapping.'}
    # Distinct values at every angular knot expose band/channel crossings,
    # including the shared pole values and the second light in the packed atlas.
    angular_requests, expected = [], []
    atlas = np.zeros((1, 8 * 16, 1, 4), np.float32)
    polar = np.linspace(0, 1, 6)
    mu = 1 - 2 * polar**3
    for light, light_up in enumerate((0.6, -1.0)):
        args['lights'][light]['directionToLight'] = [np.sqrt(1 - light_up**2), light_up, 0]
        spread = np.sqrt((1 - mu**2) * (1 - light_up**2))
        up = np.stack((mu * light_up - spread, mu * light_up + spread), axis=1)
        radiance = 1 + light + 0.2 * mu[:, None] + 0.3 * up
        for band, channels in enumerate(radiance.reshape(3, 4)):
            atlas[:, (light * 3 + band) * 16:(light * 3 + band + 1) * 16] = channels
        for slot in range(12):
            for t in np.linspace(0, 1, 21):
                cosine = 1 - 2 * t**3
                for az in (-1, 0, 1):
                    view_up = cosine * light_up + az * np.sqrt((1 - cosine**2) * (1 - light_up**2))
                    angular_requests.append([cosine, view_up, light, slot])
                    endpoints = (1 - az) * 0.5 * radiance[:, 0] + (1 + az) * 0.5 * radiance[:, 1]
                    expected.append([mu[slot // 2], up.flat[slot], np.interp(t, polar, endpoints)])
    actual = run('neuralAngles', angular_requests, np.zeros_like(angular_requests), args,
                 neural=texture(atlas, spy.Format.rgba32_float))
    np.testing.assert_allclose(actual[:, :3], expected, atol=5e-6, rtol=0)
    report['neuralAngularCases'] = len(actual)
    knots = np.arange(4, dtype=np.float32) / 3
    atlas[:, 96:112] = knots
    atlas[:, 112:128] = 1 - knots
    requests = [[up, down, multiple, strength] for up in (0, .25, 3, 40) for down in (0, 1, 20)
                for multiple in (0, .4, 1.3) for strength in (0, 1)]
    requests = np.repeat(requests, 4, axis=0)
    dirs = np.zeros_like(requests)
    dirs[:, 0] = np.arange(len(dirs)) % 4
    dirs[:, 1] = np.linspace(-1, 1, len(dirs))
    actual = run('skyResponse', requests, dirs, args, neural=texture(atlas, spy.Format.rgba32_float))
    assert (actual[:, :2] >= 0).all() and (actual[:, :2] <= 1.00001).all()
    np.testing.assert_allclose(actual[requests[:, 3] == 1, :2].sum(axis=1), 1, atol=2e-6)
    np.testing.assert_allclose(actual[:, 2], .5 * (1 + dirs[:, 1]), atol=2e-6)
    np.testing.assert_allclose(actual[:, 3], .5 * (1 - dirs[:, 1]), atol=2e-6)
    report['skyBoundaryCases'] = len(actual)
    # Bake coordinates and consumer UVs are inverse maps. Exercise all three
    # tiles, both signs of each axis, the blend zones and the far edge clamp.
    cells = np.array([[x, z, 0, 0] for z in range(20) for x in range(30)], np.float32)
    nodes = run('neuralNodes', cells, np.zeros_like(cells), args)
    for (x, z, _, _), node in zip(cells, nodes):
        if x < 20:
            radius = max(args["maxDistance"], args["lightMaxDistance"], 4.0 * args["shapeScale"])
            expected_xz = (np.array([x, z]) - 9.5) * (2.0 * radius / 20.0)
        else:
            expected_xz = (np.array([x - 20, z % 10]) - 4.5) * (128 if z < 10 else 512)
        np.testing.assert_allclose(node[[0, 2]], expected_xz, atol=1e-3, rtol=0)
    atlas = np.full((20, 128, 30, 4), 0.875, np.float32)
    atlas[:10, :, 20:] = 0.125
    atlas[10:, :, 20:] = 0.5
    positions, expected = [], []
    for distance, value in ((0, .125), (480, .125), (560, .3125), (640, .5),
                            (1920, .5), (2240, .6875), (2560, .875), (50000, .875)):
        for x, z in ((distance, 0), (-distance, 0), (0, distance), (0, -distance)):
            positions.append([x, 1000, z, 0])
            expected.append(value)
    actual = run('neuralAngles', np.zeros_like(positions), positions, args,
                 neural=texture(atlas, spy.Format.rgba32_float))
    np.testing.assert_allclose(actual[:, 2], expected, atol=2e-6, rtol=0)
    report['neuralAtlasCases'] = len(nodes) + len(positions)
    grid_inputs = [[start, end, spacing, angle] for start, end in ((0, 24000), (800, 24000), (20000, 24000),
                   (800, 1000), (0, 96000)) for spacing in (1, 8, 16, 128) for angle in (0, 0.0013)]
    for budget in (96, 160, 256, 384):
        grids = run('grids', grid_inputs, [[budget, 0, 0, 0]] * len(grid_inputs), args)
        assert np.all(grids[:, 0] <= budget) and np.all(grids[:, 0] >= 1)
        assert np.all(grids[:, 2] == 0), grids[grids[:, 2] != 0]
        np.testing.assert_allclose(grids[:, 1], np.array(grid_inputs)[:, 1], atol=0.01)
    report['gridCases'] = 4 * len(grids)
    report['maximumCloudCells'] = int(grids[:, 0].max())

    view_args = args | {'highThickness': 50.0, 'steps': 40, 'highSteps': 12}
    intervals = [[800, 6800, 0, 0], [800, 2300, 0, 0], [6400, 6450, 0, 0], [8000, 11000, 0, 0]]
    directions = [[1, 0, 0, 0], [0, 1, 0, 0], [0, 1, 0, 1], [1, 0, 0, 1]]
    view_grids = run('viewGrids', intervals, directions, view_args)
    np.testing.assert_array_equal(view_grids[:, 0], [40, 40, 1, 12])
    np.testing.assert_array_equal(view_grids[:, 2], [6800, 2300, 6450, 11000])
    assert view_grids[2, 3] == 6425
    report['layerViewCells'] = view_grids[:, 0].astype(int).tolist()

    higher_low = run('viewGrids', intervals, directions, view_args | {'steps': 64})
    higher_high = run('viewGrids', intervals, directions, view_args | {'highSteps': 20})
    np.testing.assert_array_equal(higher_low[:, 0], [64, 64, 1, 12])
    np.testing.assert_array_equal(higher_high[:, 0], [40, 40, 1, 20])
    np.testing.assert_array_equal(higher_low[2:], view_grids[2:])
    np.testing.assert_array_equal(higher_high[:3], view_grids[:3])

    # Grazing rays cover the full configured range with the same budget as
    # shorter rays. The last cell must close the interval, including for PSR.
    grazing = [[start, distance, cone, 0] for start in (0, 2500)
               for distance in (24000, 96000, 160000) for cone in (0, 0.001)]
    grazing_grids = run('viewGrids', grazing, [[1, 0, 0, 0]] * len(grazing), view_args)
    np.testing.assert_array_equal(grazing_grids[:, 0], 40)
    np.testing.assert_array_equal(grazing_grids[:, 2], np.array(grazing)[:, 1])
    first_span = 2 * (grazing_grids[:, 3] - np.array(grazing)[:, 0])
    last_span = grazing_grids[:, 2] - grazing_grids[:, 1]
    assert np.all((first_span > 0) & (last_span > first_span))
    report['grazingViewCases'] = len(grazing)

    # Extending the interval past a cell boundary leaves the other samples fixed.
    edge = 1825 + 32 * view_args['shapeScale'] / view_args['steps']
    ends = np.array([edge + .002, edge - .002], dtype=np.float32)
    threshold_grids = run('viewGrids', [[1825, end, 0, 16] for end in ends],
                          [[1, 0, 0, 0]] * 2, view_args)
    np.testing.assert_array_equal(threshold_grids[:, 0], [33, 32])
    np.testing.assert_array_equal(threshold_grids[:, 2], ends)
    sample_delta = abs(threshold_grids[0, 3] - threshold_grids[1, 3])
    extra_span = threshold_grids[0, 2] - threshold_grids[0, 1]
    assert sample_delta < 0.05 and 0 < extra_span < 0.1, threshold_grids
    report['integerBudgetCrossing'] = {'interiorSampleDelta': float(sample_delta), 'extraCellSpan': float(extra_span)}

    # Test both the weather texel and the pixel cone as the limiting footprint.
    # Moving across a high-cloud threshold must not resample the existing lit cells.
    high_crossings = []
    high_texel = view_args['shapeScale'] * cloud_weather_periods()[1] / weather_resolutions()[1]
    for cone in (0.0, 0.01):
        start = 12800.0
        spacing = max(high_texel, cone * start) / 0.8
        for cells in (1, 2, 3):
            edge = start + cells * spacing
            ends = np.array([edge - .01, edge + .01], dtype=np.float32)
            grids = run('viewGrids', [[start, end, cone, 0] for end in ends],
                        [[0.8, 0.6, 0, 1]] * 2, view_args)
            np.testing.assert_array_equal(grids[:, 0], [cells, cells + 1])
            np.testing.assert_array_equal(grids[:, 2], ends)
            first_sample_delta = abs(grids[0, 3] - grids[1, 3])
            added_span = grids[1, 2] - grids[1, 1]
            assert first_sample_delta < 0.01 and 0 < added_span < 0.02, grids
            high_crossings.append(float(first_sample_delta))
    report['highCloudBudgetCrossings'] = {
        'cases': len(high_crossings), 'maximumFirstSampleDelta': max(high_crossings)}

    # Compare the exact runtime sampler and the stored values at texel centres.
    # Endpoints cover forward/backscatter and the interior samples catch axis warp errors.
    asset = np.fromfile(SHADERS.parent / "rtx_render/clouds/revelation_phase.bin", "<f2").reshape(6, 512)
    indices = np.r_[0, 1, 3, 9, 24, 65, 128, 256, 383, 510, 511]
    u = indices / 511
    centered = 2 * .84 * u / (1 - .16 * u) - 1
    cosine = np.cos(np.pi * (.5 + .99 * centered / (1 + .98 * abs(centered))))
    args["lights"][0]["directionToLight"] = [1, 0, 0]
    origins = np.column_stack((cosine, np.zeros((len(cosine), 3))))
    actual = run('rayPhases', origins, np.zeros_like(origins), args)
    expected = np.exp2(asset[[3, 5, 0, 2]][:, indices].astype(np.float64)).T
    expected[:, 2:] /= [.9995592, .99963014]
    np.testing.assert_allclose(actual, expected, rtol=0.008, atol=1e-5)
    report['phaseLutTexels'] = len(indices) * 4

    if options.dump:
        options.dump.mkdir(parents=True, exist_ok=True)
        (options.dump / 'cloud-lighting-contracts.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
