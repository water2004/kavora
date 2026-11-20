# SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors
# SPDX-License-Identifier: MIT
"""Build the production cloud lighting fields for offline ray consumers."""
import re
import time

import numpy as np
import slangpy as spy


def build_lighting_cache(device, clouds, textures, shaders, lighting_enabled=True, warmup=False, repeats=1,
                         include_local=False, include_neural=False):
    source = (shaders / 'rtx/pass/clouds/cloud_lighting_cache.h').read_text()

    def constant(name):
        return int(re.search(rf'^#define {name}\s+(\d+)$', source, re.M).group(1))

    quality = np.clip(clouds['lightSteps'], constant('CLOUD_LIGHTING_MIN_QUALITY'),
                      constant('CLOUD_LIGHTING_MAX_QUALITY'))
    resolution = int(quality) * constant('CLOUD_LIGHTING_RESOLUTION_PER_STEP')
    light_height = resolution // constant('CLOUD_LIGHTING_LIGHT_HEIGHT_DIVISOR')
    near_distance = clouds.get('lightingNearDistance', 0.0)
    packed_depth = resolution + (resolution // 2 if 0 < near_distance < clouds['lightMaxDistance'] else 0)
    column_sampler = device.create_sampler(min_filter=spy.TextureFilteringMode.linear,
        mag_filter=spy.TextureFilteringMode.linear, address_u=spy.TextureAddressingMode.wrap,
        address_v=spy.TextureAddressingMode.clamp_to_edge, address_w=spy.TextureAddressingMode.wrap)
    sampler = device.create_sampler(min_filter=spy.TextureFilteringMode.linear,
                                    mag_filter=spy.TextureFilteringMode.linear,
                                    address_u=spy.TextureAddressingMode.clamp_to_edge,
                                    address_v=spy.TextureAddressingMode.clamp_to_edge,
                                    address_w=spy.TextureAddressingMode.clamp_to_edge)
    usage = spy.TextureUsage.shader_resource | spy.TextureUsage.unordered_access
    bindings = {'Clouds': clouds,
                **{name: value.descriptor_handle_combined for name, value in textures.items()}}
    resources = []
    result = {}
    measurements = {'resolution': resolution, 'volumes': []}
    enabled = lighting_enabled and clouds['enable'] and clouds['extinction'] > 0 and clouds['thickness'] > 0
    light_program = device.load_program('cloud_lighting_preview.comp.slang', ['buildLight'])
    light_kernel = device.create_compute_kernel(light_program)
    for index, name in enumerate(('CloudSunDepth', 'CloudMoonDepth')):
        light = clouds['lights'][index]
        active = enabled and (max(light['irradiance']) > 0
                              or (light.get('hasRadianceProfile', 0)
                                  and np.max(np.array(light['radianceProfile'])[:, :3]) > 0))
        width, height, depth = ((resolution, light_height, packed_depth)
                                if active else (1, 1, 1))
        texture = device.create_texture(type=spy.TextureType.texture_3d,
                                        format=spy.Format.r32_float,
                                        width=width, height=height, depth=depth, mip_count=1, usage=usage)
        if not active:
            texture.copy_from_numpy(np.zeros((1, 1, 1), np.float32))
        count = [resolution, light_height, 1]
        output_binding = {'LightIndex': index, 'LightOutput': texture}
        if active and warmup:
            light_kernel.dispatch(thread_count=count, vars={**bindings, **output_binding})
            device.wait_for_idle()
        samples = []
        for _ in range(repeats):
            started = time.monotonic()
            if active:
                light_kernel.dispatch(thread_count=count, vars={**bindings, **output_binding})
            device.wait_for_idle()
            samples.append(time.monotonic() - started)
        elapsed = float(np.median(samples))
        view = texture.create_view(sampler=sampler)
        resources.extend((texture, view))
        result[name] = view.descriptor_handle_combined
        measurements['volumes'].append({'name': name, 'extent': [width, height, depth], 'active': bool(active),
                                       'bytes': width * height * depth * 4,
                                       'dispatchWaitSeconds': elapsed, 'dispatchWaitSamplesSeconds': samples,
                                       'warmedUp': bool(warmup)})
    if include_local:
        high = clouds['highCoverage'] > 0 and clouds['highExtinction'] > 0 and clouds['highThickness'] > 0
        active = lighting_enabled and clouds['enable'] and (enabled or high)
        local_resolution = constant('CLOUD_LOCAL_LIGHT_RESOLUTION')
        local_height = constant('CLOUD_LOCAL_LIGHT_HEIGHT_SAMPLES') + constant('CLOUD_LOCAL_LIGHT_HIGH_HEIGHT_SAMPLES')
        local_extent = [local_resolution, local_height, local_resolution]
        fields = [('LocalLight', local_extent, [local_resolution, local_resolution, 2 if high else 1], 2)]
        if 'CLOUD_DIRECT_LIGHT_RESOLUTION' in source:
            resolution = constant('CLOUD_DIRECT_LIGHT_RESOLUTION')
            levels = ('NEAR', 'MIDDLE', 'FAR') if 'CLOUD_DIRECT_LIGHT_LOW_MIDDLE_SAMPLES' in source else ('NEAR', 'FAR')
            height = sum(constant(f'CLOUD_DIRECT_LIGHT_LOW_{level}_SAMPLES') for level in levels)
            height += len(levels) * constant('CLOUD_DIRECT_LIGHT_HIGH_SAMPLES')
            extent = [resolution, height, resolution]
            fields.append(('DirectLight', extent, extent, 4))
        for field, extent, threads, channels in fields:
            width, height, depth = extent if active else [1, 1, 1]
            format = spy.Format.rg16_float if channels == 2 else spy.Format.rgba16_float
            texture = device.create_texture(type=spy.TextureType.texture_3d, format=format,
                                            width=width, height=height, depth=depth, mip_count=1, usage=usage)
            samples = []
            if active:
                program = device.load_program('cloud_lighting_preview.comp.slang', ['build' + field])
                kernel = device.create_compute_kernel(program)
                field_bindings = {**bindings, **result, field + 'Output': texture}
                if warmup:
                    kernel.dispatch(thread_count=threads, vars=field_bindings)
                    device.wait_for_idle()
                for _ in range(repeats):
                    started = time.monotonic()
                    kernel.dispatch(thread_count=threads, vars=field_bindings)
                    device.wait_for_idle()
                    samples.append(time.monotonic() - started)
            else:
                texture.copy_from_numpy(np.zeros((1, 1, 1, channels), np.float16))
            view = texture.create_view(sampler=column_sampler if field == 'LocalLight' else sampler)
            resources.extend((texture, view))
            name = 'Cloud' + field
            result[name] = view.descriptor_handle_combined
            measurements['volumes'].append({'name': name, 'extent': [width, height, depth], 'active': bool(active),
                                           'bytes': width * height * depth * channels * 2,
                                           'dispatchWaitSeconds': float(np.median(samples)) if samples else 0.0,
                                           'dispatchWaitSamplesSeconds': samples, 'warmedUp': active and warmup})
        neural_layout = shaders / 'rtx/pass/clouds/cloud_neural_scattering.h'
        if include_neural:
            source = neural_layout.read_text()
            tile = constant('CLOUD_NEURAL_TILE_SIZE')
            rows = constant('CLOUD_NEURAL_HEIGHT') + constant('CLOUD_NEURAL_HIGH_HEIGHT')
            width, height, depth = (3 * tile, 8 * rows, 2 * tile) if active else (1, 1, 1)
            texture = device.create_texture(type=spy.TextureType.texture_3d, format=spy.Format.rgba16_float,
                width=width, height=height, depth=depth, usage=usage)
            seconds = 0
            if active:
                data = np.fromfile(shaders.parent / 'rtx_render/clouds/cloud_neural_lighting.bin', dtype='<f4')
                weights = device.create_buffer(data=data, struct_size=4, usage=spy.BufferUsage.shader_resource)
                kernel = device.create_compute_kernel(device.load_program('cloud_neural_preview.comp.slang', ['main']))
                inputs = {**bindings, **result}
                inputs['DirectLightInput'] = inputs.pop('CloudDirectLight')
                started = time.monotonic()
                kernel.dispatch(thread_count=[width * 8, 3 * rows * 10, depth], vars={
                    **inputs, 'NeuralCloudOutput': texture, 'NeuralCloudWeights': weights})
                device.wait_for_idle()
                seconds = time.monotonic() - started
            else:
                texture.copy_from_numpy(np.zeros((1, 1, 1, 4), np.float16))
            view = texture.create_view(sampler=sampler)
            result['CloudNeuralLight'] = view.descriptor_handle_combined
            resources.extend((texture, view))
            measurements['volumes'].append({'name': 'CloudNeuralLight', 'extent': [width, height, depth],
                'active': bool(active), 'bytes': width * height * depth * 8, 'dispatchWaitSeconds': seconds})
    measurements['bytes'] = sum(v['bytes'] for v in measurements['volumes'])
    measurements['dispatchWaitSeconds'] = sum(v['dispatchWaitSeconds'] for v in measurements['volumes'])
    measurements['scope'] = 'Software or hardware Vulkan dispatch and host wait; not a game frame time.'
    return result, resources, measurements
