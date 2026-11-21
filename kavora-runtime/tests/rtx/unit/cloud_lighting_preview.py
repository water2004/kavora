# SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors
# SPDX-License-Identifier: MIT
"""Build the production vertical and neural cloud fields for offline previews."""
import re
import struct
import time

import numpy as np
import slangpy as spy


def pack_inference_weights(device, source, inputs, width, hidden_layers, output_rows, alignment):
    """Convert the canonical checkpoint into the Vulkan device's inference layout."""
    if not device.has_feature(spy.Feature.cooperative_vector):
        raise RuntimeError("Cloud lighting previews require Vulkan cooperative-vector inference")
    affine_layers = hidden_layers + 1
    packed = bytearray(2 * affine_layers * 8)
    values = np.fromfile(source, '<f4')
    cursor = 0
    for index in range(2 * affine_layers):
        layer = index % affine_layers
        columns = inputs if layer == 0 else width
        outputs = 1 if layer == hidden_layers else width
        rows = output_rows if layer == hidden_layers else width
        end = cursor + (columns + 1) * outputs
        biases = np.zeros(rows, '<f2')
        biases[:outputs] = values[cursor:cursor + outputs]
        matrix = np.zeros((rows, columns), '<f2')
        matrix[:outputs] = values[cursor + outputs:end].reshape(columns, outputs).T
        cursor = end

        source_desc = device.create_coop_vec_matrix_desc(
            rows, columns, spy.CoopVecMatrixLayout.row_major, spy.DataType.float16)
        target_desc = device.create_coop_vec_matrix_desc(
            rows, columns, spy.CoopVecMatrixLayout.inferencing_optimal, spy.DataType.float16)
        converted = bytearray(target_desc.size)
        device.convert_coop_vec_matrix(converted, target_desc, matrix.tobytes(), source_desc)
        packed.extend(bytes(-len(packed) % alignment))
        matrix_offset = len(packed)
        packed.extend(converted)
        packed.extend(bytes(-len(packed) % alignment))
        bias_offset = len(packed)
        packed.extend(biases.tobytes())
        struct.pack_into('<II', packed, index * 8, matrix_offset, bias_offset)
    if cursor != values.size:
        raise ValueError("Cloud neural checkpoint does not match the runtime layout")
    packed.extend(bytes(-len(packed) % alignment))
    return device.create_buffer(data=np.frombuffer(packed, np.uint8), usage=spy.BufferUsage.shader_resource)


def build_lighting_cache(device, clouds, textures, shaders, lighting_enabled=True, warmup=False, repeats=1,
                         include_neural=False, phase_view=None):
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
    if 'CLOUD_LOCAL_LIGHT_BOUNDARY_SLICES' in layout:
        height += constant(layout, 'CLOUD_LOCAL_LIGHT_BOUNDARY_SLICES')
    bake('CloudLocalLight', (resolution, height, resolution), (resolution, resolution, 2 if high else 1), 2,
         'cloud_lighting_preview.comp.slang', 'buildLocalLight', {}, spy.TextureAddressingMode.wrap)
    if include_neural:
        model_layout = (shaders / 'rtx/pass/clouds/cloud_neural_model.h').read_text()
        tile = constant(neural_layout, 'CLOUD_NEURAL_TILE_SIZE')
        rows = constant(neural_layout, 'CLOUD_NEURAL_HEIGHT') + constant(neural_layout, 'CLOUD_NEURAL_HIGH_HEIGHT')
        knots = constant(neural_layout, 'CLOUD_NEURAL_POLAR_KNOTS')
        lights = constant(neural_layout, 'CLOUD_NEURAL_SKY_CHANNEL')
        lanes = constant(model_layout, 'CLOUD_NEURAL_TENSOR_LANES')
        sky_directions = constant(model_layout, 'CLOUD_NEURAL_SKY_DIRECTIONS')
        directions = 2 * knots - 2
        plane = (3 * tile) * (2 * tile)
        groups = lights * (plane // (lanes // directions)) + plane // (lanes // sky_directions)
        inputs = dict(result)
        if active:
            inputs['Phase'] = phase_view.descriptor_handle_combined
            asset = shaders.parent / 'rtx_render/clouds/cloud_neural_lighting.bin'
            inputs['NeuralCloudWeights'] = pack_inference_weights(device, asset,
                constant(neural_layout, 'CLOUD_NEURAL_INPUTS'), constant(neural_layout, 'CLOUD_NEURAL_WIDTH'),
                constant(neural_layout, 'CLOUD_NEURAL_HIDDEN_LAYERS'),
                constant(model_layout, 'CLOUD_NEURAL_TENSOR_OUTPUTS'),
                constant(model_layout, 'CLOUD_NEURAL_TENSOR_ALIGNMENT'))
        bake('CloudNeuralLight', (3 * tile, (lights * knots + 2) * rows, 2 * tile), (groups * lanes, rows, 1), 4,
             'cloud_neural_preview.comp.slang', 'main', inputs, spy.TextureAddressingMode.clamp_to_edge)

    return result, resources, {
        'resolution': resolution,
        'volumes': volumes,
        'bytes': sum(volume['bytes'] for volume in volumes),
        'dispatchWaitSeconds': sum(volume['dispatchWaitSeconds'] for volume in volumes),
        'scope': 'Vulkan dispatch and host wait.',
    }
