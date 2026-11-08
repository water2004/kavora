# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import hashlib
import json
import os
import subprocess
from pathlib import Path

import pytest

from kavora_tools import cli, sdk
from kavora_tools.benchmark import runner, util

pytestmark = pytest.mark.usefixtures("isolated_tools_config")


def runtime_bytes(payload: bytes = b"current", machine: int = 0x8664) -> bytes:
    pe = bytearray(134)
    pe[:2] = b"MZ"
    pe[60:64] = (128).to_bytes(4, "little")
    pe[128:132] = b"PE\0\0"
    pe[132:134] = machine.to_bytes(2, "little")
    return bytes(pe) + payload


def write_runtime(path: Path, data: bytes) -> None:
    path.write_bytes(data)
    arch = "arm64" if data[132:134] == b"\x64\xaa" else "win64"
    (path.parent / ".runtime-provenance.json").write_text(
        json.dumps(
            {
                "fork": "water2004/dxvk-remix",
                "apiVersion": "1.5.0",
                "architecture": arch,
                "sourceCommit": "a" * 40,
                "sourceDirty": False,
                "dllSha256": hashlib.sha256(data).hexdigest(),
            }
        ),
        encoding="utf-8",
    )


@pytest.fixture
def project(tmp_path, monkeypatch):
    root = tmp_path / "mod checkout"
    packman = root / "packman"
    packman.mkdir(parents=True)
    (packman / "packman.cmd").write_text("@echo off\n", encoding="utf-8")
    (packman / "kavora-runtime.packman.xml").write_text(
        '<project toolsVersion="7.6">'
        '<dependency name="kavora-runtime-win64" linkPath="../runtime/win64">'
        '<package name="runtime" version="build-win64-release" /></dependency>'
        '<dependency name="kavora-runtime-arm64" linkPath="../runtime/arm64">'
        '<package name="runtime" version="build-arm64-release" /></dependency>'
        "</project>",
        encoding="utf-8",
    )
    monkeypatch.setattr(util, "PROJECT_DIR", root)
    return root


@pytest.fixture
def source(project):
    path = project.parent / "kavora-runtime" / "_output"
    path.mkdir(parents=True)
    write_runtime((path / "d3d9.dll"), runtime_bytes())
    (path / "usd").mkdir()
    (path / "usd" / "plugInfo.json").write_text("{}", encoding="utf-8")
    return path


