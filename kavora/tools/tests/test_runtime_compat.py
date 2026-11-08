# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import ctypes
import re
import subprocess
from pathlib import Path

import pytest
from conftest import write_jdk

from kavora_tools import runtime_compat


@pytest.fixture
def win64_process(monkeypatch):
    monkeypatch.delenv("REMIX_BUILD", raising=False)
    monkeypatch.setattr(runtime_compat, "_process_arch", lambda: "win64")


def staged_runtime(tmp_path: Path, arch: str = "win64") -> tuple[Path, Path, Path]:
    project = tmp_path / "project"
    bridge = project / "build" / "native" / arch / "kavora-support.dll"
    runtime = project / "runtime" / arch / "d3d9.dll"
    bridge.parent.mkdir(parents=True)
    runtime.parent.mkdir(parents=True)
    bridge.write_bytes(b"bridge")
    runtime.write_bytes(b"runtime")
    return project, bridge, runtime


def test_the_probe_runs_out_of_process(tmp_path, monkeypatch):
    project, bridge, runtime = staged_runtime(tmp_path)
    seen = []

    def run(command, **kwargs):
        seen.append((command, kwargs))
        return subprocess.CompletedProcess(command, 0, stdout="8\n", stderr="")

    monkeypatch.setattr(subprocess, "run", run)
    code, failure = runtime_compat._probe_in_subprocess(runtime)

    assert code == runtime_compat.INCOMPATIBLE_VERSION and failure is None
    assert seen[0][0] == [runtime_compat.sys.executable, "-m", "kavora_tools.runtime_compat", str(runtime)]
    assert seen[0][1]["timeout"] == runtime_compat.PROBE_TIMEOUT_SECONDS
    assert Path(seen[0][1]["cwd"]).name.startswith("kavora-runtime-probe-")
    assert not Path(seen[0][1]["cwd"]).exists()
    assert project.is_dir()


def test_an_incompatible_runtime_warns_without_blocking(tmp_path, monkeypatch, capsys, win64_process):
    project, _, _ = staged_runtime(tmp_path)
    monkeypatch.setattr(
        runtime_compat,
        "_probe_in_subprocess",
        lambda runtime: (runtime_compat.INCOMPATIBLE_VERSION, None),
    )

    result = runtime_compat.warn_if_incompatible(project, "win64")

    assert result is not None and result.incompatible
    warning = capsys.readouterr().out
    assert "INCOMPATIBLE_VERSION" in warning
    assert "will not initialize" in warning
    assert "sdk local" in warning


def test_a_compatible_runtime_is_silent(tmp_path, monkeypatch, capsys, win64_process):
    project, _, _ = staged_runtime(tmp_path)
    monkeypatch.setattr(runtime_compat, "_probe_in_subprocess", lambda runtime: (0, None))

    result = runtime_compat.warn_if_incompatible(project, "win64")

    assert result == runtime_compat.ProbeResult(0, runtime=project / "runtime" / "win64" / "d3d9.dll")
    assert capsys.readouterr().out == ""


def test_a_probe_failure_is_distinct_from_an_api_mismatch(tmp_path, monkeypatch, capsys, win64_process):
    project, _, _ = staged_runtime(tmp_path)
    monkeypatch.setattr(runtime_compat, "_probe_in_subprocess", lambda runtime: (None, "wrong architecture"))

    runtime_compat.warn_if_incompatible(project, "win64")

    warning = capsys.readouterr().out
    assert "could not check" in warning
    assert "wrong architecture" in warning
    assert "INCOMPATIBLE_VERSION" not in warning


def test_probe_uses_the_same_checkout_relative_override_as_install(tmp_path, monkeypatch, win64_process):
    project, _, _ = staged_runtime(tmp_path)
    override = project / "local SDK" / "d3d9.dll"
    override.parent.mkdir()
    override.write_bytes(b"override")
    monkeypatch.setenv("REMIX_BUILD", "local SDK")
    monkeypatch.chdir(tmp_path)
    seen = []
    monkeypatch.setattr(runtime_compat, "_probe_in_subprocess", lambda runtime: seen.append(runtime) or (0, None))

    result = runtime_compat.probe(project, "win64")

    assert result is not None and result.runtime == override and result.override
    assert seen == [override]


def test_probe_uses_the_same_absolute_override_as_install(tmp_path, monkeypatch, win64_process):
    project, _, _ = staged_runtime(tmp_path)
    override = tmp_path / "custom SDK" / "d3d9.dll"
    override.parent.mkdir()
    override.write_bytes(b"override")
    monkeypatch.setenv("REMIX_BUILD", str(override.parent))
    seen = []
    monkeypatch.setattr(runtime_compat, "_probe_in_subprocess", lambda runtime: seen.append(runtime) or (0, None))

    result = runtime_compat.probe(project, "win64")

    assert result is not None and result.runtime == override and result.override
    assert seen == [override]


