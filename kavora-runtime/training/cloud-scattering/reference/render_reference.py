# SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
# SPDX-License-Identifier: Apache-2.0
"""Generate the offline reference dataset (report §5).

Every job freezes one density snapshot + one lighting configuration + one
camera and renders a high-sample-count path-traced HDR image.  Two lighting
*channels* are produced, because the medium is linear and the two responses
compose with arbitrary RGB weights at runtime:

    sun   unit irradiance, black sky      (existing channel)
    sky   unit radiance over the upper hemisphere, no sun

Output layout (report §5.1):

    outputs/dataset/<job_id>/
        density.npy          # (nz, ny, nx) float32, the same array the runtime loads
        scene.json           # bounds, kappa, albedo, g, sun, camera, seed
        reference.exr        # linear HDR, full multiple scattering
        meta.json            # spp, seed, checksum, render time
    outputs/dataset/index.json     # job list + splits

Usage
-----
    python -m reference.render_reference --split train --spp 512
    python -m reference.render_reference --split train_sky --spp 512
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import time

import numpy as np
import torch

from cloudfield.density import get_cloud_grid
from common.config import (TEST_VIEWS, TRAIN_VIEWS, VAL_VIEWS, default_config, save_config,
                           sun_direction, view_cfg)
from common.io_hdr import hdr_stats, write_exr
from reference.pathtracer import VolumePathTracer
from runtime.renderer import VolumeRenderer
from runtime.volumegrid import VolumeGrid

DATASET_ROOT = "outputs/dataset"

# Scene variants: the density generator is deterministic in ``scene``
SCENES = {
    "A": {"seed": 17, "coverage": 0.55, "cloud_type": "cumulus"},
    "B": {"seed": 47, "coverage": 0.45, "cloud_type": "cumulus"},
    "C": {"seed": 91, "coverage": 0.70, "cloud_type": "cumulus"},
    "D": {"seed": 137, "coverage": 0.85, "cloud_type": "stratocumulus"},
    "E": {"seed": 211, "coverage": 0.78, "cloud_type": "stratocumulus"},
}

LOW_SUN_HOLDOUT_VIEW = {"name": "e2", "position": [-2200.0, 520.0, 3500.0],
                        "target": [400.0, 1600.0, -700.0]}

# split, scene, sun elevation (deg), view, light channel
JOB_SPECS = [
    ("train", "A", 25.0, TRAIN_VIEWS[0], "sun"),
    ("train", "A", 25.0, TRAIN_VIEWS[1], "sun"),
    ("train", "A", 25.0, TRAIN_VIEWS[2], "sun"),
    ("train", "A", 25.0, TRAIN_VIEWS[3], "sun"),
    ("train", "A", 25.0, TRAIN_VIEWS[4], "sun"),
    ("train", "A", 25.0, TRAIN_VIEWS[5], "sun"),
    ("train", "A", 25.0, TRAIN_VIEWS[6], "sun"),
    ("train", "A", 25.0, TRAIN_VIEWS[7], "sun"),
    ("train", "A", 25.0, TRAIN_VIEWS[8], "sun"),
    ("train", "A", 25.0, TRAIN_VIEWS[9], "sun"),
    ("train", "A", 25.0, TRAIN_VIEWS[10], "sun"),
    ("train", "A", 25.0, TRAIN_VIEWS[11], "sun"),
    ("val", "A", 25.0, VAL_VIEWS[0], "sun"),
    ("val", "A", 25.0, VAL_VIEWS[1], "sun"),
    ("test_view", "B", 25.0, TRAIN_VIEWS[0], "sun"),
    ("test_view", "B", 25.0, TRAIN_VIEWS[1], "sun"),
    ("test_seed", "C", 25.0, TEST_VIEWS[0], "sun"),
    ("test_sun", "A", 60.0, TRAIN_VIEWS[0], "sun"),
    ("test_sun", "A", 8.0, TRAIN_VIEWS[0], "sun"),
    # ---- sky channel -------------------------------------------------------
    ("train_sky", "A", 25.0, TRAIN_VIEWS[0], "sky"),
    ("train_sky", "A", 25.0, TRAIN_VIEWS[1], "sky"),
    ("train_sky", "A", 25.0, TRAIN_VIEWS[2], "sky"),
    ("train_sky", "A", 25.0, TRAIN_VIEWS[3], "sky"),
    ("train_sky", "A", 25.0, TRAIN_VIEWS[4], "sky"),
    ("train_sky", "A", 25.0, TRAIN_VIEWS[5], "sky"),
    ("train_sky", "A", 25.0, TRAIN_VIEWS[6], "sky"),
    ("train_sky", "A", 25.0, TRAIN_VIEWS[7], "sky"),
    ("train_sky", "A", 25.0, TRAIN_VIEWS[8], "sky"),
    ("train_sky", "A", 25.0, TRAIN_VIEWS[9], "sky"),
    ("train_sky", "A", 25.0, TRAIN_VIEWS[10], "sky"),
    ("train_sky", "A", 25.0, TRAIN_VIEWS[11], "sky"),
    ("val_sky", "A", 25.0, VAL_VIEWS[0], "sky"),
    ("val_sky", "A", 25.0, VAL_VIEWS[1], "sky"),
    ("test_view_sky", "B", 25.0, TRAIN_VIEWS[0], "sky"),
    ("test_view_sky", "B", 25.0, TRAIN_VIEWS[1], "sky"),
    ("test_seed_sky", "C", 25.0, TEST_VIEWS[0], "sky"),
]


def job_id(scene: str, sun: float, view: dict, light: str = "sun") -> str:
    suffix = "" if light == "sun" else "_sky"
    return f"{scene}_s{int(sun):02d}_{view['name']}{suffix}"


def make_cfg(scene_key: str, sun_elev: float, light: str = "sun") -> dict:
    cfg = default_config()
    cfg["name"] = f"scene_{scene_key}"
    cfg["scene"].update(SCENES[scene_key])
    cfg["lighting"]["solar_elevation_deg"] = float(sun_elev)
    if light == "sky":
        cfg["lighting"]["sun_irradiance"] = 0.0
        cfg["lighting"]["sky_radiance"] = 1.0
    else:
        cfg["lighting"]["sun_irradiance"] = 1.0
        cfg["lighting"]["sky_radiance"] = 0.0
    return cfg


def checksum(arr: np.ndarray) -> str:
    return hashlib.sha1(np.ascontiguousarray(arr, dtype=np.float32).tobytes()).hexdigest()[:16]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--split", default="all", help=(
        "Comma-separated splits or all. Use low_sun_train for eight low-elevation training views."))
    ap.add_argument("--width", type=int, default=160)
    ap.add_argument("--height", type=int, default=90)
    ap.add_argument("--spp", type=int, default=512)
    ap.add_argument("--max-depth", type=int, default=8,
                    help="path depth cap (8 reproduces exp4; check depth convergence for new targets)")
    ap.add_argument("--seed", type=int, default=2024)
    ap.add_argument("--device", default="cuda")
    ap.add_argument("--out", default=DATASET_ROOT)
    ap.add_argument("--only", default=None, help="comma separated job-id substrings")
    ap.add_argument("--smoke", action="store_true", help="Use small density and light grids for pipeline validation")
    args = ap.parse_args()

    if args.split == "stress":
        specs = [("stress", "D", 5.0, TEST_VIEWS[1], "sun"),
                 ("stress_sky", "D", 5.0, TEST_VIEWS[1], "sky")]
    elif args.split == "diagnostic":
        specs = [("diagnostic", scene, sun, TEST_VIEWS[1], "sun")
                 for scene, sun in (("A", 5.0), ("A", 25.0), ("D", 25.0))]
    elif args.split == "low_sun_train":
        specs = [("train", "A", sun, TRAIN_VIEWS[i], "sun")
                 for sun in (5.0, 12.0) for i in (0, 3, 8, 10)]
    elif args.split == "holdout":
        specs = [("holdout", "E", 7.0, LOW_SUN_HOLDOUT_VIEW, "sun"),
                 ("holdout_sky", "E", 7.0, LOW_SUN_HOLDOUT_VIEW, "sky")]
    else:
        splits = set(args.split.split(","))
        specs = [s for s in JOB_SPECS if "all" in splits or s[0] in splits]
    if args.only:
        wanted = [v.strip() for v in args.only.split(",") if v.strip()]
        specs = [s for s in specs if any(w in job_id(s[1], s[2], s[3], s[4]) for w in wanted)]
    if not specs:
        ap.error("no reference jobs match this split and filter")

    index_path = os.path.join(args.out, "index.json")
    index = {"jobs": [], "meta": vars(args)}
    if os.path.exists(index_path):
        with open(index_path, encoding="utf-8") as fh:
            old = json.load(fh)
        replaced = {job_id(s[1], s[2], s[3], s[4]) for s in specs}
        index["jobs"] = [j for j in old.get("jobs", []) if j["id"] not in replaced]

    groups: dict[tuple, list] = {}
    for split, scene, sun, view, light in specs:
        groups.setdefault((scene, sun, light), []).append((split, view))

    for (scene_key, sun, light), jobs in groups.items():
        cfg = make_cfg(scene_key, sun, light)
        if args.smoke:
            cfg["scene"]["resolution"] = [32, 16, 32]
            cfg["lighting"]["sun_cache_resolution"] = [24, 12, 24]
            cfg["lighting"]["sun_cache_steps"] = 16
        t0 = time.perf_counter()
        density = get_cloud_grid(cfg)
        renderer = VolumeRenderer(cfg, density, device=args.device)
        if args.device == "cuda":
            torch.cuda.synchronize()
        print(f"[scene {scene_key} sun {sun} light {light}] density+cache ready "
              f"in {time.perf_counter() - t0:.1f}s", flush=True)
        grid = VolumeGrid(density, renderer.lo, renderer.hi, device=renderer.device)
        tracer = VolumePathTracer(cfg, grid, renderer.sun_cache, device=args.device)

        for split, view in jobs:
            jid = job_id(scene_key, sun, view, light)
            out_dir = os.path.join(args.out, jid)
            os.makedirs(out_dir, exist_ok=True)
            vcfg = view_cfg(cfg, view)
            t0 = time.perf_counter()
            res = tracer.render(vcfg, args.width, args.height, spp=args.spp,
                                max_depth=args.max_depth, samples_per_batch=32,
                                seed=args.seed)
            if args.device == "cuda":
                torch.cuda.synchronize()
            dt = time.perf_counter() - t0
            img = res["color"].detach().cpu().numpy()
            write_exr(os.path.join(out_dir, "reference.exr"), img)
            np.save(os.path.join(out_dir, "density.npy"), density)
            job_cfg = {k: v for k, v in cfg.items() if k not in ("views", "val_views", "test_views")}
            save_config(job_cfg, os.path.join(out_dir, "scene.json"))
            meta = {
                "id": jid, "split": split, "scene": scene_key, "light": light,
                "sun_elevation_deg": sun, "sun_direction": sun_direction(cfg).tolist(),
                "view": vcfg, "width": args.width, "height": args.height,
                "spp": args.spp, "max_depth": args.max_depth, "seed": args.seed,
                "render_seconds": round(dt, 2),
                "checksum": checksum(img), "stats": hdr_stats(img),
            }
            with open(os.path.join(out_dir, "meta.json"), "w", encoding="utf-8") as fh:
                json.dump(meta, fh, indent=2)
            index["jobs"].append({
                "id": jid, "split": split, "dir": out_dir,
                "image": os.path.join(out_dir, "reference.exr"),
                "scene": scene_key, "light": light, "sun_elevation_deg": sun,
                "view": view, "spp": args.spp, "max_depth": args.max_depth, "seed": args.seed,
                "width": args.width, "height": args.height,
            })
            print(f"  {jid:22s} {args.width}x{args.height} spp={args.spp} "
                  f"{dt:6.1f}s  mean={meta['stats']['mean']:.5f}", flush=True)

    os.makedirs(args.out, exist_ok=True)
    index["jobs"].sort(key=lambda j: j["id"])
    with open(index_path, "w", encoding="utf-8") as fh:
        json.dump(index, fh, indent=2)
    print(f"[done] {len(index['jobs'])} jobs in {index_path}")


if __name__ == "__main__":
    main()