def make_link(path: Path, target: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if os.name == "nt":
        subprocess.run(["cmd", "/d", "/c", "mklink", "/j", str(path), str(target)], check=True, capture_output=True)
    else:
        path.symlink_to(target, target_is_directory=True)


def test_local_snapshot_copies_runtime_without_powershell(project, source):
    report = sdk.stage_local_sdk("win64", source)
    target = project / "runtime" / "win64"
    assert (target / "d3d9.dll").read_bytes() == (source / "d3d9.dll").read_bytes()
    assert (target / "usd" / "plugInfo.json").is_file()
    sentinel = (target / ".local-sdk-source").read_text(encoding="utf-8")
    assert not sentinel.startswith("\ufeff")
    fields = sdk.parse_local_sdk_source(sentinel)
    assert Path(fields["source"]) == source.resolve()
    assert fields["staged_at_utc"].endswith("Z")
    assert report["state"] == "local-current"
    write_runtime((source / "d3d9.dll"), runtime_bytes(b"new build"))
    assert (target / "d3d9.dll").read_bytes() == runtime_bytes()
    changed = sdk.sdk_source_status("win64")
    assert changed["state"] == "local-refresh-available"
    assert changed["needsRestage"]
    assert changed["canRestage"]


def test_local_defaults_to_sibling_checkout(project, source):
    report = sdk.stage_local_sdk("win64")
    assert Path(report["sourcePath"]) == source.resolve()


def test_local_uses_configured_source_before_sibling_default(project, source):
    configured = project.parent / "local-runtime"
    configured.mkdir()
    write_runtime((configured / "d3d9.dll"), runtime_bytes(b"configured build"))
    util.write_json(util.SHARED_CONFIG_PATH.with_name("config.local.json"), {"sdk": {"localPath": str(configured)}})

    report = sdk.stage_local_sdk("win64")
    assert Path(report["sourcePath"]) == configured.resolve()
    assert (project / "runtime" / "win64" / "d3d9.dll").read_bytes() == runtime_bytes(b"configured build")


def test_local_reuses_recorded_source_and_removes_stale_files(project, source):
    sdk.stage_local_sdk("win64", source)
    target = project / "runtime" / "win64"
    (target / "obsolete.dll").write_bytes(b"old")
    write_runtime((source / "d3d9.dll"), runtime_bytes(b"rebuilt"))
    report = sdk.stage_local_sdk("win64")
    assert report["state"] == "local-current"
    assert (target / "d3d9.dll").read_bytes() == runtime_bytes(b"rebuilt")
    assert not (target / "obsolete.dll").exists()


def test_local_relative_source_is_repo_relative(project, source, monkeypatch):
    monkeypatch.chdir(project.parent)
    report = sdk.stage_local_sdk("win64", "../kavora-runtime/_output")
    assert Path(report["sourcePath"]) == source.resolve()


def test_missing_local_source_names_the_path_it_tried(project):
    with pytest.raises(RuntimeError) as raised:
        sdk.stage_local_sdk("win64", "missing/_output")
    assert str((project / "missing" / "_output").resolve()) in str(raised.value)
    assert "a relative path starts at the repository root" in str(raised.value)


@pytest.mark.parametrize("kind", ["missing", "no-dll", "wrong-arch", "invalid-pe", "target", "child", "parent"])
def test_bad_local_source_never_removes_current_runtime(project, source, kind):
    sdk.stage_local_sdk("win64", source)
    target = project / "runtime" / "win64"
    if kind == "missing":
        source = project / "missing"
    elif kind == "no-dll":
        (source / "d3d9.dll").unlink()
    elif kind == "wrong-arch":
        write_runtime((source / "d3d9.dll"), runtime_bytes(machine=0xAA64))
    elif kind == "invalid-pe":
        (source / "d3d9.dll").write_bytes(b"not a DLL")
    elif kind == "target":
        source = target
    elif kind == "child":
        source = target / "child"
        source.mkdir()
        write_runtime((source / "d3d9.dll"), runtime_bytes())
    elif kind == "parent":
        source = target.parent
        write_runtime((source / "d3d9.dll"), runtime_bytes())
    with pytest.raises(RuntimeError):
        sdk.stage_local_sdk("win64", source)
    assert (target / "d3d9.dll").read_bytes() == runtime_bytes()
    assert (target / ".local-sdk-source").is_file()


def test_local_accepts_arm64_runtime(project, source):
    write_runtime((source / "d3d9.dll"), runtime_bytes(machine=0xAA64))
    assert sdk.stage_local_sdk("arm64", source)["state"] == "local-current"


def test_local_replaces_junction_without_changing_cache(project, source):
    cache = project.parent / "cache"
    cache.mkdir()
    (cache / "d3d9.dll").write_bytes(b"cached runtime")
    target = project / "runtime" / "win64"
    make_link(target, cache)
    sdk.stage_local_sdk("win64", source)
    assert (cache / "d3d9.dll").read_bytes() == b"cached runtime"
    assert not (cache / ".local-sdk-source").exists()
    assert not target.is_junction()
    assert not target.is_symlink()
    assert (target / "d3d9.dll").read_bytes() == runtime_bytes()


@pytest.mark.parametrize("dangling", [False, True])
def test_removing_snapshot_preserves_nested_link_targets(project, dangling):
    external = project.parent / "shared cache"
    external.mkdir()
    payload = external / "runtime.dll"
    payload.write_bytes(b"keep")
    target = project / "runtime" / "win64"
    make_link(target / "linked", external)
    if dangling:
        payload.unlink()
        external.rmdir()
    sdk.remove_target(target)
    assert not target.exists()
    if not dangling:
        assert payload.read_bytes() == b"keep"


def test_fetch_refuses_local_override_without_running_packman(project, source, monkeypatch):
    sdk.stage_local_sdk("win64", source)
    monkeypatch.setattr(util, "run", lambda *a, **kw: pytest.fail("Packman must not touch a local override"))
    with pytest.raises(RuntimeError, match="must be built"):
        sdk.fetch_sdk("win64")
    assert sdk.sdk_source_status("win64")["state"] == "local-current"


@pytest.mark.parametrize("arch", ["win64", "arm64"])
@pytest.mark.parametrize("force", [False, True])
def test_packman_rejects_incompatible_runtime_without_touching_local_snapshot(
    project, source, monkeypatch, arch, force
):
    sdk.stage_local_sdk("win64", source)
    target = project / "runtime/win64/d3d9.dll"
    original = target.read_bytes()
    monkeypatch.setattr(util, "run", lambda *a, **kw: pytest.fail("Incompatible Packman runtime must not be fetched"))
    with pytest.raises(RuntimeError, match="incompatible ABI"):
        sdk.stage_packman_sdk(arch, force=force)
    assert target.read_bytes() == original
    assert sdk.sdk_source_status("win64")["state"] == "local-current"


def test_local_requires_provenance_before_replacing_existing_snapshot(project, source):
    sdk.stage_local_sdk("win64", source)
    target = project / "runtime/win64/d3d9.dll"
    original = target.read_bytes()
    (source / ".runtime-provenance.json").unlink()
    with pytest.raises(RuntimeError, match="provenance is missing"):
        sdk.stage_local_sdk("win64", source)
    assert target.read_bytes() == original


@pytest.mark.parametrize(
    "field,value", [("apiVersion", "0.16.0"), ("fork", "other"), ("architecture", "arm64"), ("dllSha256", "0" * 64)]
)
def test_provenance_binds_fork_abi_architecture_and_dll(project, source, field, value):
    marker = source / ".runtime-provenance.json"
    data = json.loads(marker.read_text(encoding="utf-8"))
    data[field] = value
    marker.write_text(json.dumps(data), encoding="utf-8")
    with pytest.raises(RuntimeError, match="provenance"):
        sdk.stage_local_sdk("win64", source)
    assert not (project / "runtime/win64").exists()


def test_restage_requires_a_local_source(project, source):
    with pytest.raises(RuntimeError, match="currently uses Packman"):
        sdk.restage_local_sdk("win64")
    sdk.stage_local_sdk("win64", source)
    (source / "d3d9.dll").unlink()
    report = sdk.sdk_source_status("win64")
    assert report["state"] == "local-source-missing"
    assert not report["canRestage"]
    assert report["dllExists"]
    with pytest.raises(RuntimeError, match="does not contain d3d9.dll"):
        sdk.restage_local_sdk("win64")


@pytest.mark.parametrize("auto_restage", [False, True])
def test_launch_refreshes_changed_local_source_only_when_enabled(project, source, capsys, auto_restage):
    sdk.stage_local_sdk("win64", source)
    write_runtime((source / "d3d9.dll"), runtime_bytes(b"rebuilt"))
    dll = runner.fetch_sdk("release", "win64", auto_restage=auto_restage)
    assert dll.read_bytes() == runtime_bytes(b"rebuilt" if auto_restage else b"current")
    output = capsys.readouterr().out
    if auto_restage:
        assert "WARNING" not in output
    else:
        assert r"Refresh it with: tools\kavora.bat sdk restage" in output
        assert "sdk auto-restage on" in output


def test_sdk_cli_preserves_positional_path_and_accepts_named_path(project, source):
    parser = cli.build_parser()
    for path_args in ([str(source)], ["--path", str(source)]):
        args = parser.parse_args(["sdk", "local", *path_args, "--arch", "win64"])
        assert args.func(args) == 0
        assert sdk.sdk_source_status("win64")["state"] == "local-current"
    with pytest.raises(SystemExit):
        parser.parse_args(["sdk", "local", str(source), "--path", str(source)])


def test_auto_restage_command_sets_and_reports_local_setting(project, monkeypatch, capsys):
    path = project / "config.json"
    original = '{\n    "playerName": "Steve",\n    "sdk": { "autoRestage": true }\n}\n'
    path.write_text(original, encoding="utf-8")
    local = project / "config.local.json"
    local.write_text('{"world": {"name": "DemoWorld"}}', encoding="utf-8")
    monkeypatch.setattr(util, "SHARED_CONFIG_PATH", path)
    parser = cli.build_parser()
    args = parser.parse_args(["sdk", "auto-restage", "off"])
    assert args.func(args) == 0
    assert "SDK auto-restage: off" in capsys.readouterr().out
    assert path.read_text(encoding="utf-8") == original
    assert util.read_json(local) == {"world": {"name": "DemoWorld"}, "sdk": {"autoRestage": False}}
    args = parser.parse_args(["sdk", "auto-restage", "--json"])
    assert args.func(args) == 0
    assert json.loads(capsys.readouterr().out) == {"autoRestage": False}
    sdk.set_sdk_auto_restage(True)
    assert path.read_text(encoding="utf-8") == original
    assert util.read_json(local) == {"world": {"name": "DemoWorld"}, "sdk": {"autoRestage": True}}
    assert sdk.configured_sdk_auto_restage({})
    assert sdk.configured_sdk_auto_restage({"sdk": {}})


def test_set_auto_restage_creates_local_override_without_changing_shipped_config(project, monkeypatch):
    path = project / "config.json"
    original = '{"playerName": "Steve"}'
    path.write_text(original, encoding="utf-8")
    monkeypatch.setattr(util, "SHARED_CONFIG_PATH", path)
    sdk.set_sdk_auto_restage(False)
    assert util.read_json(project / "config.local.json") == {"sdk": {"autoRestage": False}}
    assert path.read_text(encoding="utf-8") == original


def test_status_json_is_script_friendly(project, capsys):
    args = cli.build_parser().parse_args(["sdk", "status", "--arch", "arm64", "--json"])
    assert args.func(args) == 0
    report = json.loads(capsys.readouterr().out)
    assert report["arch"] == "arm64"
    assert report["sourceType"] == "packman"
    assert report["state"] == "packman-missing"
