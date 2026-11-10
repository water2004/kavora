# SPDX-License-Identifier: MIT

"""Turn a Slang buffer parameter's scalar layout into host compiler assertions."""

import json
import subprocess
import sys
import tempfile
from pathlib import Path

from slang_spirv_layout import LAYOUT_FLAGS


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
    compiler, source, header_name, depfile_name, param_name, host_alignment, *includes = sys.argv[1:]
    alignment = int(host_alignment)
    header = Path(header_name)
    depfile = Path(depfile_name)
    with tempfile.TemporaryDirectory(dir=header.parent) as temp_dir:
        directory = Path(temp_dir)
        reflection = directory / 'layout.json'
        subprocess.run([
            compiler, source, '-entry', 'main', '-target', 'spirv', '-emit-spirv-directly',
            *LAYOUT_FLAGS, '-Wno-30081', *[f'-I{path}' for path in includes],
            '-reflection-json', str(reflection), '-depfile', str(depfile),
            '-o', str(directory / 'layout.spv'),
        ], check=True)
        parameters = json.loads(reflection.read_text(encoding='utf-8'))['parameters']

    args = next(param for param in parameters if param['name'] == param_name)
    element = args['type']['elementVarLayout']
    if element['type']['kind'] != 'struct' or element['binding']['kind'] != 'uniform':
        raise ValueError(f'Expected a uniform struct for {param_name}')
    struct_name = element['type']['name']
    size = ((element['binding']['size'] + alignment - 1) // alignment) * alignment
    lines = [
        f'// Generated from the Slang {struct_name} buffer layout.',
        '#pragma once',
        '#include <cstddef>',
        f'static_assert(sizeof({struct_name}) == {size}, "{struct_name} size");',
        f'static_assert(alignof({struct_name}) == {alignment}, "{struct_name} alignment");',
        *assert_fields(element['type']['fields'], struct_name),
        '',
    ]
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
