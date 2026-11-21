# SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
# SPDX-License-Identifier: Apache-2.0
"""Feature construction for the neural multiple-scattering source (report §6.1).

Two channels share the same architecture but see different features:

* ``sun`` — the optical depths toward the sun, along the view ray into the layer,
  and through its vertical column, together with view/light geometry and phase.
* ``sky`` — a uniform radiance over the upper hemisphere.  Here the sun is
  irrelevant; what matters is the *vertical* optical depth above the point
  (the analytic sky term already uses it) and density probes straight up.

Neither channel sees an absolute world position: that would be a shortcut that
does not generalise to other cloud seeds (report §10 stage 3).
"""
from __future__ import annotations

import numpy as np
import torch

from common.revelation_phase import TorchPhase
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
        self.phase = TorchPhase(int(cfg["medium"].get("phase_material", 0)), self.device)
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

    def _short_depth(self, pts, direction, steps):
        cosine = direction[..., 1]
        height = torch.where(cosine >= 0, self.y1 - pts[..., 1], pts[..., 1] - self.y0)
        span = torch.minimum(height.clamp(min=0) / cosine.abs().clamp(min=1e-10),
                             (self.y1 - self.y0) / cosine.abs().clamp(min=.5))
        step = span / (steps * steps)
        column = torch.zeros_like(span)
        for i in range(steps):
            t = i + .5
            column += 2 * t * self.grid.sample(pts + direction * (t*t*step)[..., None])
        return (column * step * self.cfg["medium"]["extinction_scale_per_m"]).clamp(0, 24)

    # ------------------------------------------------------------------
    def build(self, pts: torch.Tensor, dirs: torch.Tensor, cos_sun: torch.Tensor) -> torch.Tensor:
        """Assemble (ray, sample, feature) in the trained checkpoint's order."""
        view_up = dirs[:, 1:2]
        if self.tau_up is None:
            raise ValueError("Cloud features require the vertical optical depth grid")
        if self.channel == "sky":
            tau = self.tau_up.sample(pts)
            features = [*self._base(pts), tau / (1.0 + tau), self._height(pts), view_up,
                        self._up(pts, 300.), self._up(pts, 800.)]
        else:
            bottom = pts.clone()
            bottom[..., 1] = self.y0
            column = self.tau_up.sample(bottom)
            sun_steps = 3 if int(self.cfg["medium"].get("phase_material", 0)) == 2 else 6
            sun = self._short_depth(pts, self.sun_dir, sun_steps)
            inward = self._short_depth(pts, dirs[:, None, :], 3)
            features = [sun.log1p(), inward.log1p(),
                        column.clamp(0, 128).log1p(), self.sun_dir[1], cos_sun[:, None],
                        self.phase.probability(cos_sun)[:, None], view_up]
        features.append(pts.new_tensor(self.phase.mean_cosine))
        return torch.stack([value.expand(pts.shape[:-1]) for value in features], dim=-1)
