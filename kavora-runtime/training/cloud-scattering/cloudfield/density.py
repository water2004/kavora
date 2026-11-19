# SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
# SPDX-License-Identifier: Apache-2.0
"""Procedural cloud density: weather map + 3D fractal noise + height shaping.

The generator is a pure function of ``(config, seed)`` and writes a single
``(nz, ny, nx)`` float32 voxel grid.  The *same* grid is consumed by the
offline reference renderer, the training loop and the interactive runtime, so
that reference and runtime can never disagree about geometry (report §4.1).
"""
from __future__ import annotations

import json
import os

import numpy as np

from common.config import bounds, config_hash, resolution
from cloudfield.noise import billow3, fbm2, fbm3, smoothstep


# Bump whenever the generator maths changes so cached grids are invalidated.
GENERATOR_VERSION = 3

CLOUD_TYPES = {
    # top_min/top_max: height fraction where the cloud top lives
    # base_jitter: amplitude of the 2D variation of the cloud base
    # detail: strength of the high frequency erosion
    "cumulus": dict(top_min=0.55, top_max=1.00, base_jitter=0.06, detail=0.42),
    "stratocumulus": dict(top_min=0.30, top_max=0.55, base_jitter=0.03, detail=0.30),
    "broken": dict(top_min=0.40, top_max=0.85, base_jitter=0.08, detail=0.50),
}


def build_cloud_grid(cfg: dict) -> np.ndarray:
    """Generate the canonical cloud density field, shape ``(nz, ny, nx)``."""
    scene = cfg["scene"]
    nx, ny, nz = resolution(cfg)
    seed = int(scene["seed"])
    coverage = float(np.clip(scene["coverage"], 0.0, 1.0))
    ctype = CLOUD_TYPES[str(scene.get("cloud_type", "cumulus"))]
    detail_scale = float(scene.get("detail_scale", 1.0))

    # ---- 2D weather map (periodic over the domain) -------------------------
    weather = fbm2((nz, nx), cells=(3, 3), octaves=4, seed=seed + 11)
    if coverage <= 1e-4:
        return np.zeros((nz, ny, nx), dtype=np.float32)
    thr = float(np.quantile(weather, 1.0 - coverage))
    cover = smoothstep(thr - 0.09, thr + 0.09, weather)          # 0..1 mask

    # ---- vertical envelope -------------------------------------------------
    # t = 0 at the (locally varying) cloud base, t = 1 at the cloud top.
    # The threshold grows with t, so only the strongest noise columns reach the
    # top -> cauliflower towers instead of a flat cloud deck.
    h = np.linspace(0.0, 1.0, ny, dtype=np.float32)[None, :, None]  # (1, ny, 1)
    ref = fbm2((nz, nx), cells=(9, 9), octaves=3, seed=seed + 15)
    base2d = 0.03 + ctype["base_jitter"] * ref                       # (nz, nx)
    top2d = (ctype["top_min"] + (ctype["top_max"] - ctype["top_min"]) * cover
             + 0.10 * (ref - 0.5))
    top2d = np.maximum(top2d, base2d + 0.22)

    t = (h - base2d[:, None, :]) / np.maximum(top2d - base2d, 1e-3)[:, None, :]
    t = np.clip(t, 0.0, 1.0)
    inside = smoothstep(0.0, 0.05, t) * (1.0 - smoothstep(0.55, 1.0, t) ** 0.7)

    # ---- 3D shape noise ----------------------------------------------------
    shape_noise = fbm3((nz, ny, nx), cells=(6, 5, 6), octaves=5, seed=seed + 13)
    cover3 = cover[:, None, :]
    thr3 = (1.0 - cover3) + 0.30 * t ** 1.5
    base = np.clip(2.8 * (shape_noise - thr3) / np.maximum(cover3, 0.40), 0.0, 1.0) ** 0.7

    density = base * inside

    # ---- erosion by high frequency billow noise ---------------------------
    detail = billow3((nz, ny, nx), cells=(22, 14, 22), octaves=3, seed=seed + 14)
    erosion = ctype["detail"] * detail * detail_scale
    density = np.clip(density - erosion * 0.45 * np.clip(density * 1.8, 0.0, 1.0), 0.0, 1.0)

    # ---- vertical density gradient ----------------------------------------
    density *= (0.70 + 0.30 * (1.0 - t))
    density = np.clip(density, 0.0, 1.0)
    return density.astype(np.float32)


# ----------------------------------------------------------------------------
# Persistence + statistics
# ----------------------------------------------------------------------------
def scene_hash(cfg: dict) -> str:
    """Cache key that depends *only* on the scene block + generator version."""
    import hashlib

    blob = json.dumps(
        {"scene": cfg["scene"], "generator": GENERATOR_VERSION},
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(blob).hexdigest()[:16]


def grid_path(cfg: dict, root: str = "outputs/grids") -> str:
    return os.path.join(root, f"density_{cfg['name']}_{scene_hash(cfg)}.npy")


def get_cloud_grid(cfg: dict, root: str = "outputs/grids", rebuild: bool = False) -> np.ndarray:
    path = grid_path(cfg, root)
    if os.path.exists(path) and not rebuild:
        return np.load(path)
    grid = build_cloud_grid(cfg)
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    np.save(path, grid)
    meta = {
        "config_hash": config_hash(cfg),
        "shape": list(grid.shape),
        "bounds_min": cfg["scene"]["bounds_min"],
        "bounds_max": cfg["scene"]["bounds_max"],
        "seed": cfg["scene"]["seed"],
        "coverage": cfg["scene"]["coverage"],
        "cloud_type": cfg["scene"]["cloud_type"],
        "stats": grid_stats(grid, cfg),
    }
    with open(path.replace(".npy", "_meta.json"), "w", encoding="utf-8") as fh:
        json.dump(meta, fh, indent=2)
    return grid


def grid_stats(grid: np.ndarray, cfg: dict) -> dict:
    """Diagnostics used to calibrate the extinction scale (report §9)."""
    lo, hi = bounds(cfg)
    nx, ny, nz = resolution(cfg)
    dy = (hi[1] - lo[1]) / max(ny - 1, 1)
    kappa = float(cfg["medium"]["extinction_scale_per_m"])
    col = grid.sum(axis=1) * dy * kappa        # vertical optical depth per column
    return {
        "max": float(grid.max()),
        "mean": float(grid.mean()),
        "frac_gt_0.05": float((grid > 0.05).mean()),
        "frac_gt_0.5": float((grid > 0.5).mean()),
        "vertical_optical_depth_p50": float(np.percentile(col, 50)),
        "vertical_optical_depth_p90": float(np.percentile(col, 90)),
        "vertical_optical_depth_p99": float(np.percentile(col, 99)),
    }
