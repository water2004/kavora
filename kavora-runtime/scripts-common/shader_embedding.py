# SPDX-License-Identifier: MIT
"""Share identical SPIR-V arrays while preserving each variant's C++ array type."""

import hashlib
import io
import os
from pathlib import Path

from shader_xxd import write_c_array, xxd_c_array


SHARED_MARKER = '// Shared SPIR-V bytecode.\n'


def _publish(path, contents):
    path = Path(path)
    encoded = contents.encode('utf-8')
    if path.exists() and path.read_bytes() == encoded:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(str(path) + '.tmp')
    temporary.write_bytes(encoded)
    os.replace(temporary, path)


def _is_shared(path):
    with Path(path).open('rb') as stream:
        return SHARED_MARKER.encode('ascii') in stream.read(1024)


def restore_inline_shaders(entries):
    for binary, header in entries:
        if _is_shared(header):
            xxd_c_array(Path(binary).read_bytes(), header)


def deduplicate_shaders(entries, shared_header, shared_sources, header_prefix=''):
    if not shared_sources:
        raise ValueError('Shared shader bytecode needs at least one source output')
    groups = {}
    total_bytes = 0
    for binary, header in sorted(entries):
        binary, header = Path(binary), Path(header)
        length = binary.stat().st_size
        if not length or length % 4:
            raise ValueError(f'SPIR-V must contain complete 32-bit words: {binary}')
        digest = hashlib.sha256()
        with binary.open('rb') as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                digest.update(chunk)
        groups.setdefault(digest.hexdigest(), []).append((binary, header))
        total_bytes += length

    shared_header = Path(shared_header)
    declarations = ['#pragma once\n#include <cstdint>\n\nnamespace dxvk::shader_bytecode {\n']
    sources = [io.StringIO() for _ in shared_sources]
    for source, path in zip(sources, shared_sources):
        include = os.path.relpath(shared_header, Path(path).parent).replace('\\', '/')
        source.write(f'#include "{include}"\n\nnamespace dxvk::shader_bytecode {{\n')

    aliases = []
    unique_entries = []
    saved_bytes = 0
    shared_groups = 0
    for digest, group in sorted(groups.items()):
        if len(group) == 1:
            unique_entries.extend(group)
            continue
        data = group[0][0].read_bytes()
        for binary, _ in group[1:]:
            if binary.read_bytes() != data:
                raise ValueError('SPIR-V hash collision: ' + str(binary))
        name = 'blob_' + digest
        declarations.append(f'extern const uint32_t {name}[{len(data) // 4}];\n')
        source = sources[int(digest[:8], 16) % len(sources)]
        write_c_array(source, data, name, write_size=False)
        saved_bytes += len(data) * (len(group) - 1)
        shared_groups += 1
        for _, header in group:
            include = os.path.relpath(shared_header, header.parent).replace('\\', '/')
            aliases.append((header, header_prefix + SHARED_MARKER
                            + f'#include "{include}"\n'
                            + f'inline constexpr auto& {header.stem} = ::dxvk::shader_bytecode::{name};\n'
                            + f'const size_t {header.stem}_sizeInBytes = {len(data)};\n'))

    declarations.append('}\n')
    _publish(shared_header, ''.join(declarations))
    for source, path in zip(sources, shared_sources):
        source.write('}\n')
        _publish(path, source.getvalue())
    for header, contents in aliases:
        _publish(header, contents)
    restore_inline_shaders(unique_entries)
    return {'bytes': total_bytes, 'uniqueBytes': total_bytes - saved_bytes,
            'savedBytes': saved_bytes, 'sharedGroups': shared_groups}
