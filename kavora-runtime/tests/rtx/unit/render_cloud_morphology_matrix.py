#!/usr/bin/env python3
# SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
# SPDX-License-Identifier: MIT
"""Render fixed-input production-shader comparisons across weather and locations.

Without an atmosphere fixture, light cases test solar angle under a constant
radiometric environment, not realistic sunset color. This is never a game capture.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import re
import subprocess
import sys
import time


SCENARIOS = (
    ("fair_broken", 0.45, 0.0, 0.0),
    ("cloud_rich", 0.85, 0.0, 0.0),
    ("mixed_clouds", 0.72, 0.25, 0.0),
    ("stratiform_deck", 0.82, 0.65, 0.0),
    ("storm_transition", 0.85, 1.0, 0.45),
)
LOCATIONS = {
    "user": (8043.0, 139.0, 1240.0),
    "origin": (0.0, 139.0, 0.0),
    "north": (-12000.0, 139.0, 17000.0),
    "far": (35000.0, 139.0, -20000.0),
}
VIEWS = {"top", "zenith", "45", "horizon", "zenith90"}


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[4])
    result.add_argument("--output", type=Path, required=True)
    result.add_argument("--shader-root", type=Path)
    result.add_argument("--reference-shader-root", type=Path)
    result.add_argument("--reference-repo", type=Path,
                        help="Matching reference checkout/test support; inferred from reference shader root when omitted")
    result.add_argument("--cache-dir", type=Path)
    result.add_argument("--atmosphere-fixture", type=Path)
    result.add_argument("--width", type=int, default=512)
    result.add_argument("--height", type=int, default=288)
    result.add_argument("--repeat", type=int, default=3)
    result.add_argument("--views", default="45,horizon,zenith")
    result.add_argument("--locations", default="user,origin,north,far")
    result.add_argument("--scenarios", default=",".join(row[0] for row in SCENARIOS))
    result.add_argument("--layers", choices=("low", "high", "both"), default="both")
    result.add_argument("--steps", type=int, default=384)
    result.add_argument("--high-steps", type=int, default=115)
    result.add_argument("--light-steps", type=int, default=8)
    result.add_argument("--low-max-distance", type=float, default=24000.0)
    result.add_argument("--high-max-distance", type=float, default=60000.0)
    result.add_argument("--lighting-cases", default=(
        "day_side:35:90,day_sunward:35:0,day_antisolar:35:180,sunset_side:2:90"))
    result.add_argument("--skip-radiance", action="store_true")
    result.add_argument("--dry-run", action="store_true", help="Write commands without starting Vulkan or claiming renders")
    result.add_argument("--case-timeout", type=float, default=1800.0)
    return result


def selected_names(value, allowed, label):
    names = value.split(",")
    if not names or any(name not in allowed for name in names) or len(set(names)) != len(names):
        raise ValueError(f"{label} must be distinct names from {', '.join(sorted(allowed))}")
    return names


def plan(args):
    if min(args.width, args.height, args.repeat) < 1:
        raise ValueError("resolution and repeat must be positive")
    if not 1 <= args.steps <= 1024 or not 1 <= args.high_steps <= 1024 or not 1 <= args.light_steps <= 256:
        raise ValueError("view steps must be in [1,1024] and light steps in [1,256]")
    if any(not math.isfinite(value) or value <= 0 for value in
           (args.low_max_distance, args.high_max_distance, args.case_timeout)):
        raise ValueError("ranges and timeout must be positive and finite")
    selected_names(args.views, VIEWS, "views")
    locations = selected_names(args.locations, LOCATIONS, "locations")
    scenarios = selected_names(args.scenarios, {row[0] for row in SCENARIOS}, "scenarios")
    if args.reference_repo and not args.reference_shader_root:
        raise ValueError("reference-repo requires reference-shader-root")
    if args.atmosphere_fixture and args.skip_radiance:
        raise ValueError("atmosphere-fixture requires radiance")
    if args.atmosphere_fixture:
        lighting = [("fixture", None, None)]
    else:
        lighting = []
        for item in args.lighting_cases.split(","):
            name, elevation, azimuth = item.split(":")
            elevation, azimuth = float(elevation), float(azimuth)
            if not re.fullmatch(r"[A-Za-z0-9_-]+", name) or not -90 <= elevation <= 90 or not math.isfinite(azimuth):
                raise ValueError("lighting cases need a safe name, finite azimuth and elevation in [-90,90]")
            if name in {row[0] for row in lighting}:
                raise ValueError("lighting case names must be distinct")
            lighting.append((name, elevation, azimuth))

    variants = [("candidate", args.shader_root, args.repo)]
    if args.reference_shader_root:
        reference_repo = args.reference_repo or args.reference_shader_root.resolve().parents[3]
        if not (reference_repo / "kavora-runtime/tests/rtx/unit/test_cloud_transport.py").is_file():
            raise ValueError("reference shader root needs its matching checkout/test support; pass --reference-repo")
        variants.insert(0, ("reference", args.reference_shader_root, reference_repo))
    cache = args.cache_dir or args.output / "texture-cache"
    cases = []
    for variant, shader_root, repo in variants:
        preview = repo / "kavora-runtime/tests/rtx/unit/render_cloud_preview.py"
        for location in locations:
            for light_name, elevation, azimuth in lighting:
                for name, coverage, weather, thunder in SCENARIOS:
                    if name not in scenarios:
                        continue
                    destination = args.output / variant / location / light_name / name
                    command = [sys.executable, str(preview), "--repo", str(repo),
                               "--output", str(destination), "--cache-dir", str(cache),
                               "--label", f"{variant} / {location} / {name} / {light_name}",
                               "--width", str(args.width), "--height", str(args.height),
                               "--repeat", str(args.repeat), "--warmup", "--views", args.views,
                               "--origin", ",".join(str(value) for value in LOCATIONS[location]),
                               "--yaw", "0", "--layers", args.layers,
                               "--low-base", "800", "--low-thickness", "1500",
                               "--low-shape-scale", "1024", "--low-extinction", "0.03",
                               "--low-coverage", str(coverage), "--weather", str(weather),
                               "--thunder", str(thunder), "--low-max-distance", str(args.low_max_distance),
                               "--high-max-distance", str(args.high_max_distance),
                               "--exposure", "1.0", "--tone-map", "aces",
                               "--steps", str(args.steps), "--high-steps", str(args.high_steps),
                               "--light-steps", str(args.light_steps)]
                    if shader_root:
                        command.extend(["--shader-root", str(shader_root)])
                    if not args.skip_radiance:
                        command.append("--radiance")
                    if args.atmosphere_fixture:
                        command.extend(["--atmosphere-fixture", str(args.atmosphere_fixture), "--air"])
                    else:
                        command.extend(["--sun-elevation", str(elevation), "--sun-azimuth", str(azimuth)])
                    cases.append({"variant": variant, "location": location, "lighting": light_name,
                                  "scenario": name, "output": str(destination), "command": command,
                                  "status": "planned"})
    return cases


def main():
    arg_parser = parser()
    args = arg_parser.parse_args()
    try:
        cases = plan(args)
    except (ValueError, IndexError) as error:
        arg_parser.error(str(error))
    args.output.mkdir(parents=True, exist_ok=True)
    manifest = {"scope": "Offline production Slang preview; no game capture or target-GPU frame-time claim.",
                "environment": "production atmosphere fixture" if args.atmosphere_fixture else "constant diagnostic radiance",
                "startedAt": datetime.now(timezone.utc).isoformat(),
                "status": "planned" if args.dry_run else "running", "cases": cases}
    manifest_path = args.output / "matrix.json"

    def save():
        temporary = manifest_path.with_suffix(".tmp")
        temporary.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
        temporary.replace(manifest_path)

    save()
    if args.dry_run:
        print(f"Planned {len(cases)} cases; no renderer was started: {manifest_path}")
        return
    for case in cases:
        destination = Path(case["output"])
        destination.mkdir(parents=True, exist_ok=True)
        case["status"] = "running"
        save()
        started = time.monotonic()
        preview_manifest = destination / "manifest.json"
        # A successful exit must publish a new completion marker, not reuse a stale run.
        preview_manifest.unlink(missing_ok=True)
        try:
            with (destination / "render.log").open("w", encoding="utf-8") as log:
                subprocess.run(case["command"], stdout=log, stderr=subprocess.STDOUT,
                               check=True, timeout=args.case_timeout)
            preview_result = json.loads(preview_manifest.read_text())
            if not set(args.views.split(",")).issubset(preview_result.get("views", {})):
                raise RuntimeError("renderer did not publish every requested view")
            for view in args.views.split(","):
                required = [f"{view}.npz", f"{view}-opacity.png"]
                if not args.skip_radiance and view != "top":
                    required.append(f"{view}-radiance.png")
                if any(not (destination / name).is_file() or (destination / name).stat().st_size == 0
                       for name in required):
                    raise RuntimeError(f"renderer did not retain the {view} image and raw output")
            case["previewManifestSha256"] = hashlib.sha256(preview_manifest.read_bytes()).hexdigest()
            case["status"] = "complete"
        except Exception as error:
            case["status"] = "failed"
            case["error"] = str(error)
            manifest["status"] = "failed"
            raise
        finally:
            case["elapsedSeconds"] = time.monotonic() - started
            save()
        print(f"Complete: {case['variant']}/{case['location']}/{case['lighting']}/{case['scenario']}", flush=True)
    manifest["status"] = "complete"
    save()
    print(f"Saved matrix: {manifest_path}")


if __name__ == "__main__":
    main()