def test_override_mismatch_names_the_override_instead_of_packman(tmp_path, monkeypatch, capsys, win64_process):
    project, _, _ = staged_runtime(tmp_path)
    override = project / "local SDK" / "d3d9.dll"
    override.parent.mkdir()
    override.write_bytes(b"override")
    monkeypatch.setenv("REMIX_BUILD", "local SDK")
    monkeypatch.setattr(
        runtime_compat,
        "_probe_in_subprocess",
        lambda runtime: (runtime_compat.INCOMPATIBLE_VERSION, None),
    )

    runtime_compat.warn_if_incompatible(project, "win64")

    warning = capsys.readouterr().out
    assert f"REMIX_BUILD, which currently selects {override.parent}" in warning
    assert "sdk packman" not in warning


def test_mixed_process_and_bridge_architectures_are_actionable(tmp_path, monkeypatch):
    project, _, _ = staged_runtime(tmp_path, "arm64")
    monkeypatch.setattr(runtime_compat, "_process_arch", lambda: "win64")
    monkeypatch.setattr(
        runtime_compat,
        "_probe_in_subprocess",
        lambda runtime: (_ for _ in ()).throw(AssertionError("probe called")),
    )

    result = runtime_compat.probe(project, "arm64")

    assert result is not None and not result.incompatible
    assert result.failure is not None
    assert "tools Python process is win64" in result.failure
    assert "native arm64 Python/uv environment" in result.failure


@pytest.mark.parametrize(("machine", "arch"), [(0x8664, "win64"), (0xAA64, "arm64"), (0x014C, None)])
def test_process_arch_reads_the_python_executables_pe_machine(tmp_path, monkeypatch, machine, arch):
    executable = write_jdk(tmp_path / "python", machine=machine) / "bin" / "javaw.exe"
    monkeypatch.setattr(runtime_compat.sys, "executable", str(executable))

    assert runtime_compat._process_arch() == arch


def test_missing_artifacts_are_left_to_the_installer_diagnostics(tmp_path, monkeypatch, capsys):
    monkeypatch.setattr(
        runtime_compat,
        "_probe_in_subprocess",
        lambda runtime: (_ for _ in ()).throw(AssertionError("probe called")),
    )

    result = runtime_compat.warn_if_incompatible(tmp_path, "win64")

    assert result is None
    assert capsys.readouterr().out == ""


@pytest.mark.parametrize("status", [0, runtime_compat.INCOMPATIBLE_VERSION])
def test_direct_probe_initializes_the_exact_vulkan_fork_interface(tmp_path, monkeypatch, status):
    runtime = tmp_path / "d3d9.dll"
    seen = []

    class Initialize:
        def __call__(self, info_pointer, interface_pointer):
            info = ctypes.cast(info_pointer, ctypes.POINTER(runtime_compat.InitializeLibraryInfo)).contents
            assert info.sType == 1 and info.pNext is None and info.version == (1 << 48) | (5 << 16)
            assert ctypes.sizeof(interface_pointer._obj) == runtime_compat.INTERFACE_ENTRY_COUNT * ctypes.sizeof(
                ctypes.c_void_p
            )
            table = ctypes.cast(
                interface_pointer, ctypes.POINTER(ctypes.c_void_p * runtime_compat.INTERFACE_ENTRY_COUNT)
            ).contents
            for i in range(runtime_compat.INTERFACE_ENTRY_COUNT):
                table[i] = i + 1
            return status

    class Library:
        remixapi_InitializeLibrary = Initialize()

    def load(path, *, winmode):
        seen.append((path, winmode))
        return Library()

    monkeypatch.setattr(ctypes, "WinDLL", load)
    assert runtime_compat._probe_in_process(runtime) == status
    assert seen == [(str(runtime), 0x1100)]


def test_probe_table_matches_the_authoritative_copied_header():
    project = Path(__file__).resolve().parents[3]
    header = (project.parent / "kavora-sdk/native/include/remix_c.h").read_text(encoding="utf-8")
    table = re.search(r"typedef struct remixapi_Interface\s*\{(.*?)\}", header, re.DOTALL)
    assert table is not None
    assert len(re.findall(r"PFN_remixapi_", table.group(1))) == runtime_compat.INTERFACE_ENTRY_COUNT
