# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import hashlib
import io
import json
import subprocess
import urllib.error
from email.message import Message
from pathlib import Path
from unittest.mock import Mock

import pytest

from rtx_tools import cli
from rtx_tools.benchmark import tracy

pytestmark = pytest.mark.usefixtures("isolated_tools_config")

EXE_BYTES = b"MZ" + b"\0" * 100_000
ARCHIVE_BYTES = b"7z\xbc\xaf\x27\x1c" + b"\0" * 1_000_000


@pytest.fixture
def tools_dir(tmp_path, monkeypatch):
    monkeypatch.setattr(tracy.util, "TOOLS_DIR", tmp_path)
    return tmp_path / "tracy-tools"


def installed_tools(tools_dir: Path) -> tuple[Path, Path]:
    capture = tools_dir / "capture" / "capture.exe"
    csvexport = tools_dir / "csvexport" / "csvexport.exe"
    for binary in (capture, csvexport):
        binary.parent.mkdir(parents=True)
        binary.write_bytes(EXE_BYTES)
    (tools_dir / "version.txt").write_text("0.8", encoding="utf-8")
    return capture, csvexport


def mock_tools_download(monkeypatch, names=("tracy-capture.exe", "tracy-csvexport.exe")):
    download = Mock(return_value=io.BytesIO(ARCHIVE_BYTES))
    monkeypatch.setattr(tracy.urllib.request, "urlopen", download)
    monkeypatch.setattr(tracy.shutil, "which", lambda name: "tar.exe" if name == "tar.exe" else None)

    def extract(args, **kwargs):
        assert args[0] == "tar.exe"
        assert args[1] == "-xf"
        assert args[3] == "-C"
        for name in names:
            (Path(args[4]) / name).write_bytes(EXE_BYTES)
        return subprocess.CompletedProcess(args, 0)

    monkeypatch.setattr(tracy.util, "run", extract)
    return download


def test_tools_reuse_only_matching_valid_binaries(tools_dir, monkeypatch):
    installed_tools(tools_dir)
    (tools_dir / "version.txt").write_text(" 0.8\n", encoding="utf-8")
    download = Mock(side_effect=AssertionError("must not download"))
    monkeypatch.setattr(tracy.urllib.request, "urlopen", download)
    tracy.fetch_tools()
    download.assert_not_called()


@pytest.mark.parametrize("names", [("tracy-capture.exe", "tracy-csvexport.exe"), ("capture.exe", "csvexport.exe")])
def test_tools_download_pinned_release_to_harness_paths(tools_dir, monkeypatch, names):
    download = mock_tools_download(monkeypatch, names)
    tracy.fetch_tools()
    assert download.call_args.args[0] == "https://github.com/wolfpld/tracy/releases/download/v0.8/Tracy-0.8.7z"
    assert (tools_dir / "capture" / "capture.exe").read_bytes() == EXE_BYTES
    assert (tools_dir / "csvexport" / "csvexport.exe").read_bytes() == EXE_BYTES
    assert (tools_dir / "version.txt").read_text(encoding="utf-8") == "0.8"
    assert not (tools_dir / "_extract").exists()
    assert not (tools_dir / "Tracy-0.8.7z").exists()


def test_tools_cli_stages_binaries_without_runtime(tools_dir, monkeypatch):
    mock_tools_download(monkeypatch)
    assert cli.main(["sdk", "tracy-tools"]) == 0
    assert (tools_dir / "capture" / "capture.exe").read_bytes() == EXE_BYTES
    assert (tools_dir / "csvexport" / "csvexport.exe").read_bytes() == EXE_BYTES
    assert (tools_dir / "version.txt").read_text(encoding="utf-8") == tracy.TRACY_VERSION


