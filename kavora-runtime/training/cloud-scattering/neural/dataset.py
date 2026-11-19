# SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
# SPDX-License-Identifier: Apache-2.0
"""Dataset access for the neural multiple-scattering experiment."""
from __future__ import annotations

import json
import os

import numpy as np

from common.config import default_config
from common.io_hdr import read_exr


class ReferenceJob:
    def __init__(self, entry: dict):
        self.id = entry["id"]
        self.split = entry["split"]
        self.light = entry.get("light", "sky" if self.split.endswith("_sky") else "sun")
        self.dir = entry["dir"]
        self.image_path = entry["image"]
        self.scene = entry["scene"]
        self.sun = float(entry["sun_elevation_deg"])
        self.view = entry["view"]
        self.width = int(entry["width"])
        self.height = int(entry["height"])
        self.spp = int(entry["spp"])
        self._image = None

    @property
    def image(self) -> np.ndarray:
        if self._image is None:
            self._image = read_exr(self.image_path)
        return self._image

    def __repr__(self) -> str:
        return f"<Job {self.id} split={self.split} {self.width}x{self.height}>"


class ReferenceDataset:
    def __init__(self, index_path: str = "outputs/dataset/index.json"):
        with open(index_path, encoding="utf-8") as fh:
            payload = json.load(fh)
        self.jobs = [ReferenceJob(j) for j in payload["jobs"]]
        self.root = os.path.dirname(index_path)

    def split(self, name: str) -> list[ReferenceJob]:
        return [j for j in self.jobs if j.split == name]

    def groups(self, jobs: list[ReferenceJob]) -> dict:
        """Group jobs that share the same density grid and sun direction."""
        out: dict = {}
        for job in jobs:
            out.setdefault((job.scene, job.sun), []).append(job)
        return out

    def load_scene_config(self, job: ReferenceJob) -> dict:
        path = os.path.join(job.dir, "scene.json")
        cfg = default_config()
        with open(path, encoding="utf-8") as fh:
            stored = json.load(fh)
        for key, value in stored.items():
            if isinstance(value, dict):
                cfg[key].update(value)
            else:
                cfg[key] = value
        return cfg
