# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""Fetch the matching Tracy tools and benchmark runtime without PowerShell."""

from __future__ import annotations

import os
import shutil
import time
import urllib.error
import urllib.request
from pathlib import Path

from .. import sdk
from . import util

# The SDK vendors Tracy v0.8 (protocol 55). Its Windows tools are x86_64 and
# also run on ARM64 hosts under emulation.
TRACY_VERSION = "0.8"


def _windows_exe(path: Path) -> bool:
    if not path.is_file():
        return False
    with path.open("rb") as stream:
        return stream.read(2) == b"MZ"


def _download_file(url: str, destination: Path) -> None:
    curl = shutil.which("curl.exe")
    last_error = ""
    for attempt in range(1, 5):
        destination.unlink(missing_ok=True)
        if attempt > 1:
            time.sleep(min(10, attempt * 2))
        if curl:
            result = util.run(
                [
                    curl,
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
                    destination,
                    url,
                ],
                check=False,
            )
            if result.returncode == 0 and destination.is_file():
                return
            last_error = f"curl.exe exited with code {result.returncode}"
            continue
        try:
            with urllib.request.urlopen(url) as response, destination.open("wb") as output:
                shutil.copyfileobj(response, output)
            return
        except urllib.error.HTTPError as exc:
            last_error = f"HTTP {exc.code} {exc.reason}".rstrip()
        except OSError as exc:
            last_error = str(exc)
    raise RuntimeError(f"download failed after 4 attempts. Last error: {last_error}")


def fetch_tools() -> None:
    """Stage pinned capture/csvexport binaries in tools/tracy-tools.

    Reuse only a matching version with nontrivial Windows executables. Raise
    RuntimeError if downloading, archive validation, or extraction fails.
    """
    tools_dir = util.TOOLS_DIR / "tracy-tools"
    capture = tools_dir / "capture" / "capture.exe"
    csvexport = tools_dir / "csvexport" / "csvexport.exe"
    version_file = tools_dir / "version.txt"
    if capture.is_file() and csvexport.is_file():
        cap_size = capture.stat().st_size
        csv_size = csvexport.stat().st_size
        installed = version_file.read_text(encoding="utf-8-sig").strip() if version_file.is_file() else ""
        if cap_size > 100_000 and csv_size > 100_000 and _windows_exe(capture) and _windows_exe(csvexport):
            if installed == TRACY_VERSION:
                print("Tracy tools already present:")
                print(f"  {capture} ({cap_size} bytes)")
                print(f"  {csvexport} ({csv_size} bytes)")
                print(f"  version: {installed}")
                return

    arch = os.environ.get("PROCESSOR_ARCHITEW6432") or os.environ.get("PROCESSOR_ARCHITECTURE", "")
    if arch.upper() != "AMD64":
        print(f"Host arch '{arch}'; using Tracy's x86_64 Windows tools under emulation.")
    archive_name = f"Tracy-{TRACY_VERSION}.7z"
    url = f"https://github.com/wolfpld/tracy/releases/download/v{TRACY_VERSION}/{archive_name}"
    archive = tools_dir / archive_name
    capture.parent.mkdir(parents=True, exist_ok=True)
    csvexport.parent.mkdir(parents=True, exist_ok=True)
    print(f"Downloading Tracy v{TRACY_VERSION} from GitHub...")
    try:
        _download_file(url, archive)
    except RuntimeError as exc:
        raise RuntimeError(f"Tracy download failed: {exc}") from exc
    archive_size = archive.stat().st_size
    if archive_size < 1_000_000:
        raise RuntimeError(f"Tracy archive is suspiciously small ({archive_size} bytes); aborting.")
    with archive.open("rb") as stream:
        valid_archive = stream.read(6) == b"7z\xbc\xaf\x27\x1c"
    if not valid_archive:
        archive.unlink()
        raise RuntimeError(f"Tracy archive does not look like a {archive_name} download; aborting.")

    print("Extracting...")
    extract_dir = tools_dir / "_extract"
    if extract_dir.exists():
        shutil.rmtree(extract_dir)
    extract_dir.mkdir()
    try:
        tar = shutil.which("tar.exe")
        if not tar:
            raise RuntimeError(f"tar.exe is required to extract {archive_name} but was not found on PATH.")
        result = util.run([tar, "-xf", archive, "-C", extract_dir], check=False)
        if result.returncode != 0:
            raise RuntimeError(f"Failed to extract {archive_name} with tar.exe")
        sources = []
        for name in ("capture", "csvexport"):
            candidates = (extract_dir / f"tracy-{name}.exe", extract_dir / f"{name}.exe")
            source = next((path for path in candidates if path.is_file()), None)
            if source is None:
                raise RuntimeError(f"{name}.exe not found in downloaded Tracy archive.")
            sources.append(source)
        shutil.copy2(sources[0], capture)
        shutil.copy2(sources[1], csvexport)
    finally:
        shutil.rmtree(extract_dir)
    archive.unlink()
    version_file.write_text(TRACY_VERSION, encoding="utf-8")
    print("Tracy tools ready:")
    print(f"  tracy-capture:   {capture}")
    print(f"  tracy-csvexport: {csvexport}")


def fetch_sdk(platform: str) -> None:
    """Validate a source-built Tracy runtime without replacing local staging."""
    if platform not in {"win64", "arm64", "all"}:
        raise RuntimeError(f"invalid SDK platform: {platform}")
    for arch in ("win64", "arm64") if platform == "all" else (platform,):
        directory = util.PROJECT_DIR / "runtime" / arch
        sdk.validate_runtime_provenance(directory, arch)
        if b"___tracy_emit_zone_begin_alloc" not in (directory / "d3d9.dll").read_bytes():
            raise RuntimeError(
                f"Source-built {arch} runtime is not Tracy-enabled: {directory}. Rebuild with Tracy enabled."
            )
    print("Source-built Tracy runtime ready.")
