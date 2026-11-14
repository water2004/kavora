# SPDX-License-Identifier: MIT

"""Turn a Slang buffer parameter's scalar layout into host compiler assertions."""

import argparse
import json
import subprocess
import tempfile
from pathlib import Path

from slang_spirv_layout import LAYOUT_FLAGS


def reflect(compiler, source, includes, directory, suffix, defines):
    reflection = directory / f'layout{suffix}.json'
    depfile = directory / f'layout{suffix}.d'
    module_search_path = Path(source).resolve().parent
    subprocess.run([
        compiler, source, '-entry', 'main', '-target', 'spirv', '-zero-initialize', '-emit-spirv-directly',
        *LAYOUT_FLAGS, *defines, '-Wno-30081', *[f'-I{path}' for path in [*includes, module_search_path]],
        '-reflection-json', str(reflection), '-depfile', str(depfile),
        '-o', str(directory / f'layout{suffix}.spv'),
    ], check=True)
    _, separator, dependencies = depfile.read_text(encoding='utf-8').partition(': ')
    if not separator:
        raise ValueError('Slang did not emit a valid dependency file')
    return json.loads(reflection.read_text(encoding='utf-8')), dependencies.strip()


def descriptors(reflection):
    used = {binding['name'] for binding in reflection['entryPoints'][0]['bindings']
            if binding['binding'].get('used')}
    slots = {}
    for param in reflection['parameters']:
        binding = param['binding']
        if binding['kind'] != 'descriptorTableSlot':
            continue
        shader_type = param['type']
        slot, name = binding['index'], param['name']
        if binding.get('space', 0) or slot in slots:
            raise ValueError(f'Expected a unique set-zero descriptor for {name}')
        if shader_type['kind'] == 'constantBuffer':
            descriptor, view = 'UNIFORM_BUFFER', None
        elif shader_type['kind'] == 'resource':
            view = {'texture2D': '2D', 'texture3D': '3D', 'textureCube': 'CUBE'}[shader_type['baseShape']]
            if shader_type.get('array'):
                view += '_ARRAY'
            if shader_type.get('combined'):
                descriptor = 'COMBINED_IMAGE_SAMPLER'
            elif shader_type.get('access') == 'readWrite':
                descriptor = 'STORAGE_IMAGE'
            elif 'access' not in shader_type:
                descriptor = 'SAMPLED_IMAGE'
            else:
                raise ValueError(f'Unsupported image access for {name}: {shader_type["access"]}')
        else:
            raise ValueError(f'Unsupported descriptor for {name}: {shader_type["kind"]}')
        count, = (size['value'] for size in shader_type['sizes'] if size['kind'] == 'descriptorTableSlot')
        slots[slot] = (descriptor, view, count), name in used
    return slots


def assert_descriptors(reflections, name, allow_unused, shared_layout):
    slots = {}
    for reflection in reflections:
        variant = descriptors(reflection)
        if slots and shared_layout and slots.keys() != variant.keys():
            raise ValueError(f'{name}: descriptor slots differ across variants')
        for slot, (layout, used) in variant.items():
            if slot in slots:
                previous_layout, previously_used = slots[slot]
                if layout != previous_layout:
                    raise ValueError(f'{name}: descriptor slot {slot} differs across variants')
                used |= previously_used
            slots[slot] = layout, used
    if not slots:
        raise ValueError(f'{name}: no descriptor slots were reflected')
    ordered_slots = sorted(slots.items())
    lines = [
        'template<std::size_t Count>',
        f'consteval bool {name}(const dxvk::DxvkResourceSlot (&slots)[Count]) {{',
        f'  bool seen[{len(slots)}] = {{}};',
        '  for (const auto& slot : slots) {',
        '    std::size_t index;',
        '    switch (slot.slot) {',
    ]
    for index, (slot, ((descriptor, view, count), _)) in enumerate(ordered_slots):
        lines += [
            f'      case {slot}:',
            f'        if (slot.type != VK_DESCRIPTOR_TYPE_{descriptor}'
            + (f' || slot.view != VK_IMAGE_VIEW_TYPE_{view}' if view else '')
            + f' || slot.count != {count}) return false;',
            f'        index = {index};',
            '        break;',
        ]
    lines += ['      default: return false;', '    }', '    if (seen[index]) return false;',
              '    seen[index] = true;', '  }']
    required = [f'seen[{index}]' for index, (_, (_, used)) in enumerate(ordered_slots)
                if used or not allow_unused]
    lines += [f'  return {" && ".join(required) or "true"};', '}']
    return lines


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
    parser.add_argument('--check-slots')
    parser.add_argument('--variant', action='append', default=[])
    parser.add_argument('--allow-unused-slots', action='store_true')
    parser.add_argument('--union-variant-slots', action='store_true')
    options = parser.parse_args()
    compiler, source, includes = options.compiler, options.source, options.includes
    header, depfile = options.header, options.depfile
    param_name, alignment = options.param_name, options.alignment
    with tempfile.TemporaryDirectory(dir=header.parent) as temp_dir:
        directory = Path(temp_dir)
        defines = [f'-D{define}' for define in options.defines]
        reflection, dependencies = reflect(compiler, source, includes, directory, '', defines)
        parameters = reflection['parameters']
        reflections = [reflection]
        dependency_lines = [dependencies]
        for index, variant in enumerate(options.variant):
            reflected_variant, dependencies = reflect(compiler, source, includes, directory, f'_{index}', [*defines, f'-D{variant}'])
            reflections.append(reflected_variant)
            dependency_lines.append(dependencies)

    args = next(param for param in parameters if param['name'] == param_name)
    element = args['type']['elementVarLayout']
    if element['type']['kind'] != 'struct' or element['binding']['kind'] != 'uniform':
        raise ValueError(f'Expected a uniform struct for {param_name}')
    for variant in reflections[1:]:
        parameter = next((param for param in variant['parameters'] if param['name'] == param_name), None)
        if (parameter is None or parameter['binding'] != args['binding'] or
                parameter['type']['elementVarLayout'] != element):
            raise ValueError(f'{source}: {param_name} layout differs across variants')
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
    if options.check_slots:
        lines.insert(3, '#include "dxvk_pipelayout.h"')
        lines += assert_descriptors(reflections, options.check_slots, options.allow_unused_slots,
                                    not options.union_variant_slots)
    lines.append('')
    content = '\n'.join(lines)
    if not header.exists() or header.read_text(encoding='utf-8') != content:
        header.write_bytes(content.encode('utf-8'))

    target = header.as_posix().replace(' ', r'\ ')
    depfile.write_bytes(f'{target}: {" ".join(dependency_lines)}\n'.encode('utf-8'))


if __name__ == '__main__':
    main()
