# SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
# SPDX-License-Identifier: Apache-2.0
"""Experiment configuration: defaults, JSON IO, and derived quantities.

All units are SI (metres, radians internally, degrees in the config files).
World layout: Y is up, the cloud layer is an infinite horizontal slab
``y in [bounds_min[1], bounds_max[1]]`` whose density is *periodic* in X and Z
with period ``bounds_max[0] - bounds_min[0]`` (resp. Z).  This lets a single
128x64x128 voxel grid describe a cloud sea that reaches the horizon without
storing an unbounded volume.
"""
from __future__ import annotations

import copy
import hashlib
import json
import math
import os

import numpy as np

# ----------------------------------------------------------------------------
# Default configuration (numbers are experiment starting points, see report §9)
# ----------------------------------------------------------------------------
DEFAULT_CONFIG = {
    "name": "cumulus_day",
    "scene": {
        "bounds_min": [-4000.0, 700.0, -4000.0],
        "bounds_max": [4000.0, 2700.0, 4000.0],
        "resolution": [128, 64, 128],          # (nx, ny, nz) voxel samples
        "seed": 17,
        "coverage": 0.55,
        "cloud_type": "cumulus",               # cumulus | stratocumulus | broken
        "wind_mps": [6.0, 0.0, 2.0],
        "time_s": 0.0,
        "detail_scale": 1.0,
    },
    "medium": {
        "extinction_scale_per_m": 0.008,
        "single_scattering_albedo": 0.99,
        "phase": "hg",
    },
    "lighting": {
        "solar_elevation_deg": 25.0,
        "solar_azimuth_deg": 135.0,
        "sun_irradiance": 1.0,
        "sky_radiance": 0.0,
        "sun_cache_resolution": [192, 96, 192],
        "sun_cache_steps": 128,
    },
    "render": {
        "width": 320,
        "height": 180,
        "camera_ray_steps": 96,
        "max_distance_m": 30000.0,
        "exposure": 1.0,
        "tonemap": "aces",
        "background": "black",                 # black | sky (dome) | slab_sky (finite upper boundary)
    },
    "camera": {
        "position": [0.0, 900.0, -3200.0],
        "target": [0.0, 1650.0, 300.0],
        "up": [0.0, 1.0, 0.0],
        "fov_deg": 55.0,
    },
    "neural": {
        "model": "point_mlp",
        "hidden_width": 64,
        "hidden_layers": 4,
        "activation": "silu",
        "feature_scales_m": [120.0, 350.0, 900.0],
        "predict": "multi_scatter_source",
        "output_scale": 1.0,
    },
    "training": {
        "image_width": 96,
        "image_height": 54,
        "ray_steps": 64,
        "iterations": 400,
        "learning_rate": 2.0e-3,
        "lr_final_fraction": 0.05,
        "batch_views": 2,
        "lambda_l1": 1.0,
        "lambda_log": 0.3,
        "lambda_grad": 0.0,
        "alpha": 10.0,
        "seed": 1234,
    },
}

# ----------------------------------------------------------------------------
# Views used for training / validation / testing (stage 2 data set)
# ----------------------------------------------------------------------------
TRAIN_VIEWS = [
    # above the layer, looking towards the sun: single scattering dominates
    {"name": "t0", "position": [0.0, 3100.0, 2800.0], "target": [-800.0, 2400.0, -800.0]},
    # above the layer, side lit
    {"name": "t1", "position": [-3400.0, 3000.0, 0.0], "target": [900.0, 2200.0, 1200.0]},
    # above the layer, looking away from the sun: multiple scattering dominates
    {"name": "t2", "position": [-2800.0, 2900.0, -2800.0], "target": [800.0, 2300.0, 800.0]},
    # below the cloud base looking up (dark side, deep multiple scattering)
    {"name": "t3", "position": [2600.0, 500.0, 2600.0], "target": [-800.0, 1500.0, -800.0]},
    # steeper down-sun view from high above
    {"name": "t4", "position": [2000.0, 3600.0, 2000.0], "target": [-600.0, 2600.0, -600.0]},
    # below the base, looking up away from the sun
    {"name": "t5", "position": [-2400.0, 400.0, -2800.0], "target": [900.0, 1600.0, 900.0]},
    # at cloud-top level, into the sun
    {"name": "t6", "position": [-3400.0, 2700.0, 2400.0], "target": [1200.0, 2500.0, -1200.0]},
    # near-vertical view from very high
    {"name": "t7", "position": [500.0, 4000.0, 500.0], "target": [0.0, 2200.0, 800.0]},
    # ---- below / inside the layer: the deep-shadow regime -------------------
    {"name": "t8", "position": [0.0, 300.0, 0.0], "target": [600.0, 1800.0, -600.0]},
    {"name": "t9", "position": [-2500.0, 400.0, 2200.0], "target": [800.0, 1700.0, -800.0]},
    {"name": "t10", "position": [2800.0, 950.0, -2400.0], "target": [-500.0, 1500.0, 500.0]},
    {"name": "t11", "position": [-1500.0, 450.0, -3000.0], "target": [700.0, 1600.0, 900.0]},
]

