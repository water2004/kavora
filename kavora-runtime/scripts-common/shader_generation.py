# SPDX-License-Identifier: MIT
"""Publish a declared build dependency for embedded RTX shader bytecode."""

import hashlib
import os
from pathlib import Path


def shader_generation_include(output, stamp):
    return '#include "' + os.path.relpath(Path(stamp).resolve(), Path(output).resolve()).replace("\\", "/") + '"\n'


def publish_shader_generation(output, stamp):
    output = Path(output).resolve()
    stamp = Path(stamp).resolve()
    include = shader_generation_include(output, stamp)
    digest = hashlib.sha256()
    for header in sorted(output.glob("*.h")):
        if header == stamp:
            continue
        contents = header.read_bytes()
        prefix = include.encode("utf-8")
        if not contents.startswith(prefix):
            contents = prefix + contents
            header.write_bytes(contents)
        digest.update(header.name.encode("utf-8") + b"\0")
        digest.update(contents)
    fingerprint = digest.hexdigest()
    contents = ('#pragma once\n#define DXVK_RTX_SHADER_BYTECODE_SHA256 "' + fingerprint + '"\n').encode("ascii")
    if not stamp.exists() or stamp.read_bytes() != contents:
        stamp.parent.mkdir(parents=True, exist_ok=True)
        stamp.write_bytes(contents)
    return fingerprint
