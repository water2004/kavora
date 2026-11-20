# SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors
# SPDX-License-Identifier: MIT
"""Build the production vertical and neural cloud fields for offline previews."""
import re
import time

import numpy as np
import slangpy as spy


def build_lighting_cache(device, clouds, textures, shaders, lighting_enabled=True, warmup=False, repeats=1,
                         include_neural=False):
    layout = (shaders / 'rtx/pass/clouds/cloud_lighting_cache.h').read_text()
    neural_layout = (shaders / 'rtx/pass/clouds/cloud_neural_scattering.h').read_text()

    def constant(source, name):
        return int(re.search(rf'^#define {name}\s+(\d+)$', source, re.M).group(1))

    low = clouds['extinction'] > 0 and clouds['thickness'] > 0
    high = clouds['highCoverage'] > 0 and clouds['highExtinction'] > 0 and clouds['highThickness'] > 0
    active = bool(lighting_enabled and clouds['enable'] and (low or high))
    usage = spy.TextureUsage.shader_resource | spy.TextureUsage.unordered_access
    bindings = {'Clouds': clouds, **{name: value.descriptor_handle_combined for name, value in textures.items()}}
    resources = []
    result = {}
    volumes = []

    def bake(name, extent, threads, channels, source, entry, inputs, address_xz):
        width, height, depth = extent if active else (1, 1, 1)
        fmt = spy.Format.rg16_float if channels == 2 else spy.Format.rgba16_float
        texture = device.create_texture(type=spy.TextureType.texture_3d, format=fmt,
            width=width, height=height, depth=depth, mip_count=1, usage=usage)
        samples = []
        if active:
            kernel = device.create_compute_kernel(device.load_program(source, [entry]))
            vars = {**bindings, **inputs, name + 'Output': texture}
            if warmup:
                kernel.dispatch(thread_count=threads, vars=vars)
                device.wait_for_idle()
            for _ in range(repeats):
                started = time.monotonic()
                kernel.dispatch(thread_count=threads, vars=vars)
                device.wait_for_idle()
                samples.append(time.monotonic() - started)
        else:
            texture.copy_from_numpy(np.zeros((1, 1, 1, channels), np.float16))
        sampler = device.create_sampler(min_filter=spy.TextureFilteringMode.linear,
            mag_filter=spy.TextureFilteringMode.linear, address_u=address_xz,
            address_v=spy.TextureAddressingMode.clamp_to_edge, address_w=address_xz)
        view = texture.create_view(sampler=sampler)
        resources.extend((texture, view))
        result[name] = view.descriptor_handle_combined
        volumes.append({'name': name, 'extent': [width, height, depth], 'active': active,
                        'bytes': width * height * depth * channels * 2,
                        'dispatchWaitSeconds': float(np.median(samples)) if samples else 0.0,
                        'dispatchWaitSamplesSeconds': samples, 'warmedUp': active and warmup})

    resolution = constant(layout, 'CLOUD_LOCAL_LIGHT_RESOLUTION')
    height = constant(layout, 'CLOUD_LOCAL_LIGHT_HEIGHT_SAMPLES') + constant(layout, 'CLOUD_LOCAL_LIGHT_HIGH_HEIGHT_SAMPLES')
    bake('CloudLocalLight', (resolution, height, resolution), (resolution, resolution, 2 if high else 1), 2,
         'cloud_lighting_preview.comp.slang', 'buildLocalLight', {}, spy.TextureAddressingMode.wrap)
    if include_neural:
        tile = constant(neural_layout, 'CLOUD_NEURAL_TILE_SIZE')
        rows = constant(neural_layout, 'CLOUD_NEURAL_HEIGHT') + constant(neural_layout, 'CLOUD_NEURAL_HIGH_HEIGHT')
        inputs = dict(result)
        if active:
            data = np.fromfile(shaders.parent / 'rtx_render/clouds/cloud_neural_lighting.bin', dtype='<f4')
            inputs['NeuralCloudWeights'] = device.create_buffer(data=data, struct_size=4,
                usage=spy.BufferUsage.shader_resource)
        bake('CloudNeuralLight', (3 * tile, 8 * rows, 2 * tile), (24 * tile, 30 * rows, 2 * tile), 4,
             'cloud_neural_preview.comp.slang', 'main', inputs, spy.TextureAddressingMode.clamp_to_edge)

    return result, resources, {
        'resolution': resolution,
        'volumes': volumes,
        'bytes': sum(volume['bytes'] for volume in volumes),
        'dispatchWaitSeconds': sum(volume['dispatchWaitSeconds'] for volume in volumes),
        'scope': 'Vulkan dispatch and host wait.',
    }
