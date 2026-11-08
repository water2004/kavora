# SPDX-License-Identifier: MIT
"""Content fingerprints for one invocation of the shader compiler driver."""

import hashlib
import json
import os
from pathlib import Path
import threading


class ShaderDependencyCache:
    def __init__(self, manifest, tools):
        self.manifest = Path(manifest)
        self.tools = list(tools)
        self._hashes = {}
        self._lock = threading.Lock()
        try:
            data = json.loads(self.manifest.read_text(encoding="utf-8"))
            self._records = data["records"] if isinstance(data, dict) and data.get("version") == 1 else {}
            if not isinstance(self._records, dict):
                self._records = {}
        except (OSError, ValueError, KeyError, TypeError):
            self._records = {}

    def fingerprint(self, inputs, commands):
        digest = hashlib.sha256()
        digest.update(json.dumps(commands, ensure_ascii=True).encode("ascii"))
        # Sources stay fixed during a build. Each shared include is read once across variants.
        for source in sorted(set(str(Path(p).resolve()) for p in [*self.tools, *inputs])):
            with self._lock:
                value = self._hashes.get(source)
                if value is None:
                    try:
                        value = hashlib.sha256(Path(source).read_bytes()).digest()
                    except OSError:
                        return None
                    self._hashes[source] = value
            digest.update(source.encode("utf-8") + b"\0")
            digest.update(value)
        return digest.hexdigest()

    def needs_build(self, output, inputs, commands):
        fingerprint = self.fingerprint(inputs, commands)
        return fingerprint is None or self._records.get(str(Path(output).resolve())) != fingerprint

    def record_success(self, output, inputs, commands):
        fingerprint = self.fingerprint(inputs, commands)
        if fingerprint is None:
            raise OSError("A shader dependency disappeared during compilation")
        with self._lock:
            self._records[str(Path(output).resolve())] = fingerprint

    def save(self):
        data = json.dumps({"version": 1, "records": self._records}, sort_keys=True).encode("utf-8")
        if self.manifest.exists() and self.manifest.read_bytes() == data:
            return
        temporary = self.manifest.with_suffix(self.manifest.suffix + ".tmp")
        temporary.write_bytes(data)
        os.replace(temporary, self.manifest)