@pytest.mark.parametrize("invalid", ["missing", "small", "bad_magic", "wrong_version", "missing_version"])
@pytest.mark.parametrize("binary", [0, 1])
def test_tools_refetch_invalid_installation(tools_dir, monkeypatch, invalid, binary):
    binaries = installed_tools(tools_dir)
    if invalid == "missing":
        binaries[binary].unlink()
    elif invalid == "small":
        binaries[binary].write_bytes(b"MZ")
    elif invalid == "bad_magic":
        binaries[binary].write_bytes(b"xx" + EXE_BYTES[2:])
    elif invalid == "wrong_version":
        (tools_dir / "version.txt").write_text("0.9", encoding="utf-8")
    else:
        (tools_dir / "version.txt").unlink()
    download = mock_tools_download(monkeypatch)
    tracy.fetch_tools()
    assert download.call_count == 1
    assert binaries[binary].read_bytes() == EXE_BYTES
    assert (tools_dir / "version.txt").read_text(encoding="utf-8") == "0.8"


@pytest.mark.parametrize(
    ("payload", "message", "archive_retained"),
    [(b"7z", "suspiciously small", True), (b"x" * 1_000_000, "does not look like", False)],
    ids=["too-small", "bad-magic"],
)
def test_tools_reject_invalid_archives(tools_dir, monkeypatch, payload, message, archive_retained):
    mock_tools_download(monkeypatch)
    monkeypatch.setattr(tracy.urllib.request, "urlopen", Mock(return_value=io.BytesIO(payload)))
    with pytest.raises(RuntimeError, match=message):
        tracy.fetch_tools()
    assert (tools_dir / "Tracy-0.8.7z").exists() == archive_retained
    assert not (tools_dir / "version.txt").exists()
    assert not (tools_dir / "_extract").exists()


@pytest.mark.parametrize("missing", ["capture", "csvexport"])
def test_tools_reject_incomplete_archive_without_replacing_installation(tools_dir, monkeypatch, missing):
    binaries = installed_tools(tools_dir)
    (tools_dir / "version.txt").write_text("0.7", encoding="utf-8")
    names = (f"tracy-{'csvexport' if missing == 'capture' else 'capture'}.exe",)
    mock_tools_download(monkeypatch, names)
    with pytest.raises(RuntimeError, match=f"{missing}.exe not found"):
        tracy.fetch_tools()
    assert all(binary.read_bytes() == EXE_BYTES for binary in binaries)
    assert (tools_dir / "version.txt").read_text(encoding="utf-8") == "0.7"
    assert not (tools_dir / "_extract").exists()


@pytest.mark.parametrize("tar_available", [False, True])
def test_tools_report_missing_or_failing_extractor(tools_dir, monkeypatch, tar_available):
    mock_tools_download(monkeypatch)
    monkeypatch.setattr(tracy.shutil, "which", lambda name: "tar.exe" if tar_available and name == "tar.exe" else None)
    monkeypatch.setattr(tracy.util, "run", Mock(return_value=subprocess.CompletedProcess([], 1)))
    message = "Failed to extract" if tar_available else "tar.exe is required"
    with pytest.raises(RuntimeError, match=message):
        tracy.fetch_tools()
    assert not (tools_dir / "_extract").exists()
    assert not (tools_dir / "version.txt").exists()


def test_download_retries_curl_with_original_limits_and_removes_partial_file(tmp_path, monkeypatch):
    output = tmp_path / "download.7z"
    calls = []

    def curl(args, **kwargs):
        assert not output.exists()
        calls.append(args)
        output.write_bytes(b"partial" if len(calls) == 1 else ARCHIVE_BYTES)
        return subprocess.CompletedProcess(args, 22 if len(calls) == 1 else 0)

    monkeypatch.setattr(tracy.shutil, "which", Mock(return_value="curl.exe"))
    monkeypatch.setattr(tracy.util, "run", curl)
    sleep = Mock()
    monkeypatch.setattr(tracy.time, "sleep", sleep)
    tracy._download_file("https://example.test/tools.7z", output)
    assert len(calls) == 2
    assert calls[0] == [
        "curl.exe",
        "--fail",
        "--location",
        "--silent",
        "--show-error",
        "--retry",
        "5",
        "--retry-delay",
        "2",
        "--connect-timeout",
        "30",
        "--max-time",
        "300",
        "--output",
        output,
        "https://example.test/tools.7z",
    ]
    assert output.read_bytes() == ARCHIVE_BYTES
    sleep.assert_called_once_with(4)