VAL_VIEWS = [
    {"name": "v0", "position": [3200.0, 3050.0, 1800.0], "target": [-900.0, 2300.0, -900.0]},
    {"name": "v1", "position": [-1200.0, 450.0, -3200.0], "target": [600.0, 1500.0, 1200.0]},
]

TEST_VIEWS = [
    {"name": "e0", "position": [-3000.0, 3200.0, 2200.0], "target": [700.0, 2400.0, -1100.0]},
    {"name": "e1", "position": [1200.0, 550.0, -2600.0], "target": [-700.0, 1600.0, 1000.0]},
]

DEFAULT_CONFIG["views"] = TRAIN_VIEWS
DEFAULT_CONFIG["val_views"] = VAL_VIEWS
DEFAULT_CONFIG["test_views"] = TEST_VIEWS


# ----------------------------------------------------------------------------
# Basic helpers
# ----------------------------------------------------------------------------
def default_config() -> dict:
    return copy.deepcopy(DEFAULT_CONFIG)


def deep_update(base: dict, other: dict) -> dict:
    """Recursively merge ``other`` into ``base`` (in place) and return base."""
    for key, value in other.items():
        if isinstance(value, dict) and isinstance(base.get(key), dict):
            deep_update(base[key], value)
        else:
            base[key] = copy.deepcopy(value)
    return base


def load_config(path: str | None = None, overrides: dict | None = None) -> dict:
    cfg = default_config()
    if path is not None and os.path.exists(path):
        with open(path, "r", encoding="utf-8") as fh:
            deep_update(cfg, json.load(fh))
    if overrides:
        deep_update(cfg, overrides)
    return cfg


def save_config(cfg: dict, path: str) -> None:
    os.makedirs(os.path.dirname(os.path.abspath(path)) or ".", exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(cfg, fh, indent=2, ensure_ascii=False)
        fh.write("\n")


def config_hash(cfg: dict) -> str:
    blob = json.dumps(cfg, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(blob).hexdigest()[:16]


# ----------------------------------------------------------------------------
# Derived quantities
# ----------------------------------------------------------------------------
def bounds(cfg: dict) -> tuple[np.ndarray, np.ndarray]:
    lo = np.asarray(cfg["scene"]["bounds_min"], dtype=np.float64)
    hi = np.asarray(cfg["scene"]["bounds_max"], dtype=np.float64)
    return lo, hi


def resolution(cfg: dict) -> tuple[int, int, int]:
    """Return ``(nx, ny, nz)``."""
    nx, ny, nz = cfg["scene"]["resolution"]
    return int(nx), int(ny), int(nz)


def sun_direction(cfg: dict) -> np.ndarray:
    """Unit vector pointing *towards* the sun from a point in the world.

    Azimuth is measured from +Z towards +X (clockwise seen from above),
    elevation from the horizontal plane.
    """
    el = math.radians(float(cfg["lighting"]["solar_elevation_deg"]))
    az = math.radians(float(cfg["lighting"]["solar_azimuth_deg"]))
    d = np.array([math.cos(el) * math.sin(az), math.sin(el), math.cos(el) * math.cos(az)])
    return d / np.linalg.norm(d)


def medium_params(cfg: dict) -> dict:
    med = cfg["medium"]
    return {
        "extinction_scale": float(med["extinction_scale_per_m"]),
        "albedo": float(med["single_scattering_albedo"]),
        "phase_material": int(med.get("phase_material", 0)),
    }


def sun_irradiance(cfg: dict) -> float:
    return float(cfg["lighting"]["sun_irradiance"])


def view_cfg(cfg: dict, view: dict) -> dict:
    """Complete a view dict with defaults from ``cfg['camera']``."""
    out = dict(cfg["camera"])
    out.update(view)
    return out
