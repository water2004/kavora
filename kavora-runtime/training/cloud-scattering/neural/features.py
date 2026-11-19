# SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
# SPDX-License-Identifier: Apache-2.0
"""Feature construction for the neural multiple-scattering source (report §6.1).

Two channels share the same architecture but see different features:

* ``sun`` — direct sunlight, attenuated along the sun direction.  Features are
  local density, a 3-scale density pyramid, the sun transmittance cache, height
  inside the layer, the view/sun geometry and density probes along the sun.
* ``sky`` — a uniform radiance over the upper hemisphere.  Here the sun is
  irrelevant; what matters is the *vertical* optical depth above the point
  (the analytic sky term already uses it) and density probes straight up.

Neither channel sees an absolute world position: that would be a shortcut that
does not generalise to other cloud seeds (report §10 stage 3).
"""
from __future__ import annotations

import numpy as np
import torch

from common.medium import hg_phase_torch
from neural.feature_layout import feature_names


class FeatureBuilder:
    def __init__(self, cfg: dict, grid, mips, sun_cache, sun_dir, device=None,
                 tau_up=None, channel: str = "sun"):
        self.cfg = cfg
        self.grid = grid
        self.mips = mips
        self.sun_cache = sun_cache
        self.tau_up = tau_up
        self.channel = channel
        self.device = torch.device(device) if device is not None else grid.device
        self.sun_dir = torch.as_tensor(np.asarray(sun_dir, dtype=np.float32), device=self.device)
        self.g = float(cfg["medium"]["g"])
        self.y0 = float(grid.lo[1])
        self.y1 = float(grid.hi[1])
        self.names = feature_names(channel)
        self.n_features = len(self.names)

    # ------------------------------------------------------------------
    def set_sun(self, sun_dir, sun_cache=None) -> None:
        """Update the probe direction and bind the rebuilt optical-depth field."""
        self.sun_dir = torch.as_tensor(sun_dir, dtype=torch.float32, device=self.device)
        if sun_cache is not None:
            self.sun_cache = sun_cache

    # ------------------------------------------------------------------
    def _base(self, pts: torch.Tensor) -> list[torch.Tensor]:
        feats = [self.grid.sample(pts)]
        for level in range(1, min(4, len(self.mips))):
            feats.append(self.mips[level].sample(pts))
        while len(feats) < 4:                       # degenerate small grids
            feats.append(feats[-1])
        return feats

    def _height(self, pts: torch.Tensor) -> torch.Tensor:
        h = (pts[..., 1] - self.y0) / max(self.y1 - self.y0, 1e-6)
        return h.clamp(0.0, 1.0)

    def _up(self, pts: torch.Tensor, dist: float) -> torch.Tensor:
        return self.grid.sample(pts + torch.tensor([0.0, dist, 0.0], device=self.device))

    # ------------------------------------------------------------------
    def spatial_features(self, pts: torch.Tensor):
        """Columns before and after the angular features in the checkpoint layout."""
        prefix = self._base(pts)
        if self.channel == "sky":
            if self.tau_up is None:
                raise ValueError("sky feature channel requires the tau_up grid")
            tau = self.tau_up.sample(pts)
            probes = [self._up(pts, dist) for dist in (300.0, 800.0)]
        else:
            tau = self.sun_cache.tau(pts)
            prefix.append(torch.exp(-tau))
            probes = [self.grid.sample(pts + self.sun_dir * dist)
                      for dist in (150.0, 500.0, 1200.0, -300.0)]
            probes.append(self._up(pts, 500.0))
        prefix.extend((tau / (1.0 + tau), self._height(pts)))
        return prefix, probes

    def with_directions(self, spatial, dirs: torch.Tensor, cos_sun: torch.Tensor) -> torch.Tensor:
        prefix, probes = spatial
        angular = [dirs[:, 1:2]]
        if self.channel == "sun":
            angular = [cos_sun[:, None], hg_phase_torch(cos_sun, self.g)[:, None], *angular]
        shape = (len(dirs), prefix[0].shape[1])
        return torch.stack([v.expand(shape) for v in (*prefix, *angular, *probes)], dim=-1)

    def build(self, pts: torch.Tensor, dirs: torch.Tensor, cos_sun: torch.Tensor) -> torch.Tensor:
        """Assemble (ray, sample, feature) in the trained checkpoint's order."""
        return self.with_directions(self.spatial_features(pts), dirs, cos_sun)
