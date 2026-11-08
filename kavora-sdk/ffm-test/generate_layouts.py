# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
"""Generate the FFM layout declarations and a C ABI probe from the pinned header."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
HEADER = ROOT / 'kavora-sdk/native/include/remix_c.h'
text = re.sub(r'/\*.*?\*/|//[^\n]*', '', HEADER.read_text(), flags=re.S)
structs = []
enums = set(re.findall(r'typedef enum\s+(remixapi_\w+)', text))
integer_aliases = set(re.findall(r'typedef uint32_t\s+(remixapi_\w+)\s*;', text))
for match in re.finditer(r'typedef struct remixapi_(\w+)\s*\{(.*?)\}\s*remixapi_\1\s*;', text, re.S):
    name, body = match.groups()
    fields = []
    for declaration in body.split(';'):
        declaration = declaration.strip()
        if not declaration:
            continue
        field = re.fullmatch(r'(.+?)\s+(\w+)((?:\[\d+\])*)', declaration, re.S)
        if not field:
            raise ValueError(declaration)
        ctype, fieldname, dimensions = field.groups()
        count = 1
        for dimension in re.findall(r'\[(\d+)\]', dimensions):
            count *= int(dimension)
        fields.append((ctype.strip(), fieldname, count))
    structs.append((name, fields))

def java_type(ctype):
    if '*' in ctype or ctype.startswith('PFN_') or ctype.endswith('Handle') or ctype in ('remixapi_HWND', 'remixapi_Path'):
        return 'ADDRESS'
    if ctype == 'float': return 'JAVA_FLOAT'
    if ctype == 'uint64_t': return 'JAVA_LONG'
    if ctype == 'uint16_t': return 'JAVA_SHORT'
    if ctype == 'uint8_t': return 'JAVA_BYTE'
    suffix = ctype.removeprefix('remixapi_')
    if suffix in {name for name, fields in structs}:
        return f'layout("{suffix}")'
    if ctype in ('int', 'int32_t', 'uint32_t') or ctype in enums or ctype in integer_aliases:
        return 'JAVA_INT'
    raise ValueError(ctype)

prefix = '''/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.sdk;

import java.lang.foreign.MemoryLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static java.lang.foreign.ValueLayout.*;

/** Windows 64-bit Remix C ABI layouts, including alignment and trailing padding. */
final class RemixLayouts {
    private static final Map<String, MemoryLayout> STRUCTS = new LinkedHashMap<>();
    static final Map<String, MemoryLayout> ALL;

    static {
'''
lines = [prefix]
for name, fields in structs:
    lines.append(f'        define("{name}",\n')
    declarations = []
    for ctype, fieldname, count in fields:
        layout = java_type(ctype)
        if count != 1: layout = f'MemoryLayout.sequenceLayout({count}, {layout})'
        declarations.append(f'                {layout}.withName("{fieldname}")')
    lines.append(',\n'.join(declarations) + ');\n')
lines.append('''        ALL = Collections.unmodifiableMap(STRUCTS);
    }

    private RemixLayouts() {}

    static MemoryLayout layout(String name) {
        MemoryLayout result = STRUCTS.get(name);
        if (result == null) throw new IllegalArgumentException("Unknown Remix C structure: " + name);
        return result;
    }

    static long offset(String structure, String field) {
        return layout(structure).byteOffset(MemoryLayout.PathElement.groupElement(field));
    }

    static long size(String structure) {
        return layout(structure).byteSize();
    }

    private static void define(String name, MemoryLayout... fields) {
        var members = new ArrayList<MemoryLayout>();
        long offset = 0;
        long alignment = 1;
        for (MemoryLayout field : fields) {
            long fieldAlignment = field.byteAlignment();
            alignment = Math.max(alignment, fieldAlignment);
            long padding = (fieldAlignment - offset % fieldAlignment) % fieldAlignment;
            if (padding != 0) members.add(MemoryLayout.paddingLayout(padding));
            members.add(field);
            offset += padding + field.byteSize();
        }
        long padding = (alignment - offset % alignment) % alignment;
        if (padding != 0) members.add(MemoryLayout.paddingLayout(padding));
        STRUCTS.put(name, MemoryLayout.structLayout(members.toArray(MemoryLayout[]::new)).withName(name));
    }
}
''')
def output(path, content):
    if '--check' in sys.argv:
        if path.read_text() != content:
            raise SystemExit(f'Stale generated ABI declaration: {path}')
    else:
        path.write_text(content)

output(ROOT / 'kavora-sdk/src/org/edtp/kavora/sdk/RemixLayouts.java', ''.join(lines))
probe = ['/* SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.\n'
         ' * SPDX-License-Identifier: Apache-2.0 */\n'
         '#include <stddef.h>\n#include <stdio.h>\n#include "remix_c.h"\n'
         '_Static_assert(sizeof(void*) == 8, "Remix requires 64-bit pointers");\n'
         '_Static_assert(sizeof(wchar_t) == 2, "Remix paths use Windows UTF-16");\n'
         '_Static_assert(sizeof(remixapi_Bool) == 4, "Remix bool is uint32_t");\n'
         'int main(void) {\n']
for name, fields in structs:
    probe.append(f'printf("{name} sizeof %zu\\n", sizeof(remixapi_{name}));\n')
    probe.append(f'printf("{name} alignof %zu\\n", __alignof(remixapi_{name}));\n')
    for ctype, fieldname, count in fields:
        probe.append(f'printf("{name} {fieldname} %zu\\n", offsetof(remixapi_{name}, {fieldname}));\n')
probe.append('return 0;\n}\n')
output(ROOT / 'kavora-sdk/ffm-test/layout_probe.c', ''.join(probe))
print(f'Generated {len(structs)} structures, {sum(len(fields) for name, fields in structs)} fields')
