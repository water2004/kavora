# SPDX-License-Identifier: MIT

"""Turn a Slang buffer parameter's scalar layout into host compiler assertions."""

import argparse
import json
import subprocess
import tempfile
from pathlib import Path

from slang_spirv_layout import LAYOUT_FLAGS


def validate_dither_resources(parameters, variant):
    resources = {param['name']: param for param in parameters if param['type']['kind'] == 'resource'}
    expected = {
        'BlueNoise': ('SRGB_DITHER_BLUE_NOISE_TEXTURE_INPUT', 'texture2D', True, None),
        'InOutColorBuffer': ('SRGB_DITHER_COLOR_INPUT_OUTPUT', 'texture2D', False, 'readWrite'),
    }
    if set(resources) != set(expected):
        raise ValueError(f'{variant}: expected only BlueNoise and InOutColorBuffer resources, got {sorted(resources)}')
    bindings = {}
    for name, (binding, shape, is_array, access) in expected.items():
        param = resources[name]
        ty = param['type']
        reflected_binding = param['binding']
        # Slang omits the descriptor space for set zero.
        if (reflected_binding.get('kind') != 'descriptorTableSlot' or
                reflected_binding.get('space', 0) != 0):
            raise ValueError(f'{variant}: {name} must use a descriptor in set zero')
        bindings[binding] = reflected_binding['index']
        if ty.get('baseShape') != shape or ty.get('array', False) != is_array:
            raise ValueError(f'{variant}: unexpected {name} image shape/array layout')
        if ty.get('access') != access:
            raise ValueError(f'{variant}: unexpected {name} image access {ty.get("access")!r}')
        result = ty.get('resultType', {})
        if (result.get('kind') != 'vector' or result.get('elementCount') != 4 or
                result.get('elementType', {}).get('kind') != 'scalar' or
                result.get('elementType', {}).get('scalarType') != 'float32'):
            raise ValueError(f'{variant}: {name} must expose float4 image elements')
    return bindings


def reflect(compiler, source, includes, directory, suffix, defines=(), depfile_override=None):
    reflection = directory / f'layout{suffix}.json'
    depfile = depfile_override or directory / f'layout{suffix}.d'
    module_search_path = Path(source).resolve().parent
    subprocess.run([
        compiler, source, '-entry', 'main', '-target', 'spirv', '-zero-initialize', '-emit-spirv-directly',
        *LAYOUT_FLAGS, *defines, '-Wno-30081', *[f'-I{path}' for path in [*includes, module_search_path]],
        '-reflection-json', str(reflection), '-depfile', str(depfile),
        '-o', str(directory / f'layout{suffix}.spv'),
    ], check=True)
    return json.loads(reflection.read_text(encoding='utf-8'))['parameters'], depfile


def assert_fields(fields, struct_name, prefix='', base_offset=0):
    for field in fields:
        name = prefix + field['name']
        layout = field['binding']
        if layout['kind'] != 'uniform':
            raise ValueError(f'Expected a uniform layout for {name}')
        offset = base_offset + layout['offset']
        yield f'static_assert(offsetof({struct_name}, {name}) == {offset}, "{struct_name}.{name} offset");'
        yield from assert_type(field['type'], struct_name, name, offset, layout['size'])


def assert_type(shader_type, struct_name, name, offset, size):
    kind = shader_type['kind']
    if kind not in ('scalar', 'vector', 'matrix', 'struct', 'array'):
        raise ValueError(f'Unsupported {struct_name} member type {kind} for {name}')
    yield f'static_assert(sizeof(static_cast<{struct_name}*>(nullptr)->{name}) == {size}, "{struct_name}.{name} size");'
    if kind == 'struct':
        yield from assert_fields(shader_type['fields'], struct_name, name + '.', offset)
    elif kind == 'array':
        # The host array uses sizeof(element) as its stride. Check one element
        # recursively and the total array size to cover all element offsets.
        yield from assert_type(shader_type['elementType'], struct_name, name + '[0]', offset, shader_type['uniformStride'])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('compiler')
    parser.add_argument('source')
    parser.add_argument('header', type=Path)
    parser.add_argument('depfile', type=Path)
    parser.add_argument('param_name')
    parser.add_argument('alignment', type=int)
    parser.add_argument('includes', nargs='*')
    parser.add_argument('-D', dest='defines', action='append', default=[])
    parser.add_argument('--validate-srgb-dither', action='store_true')
    options = parser.parse_args()
    compiler, source, includes = options.compiler, options.source, options.includes
    header, depfile = options.header, options.depfile
    param_name, alignment = options.param_name, options.alignment
    with tempfile.TemporaryDirectory(dir=header.parent) as temp_dir:
        directory = Path(temp_dir)
        defines = [f'-D{define}' for define in options.defines]
        parameters, depfile = reflect(compiler, source, includes, directory, '', defines, depfile)
        variant_parameters = [('sRGB dither', parameters)]
        if options.validate_srgb_dither:
            inversion, _ = reflect(compiler, source, includes, directory, '_inversion', [*defines, '-DDISPLAY_COLOR_INVERSION'])
            variant_parameters.append(('sRGB dither inversion', inversion))

    args = next(param for param in parameters if param['name'] == param_name)
    element = args['type']['elementVarLayout']
    if element['type']['kind'] != 'struct' or element['binding']['kind'] != 'uniform':
        raise ValueError(f'Expected a uniform struct for {param_name}')
    bindings = {}
    if options.validate_srgb_dither:
        for variant_name, variant in variant_parameters:
            reflected_bindings = validate_dither_resources(variant, variant_name)
            if bindings and bindings != reflected_bindings:
                raise ValueError(f'{variant_name}: descriptor bindings differ from sRGB dither')
            bindings = reflected_bindings
            push = next((param for param in variant if param['name'] == param_name), None)
            if (push is None or push['binding'].get('kind') != 'pushConstantBuffer' or
                    push['binding'].get('index') != 0):
                raise ValueError(f'{variant_name}: expected {param_name} at push-constant slot 0')
            variant_element = push['type'].get('elementVarLayout', {})
            if (variant_element.get('type') != element['type'] or
                    variant_element.get('binding') != element['binding']):
                raise ValueError(f'{variant_name}: push-constant layout differs from sRGB dither')
    struct_name = element['type']['name']
    size = ((element['binding']['size'] + alignment - 1) // alignment) * alignment
    lines = [
        f'// Generated from the Slang {struct_name} buffer layout.',
        '#pragma once',
        '#include <cstddef>',
        f'static_assert(sizeof({struct_name}) == {size}, "{struct_name} size");',
        f'static_assert(alignof({struct_name}) == {alignment}, "{struct_name} alignment");',
        *assert_fields(element['type']['fields'], struct_name),
    ]
    if options.validate_srgb_dither:
        lines += [f'static_assert({name} == {slot}, "{name} descriptor slot");' for name, slot in bindings.items()]
    lines.append('')
    content = '\n'.join(lines)
    if not header.exists() or header.read_text(encoding='utf-8') != content:
        header.write_bytes(content.encode('utf-8'))

    # Slang names the temporary SPIR-V file as the dependency target.
    _, separator, dependencies = depfile.read_text(encoding='utf-8').partition(': ')
    if not separator:
        raise ValueError('Slang did not emit a valid dependency file')
    target = header.as_posix().replace(' ', r'\ ')
    depfile.write_bytes(f'{target}: {dependencies}'.encode('utf-8'))


if __name__ == '__main__':
    main()
