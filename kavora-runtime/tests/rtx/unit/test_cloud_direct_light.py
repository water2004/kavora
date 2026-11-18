# SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors
# SPDX-License-Identifier: MIT
"""Read back direct-light cascade, slice, and channel selection on Vulkan."""
from pathlib import Path

import numpy as np
import slangpy as spy

from test_cloud_transport import SHADERS, cloud_parameters


def main():
    here = Path(__file__).resolve().parent
    device = spy.Device(type=spy.DeviceType.vulkan, compiler_options={
        'include_paths': [str(here), str(SHADERS)], 'defines': {'__SLANG__': '1'}})
    sampler = device.create_sampler(min_filter=spy.TextureFilteringMode.linear,
        mag_filter=spy.TextureFilteringMode.linear, mip_filter=spy.TextureFilteringMode.point,
        address_u=spy.TextureAddressingMode.clamp_to_edge,
        address_v=spy.TextureAddressingMode.clamp_to_edge,
        address_w=spy.TextureAddressingMode.clamp_to_edge)
    args = cloud_parameters(baseHeight=800, thickness=1500, shapeScale=1024,
        windOffset=[17, 0, -31], highBaseHeight=6400, highThickness=50)
    # Distinct channels at every layer and cascade edge expose filtering into adjacent slices.
    values = np.array([[.25, .375, .5, .625], [.5, .625, .75, .875],
                       [.375, .5, .625, .75], [.125, .25, .375, .5]], dtype=np.float16)
    slices = np.repeat(values, (16, 12, 4, 4), axis=0)
    samples = np.broadcast_to(slices[None, :, None, :], (64, 36, 64, 4)).copy()
    field = device.create_texture(type=spy.TextureType.texture_3d, format=spy.Format.rgba16_float,
        width=64, height=36, depth=64, usage=spy.TextureUsage.shader_resource)
    field.copy_from_numpy(samples)
    view = field.create_view(sampler=sampler)

    origins, requests, expected = [], [], []
    clear = np.zeros(4)
    for layer, altitudes in enumerate(((800, 2300), (6400, 6450))):
        near, far = values[2 * layer:2 * layer + 2]
        for altitude in altitudes:
            for x, distance, channels, weight in (
                    (0, 9000, near, 1), (1792, 9000, (near + far) / 2, 1), (4096, 9000, far, 1),
                    (7168, 9000, far, .5), (8192, 9000, clear, 0), (0, 100, clear, 0)):
                for channel, transmitted in enumerate(channels):
                    origins.append([17 + x, altitude, -31, distance])
                    requests.append([altitude, layer, channel, 0])
                    expected.append([transmitted, weight])

    program = device.load_program('cloud_direct_light_contracts.comp.slang', ['directLight'])
    kernel = device.create_compute_kernel(program)
    output = device.create_buffer(element_count=len(origins), struct_size=16,
        usage=spy.BufferUsage.unordered_access)
    kernel.dispatch(thread_count=[len(origins), 1, 1], vars={
        'Origins': device.create_buffer(data=np.array(origins, dtype=np.float32), usage=spy.BufferUsage.shader_resource),
        'Directions': device.create_buffer(data=np.array(requests, dtype=np.float32), usage=spy.BufferUsage.shader_resource),
        'Results': output, 'Count': len(origins), 'Clouds': args, 'Direct': view.descriptor_handle_combined})
    actual = output.to_numpy().view(np.float32).reshape(-1, 4)
    np.testing.assert_allclose(actual[:, :2], expected, rtol=0, atol=2e-6)
    print(f'PASS {len(actual)} direct-light cases')


if __name__ == '__main__':
    main()
