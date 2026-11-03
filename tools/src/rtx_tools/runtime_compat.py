# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""Probe the staged source runtime through its native Remix 1.5.0 C API."""

from __future__ import annotations

import ctypes
import os
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path

INCOMPATIBLE_VERSION = 8
PROBE_TIMEOUT_SECONDS = 30
INTERFACE_ENTRY_COUNT = 44


@dataclass(frozen=True)
class ProbeResult:
    code: int | None
    failure: str | None = None
    runtime: Path | None = None
    override: bool = False

    @property
    def incompatible(self) -> bool:
        return self.code == INCOMPATIBLE_VERSION


class InitializeLibraryInfo(ctypes.Structure):
    _fields_ = [("sType", ctypes.c_uint32), ("pNext", ctypes.c_void_p), ("version", ctypes.c_uint64)]


def _probe_in_process(runtime: Path) -> int:
    library = ctypes.WinDLL(str(runtime), winmode=0x1100)
    initialize = library.remixapi_InitializeLibrary
    initialize.argtypes = [
        ctypes.POINTER(InitializeLibraryInfo),
        ctypes.POINTER(ctypes.c_void_p * INTERFACE_ENTRY_COUNT),
    ]
    initialize.restype = ctypes.c_uint32
    info = InitializeLibraryInfo(1, None, (1 << 48) | (5 << 16))
    interface = (ctypes.c_void_p * INTERFACE_ENTRY_COUNT)()
    status = int(initialize(ctypes.byref(info), ctypes.byref(interface)))
    if status == 0 and not all(interface):
        return INCOMPATIBLE_VERSION
    return status


def _process_arch() -> str | None:
    from .prism import executable_arch

    return executable_arch(Path(sys.executable))


def _probe_in_subprocess(runtime: Path) -> tuple[int | None, str | None]:
    command = [sys.executable, "-m", __name__, str(runtime)]
    try:
        with tempfile.TemporaryDirectory(prefix="rtx-remix-runtime-probe-") as cwd:
            result = subprocess.run(
                command,
                capture_output=True,
                text=True,
                timeout=PROBE_TIMEOUT_SECONDS,
                check=False,
                cwd=cwd,
            )
    except (OSError, subprocess.TimeoutExpired) as exc:
        return None, str(exc)
    if result.returncode != 0:
        detail = result.stderr.strip() or f"probe process exited with code {result.returncode}"
        return None, detail
    try:
        return int(result.stdout.strip()), None
    except ValueError:
        return None, f"probe returned an invalid result: {result.stdout.strip()!r}"


def probe(project: Path, arch: str) -> ProbeResult | None:
    """Check the runtime install will use, or None when no runtime has been staged."""
    from .install import runtime_source

    source = runtime_source(project, arch)
    runtime = source / "d3d9.dll"
    if not runtime.is_file():
        return None

    process_arch = _process_arch()
    if process_arch is not None and process_arch != arch:
        return ProbeResult(
            None,
            f"the tools Python process is {process_arch}, but the selected runtime is {arch}; "
            f"run the tools from a native {arch} Python/uv environment to enable this check "
            f"(current interpreter: {sys.executable})",
            runtime,
            "REMIX_BUILD" in os.environ,
        )
    code, failure = _probe_in_subprocess(runtime)
    return ProbeResult(code, failure, runtime, "REMIX_BUILD" in os.environ)


def warn(result: ProbeResult | None) -> None:
    """Describe a failed probe without deciding whether the caller should continue."""
    if result is None:
        return
    code, failure = result.code, result.failure
    if failure is not None:
        print(f"WARNING: could not check RTX Remix runtime compatibility before launch ({failure}).")
        return
    if code == 0:
        return
    if code == INCOMPATIBLE_VERSION:
        lines = [
            "WARNING: the selected RTX Remix runtime is incompatible with this checkout's Remix API "
            "(INCOMPATIBLE_VERSION).",
            "         RTX Remix will not initialize with this runtime and mod combination.",
        ]
        if result.override:
            selected = result.runtime.parent if result.runtime else "an override"
            lines.append(f"         Update or unset REMIX_BUILD, which currently selects {selected}.")
        else:
            lines += [
                "         Build a matching source runtime and stage it with `tools\\rtx.bat sdk local`; keep its",
                "         C API version in step with the mod.",
            ]
        print("\n".join(lines))
        return
    print(f"WARNING: the RTX Remix compatibility probe returned error code {code}.")


def warn_if_incompatible(project: Path, arch: str) -> ProbeResult | None:
    """Probe and report compatibility, returning the result so a launcher can choose its policy."""
    result = probe(project, arch)
    warn(result)
    return result


def _main(argv: list[str]) -> int:
    if len(argv) != 1:
        print("usage: python -m rtx_tools.runtime_compat <d3d9.dll>", file=sys.stderr)
        return 2
    try:
        code = _probe_in_process(Path(argv[0]))
    except (AttributeError, OSError) as exc:
        print(exc, file=sys.stderr)
        return 1
    print(code)
    return 0


if __name__ == "__main__":
    raise SystemExit(_main(sys.argv[1:]))