def test_download_reports_four_failed_attempts(tmp_path, monkeypatch):
    monkeypatch.setattr(tracy.shutil, "which", Mock(return_value=None))
    failure = urllib.error.HTTPError("https://example.test/tools.7z", 503, "Unavailable", Message(), None)
    download = Mock(side_effect=failure)
    monkeypatch.setattr(tracy.urllib.request, "urlopen", download)
    sleep = Mock()
    monkeypatch.setattr(tracy.time, "sleep", sleep)
    with pytest.raises(RuntimeError, match="after 4 attempts.*HTTP 503 Unavailable"):
        tracy._download_file("https://example.test/tools.7z", tmp_path / "download.7z")
    assert download.call_count == 4
    assert [call.args[0] for call in sleep.call_args_list] == [4, 6, 8]


@pytest.fixture
def project(tmp_path, monkeypatch, isolated_tools_config):
    packman_dir = tmp_path / "packman"
    packman_dir.mkdir()
    (packman_dir / "packman.cmd").write_text("", encoding="utf-8")
    (packman_dir / "remix-sdk.packman.xml").write_text(
        '<project toolsVersion="7.6">'
        '<dependency name="remix-sdk-win64" linkPath="../remix-runtime/win64">'
        '<package name="rtx_remix_runtime" version="revision-win64-release" platforms="windows-x86_64" />'
        '</dependency><dependency name="remix-sdk-arm64" linkPath="../remix-runtime/arm64">'
        '<package name="rtx_remix_runtime" version="revision-arm64-release" />'
        "</dependency></project>",
        encoding="utf-8",
    )
    benchmark_dir = tmp_path / "tools" / "src" / "rtx_tools" / "benchmark"
    benchmark_dir.mkdir(parents=True)
    (benchmark_dir / "config.json").write_text("{}", encoding="utf-8")
    tracy.util.write_json(isolated_tools_config.with_name("config.local.json"), {"sdk": {"tracyRemote": "demo-sdk"}})
    monkeypatch.setattr(tracy.util, "PROJECT_DIR", tmp_path)
    monkeypatch.setattr(tracy.util, "BENCHMARK_DIR", benchmark_dir)
    return tmp_path


def staged_sdk(project: Path, platform: str = "win64") -> Path:
    target = project / "remix-runtime" / platform
    target.mkdir(parents=True)
    (target / "d3d9.dll").write_bytes(b"old runtime")
    return target


@pytest.mark.parametrize("platform", ["win64", "arm64", "all"])
def test_tracy_sdk_requires_source_provenance_without_pulling_packages(project, monkeypatch, platform):
    target = staged_sdk(project)
    monkeypatch.setattr(tracy.util, "run", lambda *a, **kw: pytest.fail("Old runtime packages must not be fetched"))
    with pytest.raises(RuntimeError, match="provenance is missing"):
        tracy.fetch_sdk(platform)
    assert (target / "d3d9.dll").read_bytes() == b"old runtime"


def test_tracy_sdk_rejects_unsupported_platform(project):
    with pytest.raises(RuntimeError, match="invalid SDK platform"):
        tracy.fetch_sdk("linux")


@pytest.mark.parametrize("tracy_enabled", [False, True])
def test_tracy_sdk_preserves_and_checks_source_built_runtime(project, tracy_enabled):
    target = staged_sdk(project)
    dll = target / "d3d9.dll"
    dll.write_bytes(b"source runtime" + (b"___tracy_emit_zone_begin_alloc" if tracy_enabled else b""))
    (target / ".runtime-provenance.json").write_text(
        json.dumps(
            {
                "fork": "water2004/dxvk-remix",
                "apiVersion": "1.5.0",
                "architecture": "win64",
                "sourceCommit": "a" * 40,
                "sourceDirty": True,
                "dllSha256": hashlib.sha256(dll.read_bytes()).hexdigest(),
            }
        ),
        encoding="utf-8",
    )
    original = dll.read_bytes()
    if tracy_enabled:
        tracy.fetch_sdk("win64")
    else:
        with pytest.raises(RuntimeError, match="not Tracy-enabled"):
            tracy.fetch_sdk("win64")
    assert dll.read_bytes() == original
