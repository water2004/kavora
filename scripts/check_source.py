#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Check source layout, ABI copies, license payloads and source-only Git content."""

from pathlib import Path
import configparser
import json
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent


def check() -> None:
    required = [
        'rtx-remix-minecraft/build.gradle', 'rtxremix-java/build.gradle',
        'remix-horizons/fabric/build.gradle', 'dxvk-remix/meson.build',
        'README.md', 'LICENSE', 'NOTICE', 'THIRD_PARTY_NOTICES.md',
        'LICENSES/README.md', 'LICENSES/Apache-2.0.txt', 'LICENSES/MIT-NVIDIA.txt',
        'LICENSES/Zlib-DXVK.txt', 'LICENSES/LGPL-3.0.txt', 'LICENSES/GPL-3.0.txt',
        'rtx-remix-minecraft/LICENSE', 'rtxremix-java/LICENSE',
        'dxvk-remix/LICENSE', 'dxvk-remix/LICENSE-MIT',
        'remix-horizons/LICENSE.LESSER.txt', 'remix-horizons/LICENSE.txt',
        'remix-horizons/licenses/rocksdb-LICENSE-Apache.txt',
        'remix-horizons/licenses/rocksdb-LICENSE.leveldb.txt',
    ]
    payload = ROOT / 'rtx-remix-minecraft/packaging/licenses'
    required += [str(p.relative_to(ROOT)) for p in payload.rglob('*') if p.is_file()]
    for name in required:
        path = ROOT / name
        assert path.is_file() and path.stat().st_size > 0, f'Missing source/license: {name}'

    header = ROOT / 'dxvk-remix/public/include/remix/remix_c.h'
    assert header.read_bytes() == (ROOT / 'rtxremix-java/native/include/remix_c.h').read_bytes(), 'C API headers differ'
    for source in (ROOT / 'rtxremix-java/src').rglob('*.java'):
        text = source.read_text(encoding='utf-8')
        assert 'import net.minecraft' not in text and 'import net.fabricmc' not in text, f'Game dependency in {source}'

    defaults = json.loads((ROOT / 'rtx-remix-minecraft/packaging/launcher-defaults.json').read_text())
    assert '--enable-native-access=ALL-UNNAMED' in defaults['RECOMMENDED_JVM_ARGS']
    config = configparser.ConfigParser()
    config.read(ROOT / '.gitmodules')
    assert len(config.sections()) == 10, 'Expected ten pinned top-level runtime dependencies'
    for section in config.sections():
        assert config[section]['path'].startswith('dxvk-remix/'), f'Misplaced dependency: {section}'
        assert config[section]['url'].startswith('https://github.com/'), f'Non-public dependency: {section}'

    tracked = subprocess.check_output(['git', 'ls-files', '-z'], cwd=ROOT).decode().split('\0')
    forbidden = ('.codex/', '.local/', 'dist/', 'rtx-remix-minecraft/remix-runtime/', 'dxvk-remix/external/')
    for name in filter(None, tracked):
        assert not name.startswith(forbidden), f'Local artifact tracked: {name}'
        assert '/build/' not in name and not name.startswith('build/'), f'Build output tracked: {name}'
    print(f'Source layout, matching C API headers and {len(required)} required source/license files verified.')


if __name__ == '__main__':
    try:
        check()
    except (AssertionError, OSError, subprocess.CalledProcessError) as error:
        print(f'Source check failed: {error}', file=sys.stderr)
        raise SystemExit(1)
