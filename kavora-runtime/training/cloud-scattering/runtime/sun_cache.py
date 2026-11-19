# SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
# SPDX-License-Identifier: Apache-2.0
"""Sun optical-depth cache (report §6.1, "太阳可见性").

For every voxel of a (typically 1.5x finer) grid we integrate the density along
the direction towards the sun until the ray leaves the top of the cloud slab.
The resulting optical depth field is what both the reference renderer and the
runtime use for direct-sun visibility, so the two can never disagree; the
trilinear reconstruction error of the cache is measured separately in
``tests/test_sun_cache.py`` against a per-point exact integration.
"""
from __future__ import annotations

import numpy as np
import torch

from runtime.volumegrid import VolumeGrid


class SunCache:
    """Optical depth / transmittance of the direct sun path, sampled in 3D."""

    def __init__(self, tau_grid: VolumeGrid, sun_dir, kappa: float):
        self.tau_grid = tau_grid
        self.sun_dir = torch.as_tensor(
            np.asarray(sun_dir, dtype=np.float32), device=tau_grid.device
        )
        self.kappa = float(kappa)

    # ------------------------------------------------------------------
    @classmethod
    def build(
        cls,
        grid: VolumeGrid,
        sun_dir,
        resolution,
        steps: int = 128,
        kappa: float | None = None,
        device=None,
    ) -> "SunCache":
        device = torch.device(device) if device is not None else grid.device
        kappa = float(kappa if kappa is not None else getattr(grid, "kappa", 1.0))
        cnx, cny, cnz = (int(v) for v in resolution)
        lo = torch.as_tensor(grid.lo, dtype=torch.float32, device=device)
        hi = torch.as_tensor(grid.hi, dtype=torch.float32, device=device)
        dx = (hi[0] - lo[0]) / cnx
        dz = (hi[2] - lo[2]) / cnz
        dy = (hi[1] - lo[1]) / max(cny - 1, 1)

        xs = lo[0] + torch.arange(cnx, device=device, dtype=torch.float32) * dx
        ys = lo[1] + torch.arange(cny, device=device, dtype=torch.float32) * dy
        zs = lo[2] + torch.arange(cnz, device=device, dtype=torch.float32) * dz

        sun = torch.as_tensor(np.asarray(sun_dir, dtype=np.float32), device=device)
        tau = torch.zeros((cnz, cny, cnx), dtype=torch.float32, device=device)

        if float(sun[1]) <= 1e-6:
            tau.fill_(1e9)                      # sun below the horizon: no direct light
        else:
            zz, yy, xx = torch.meshgrid(zs, ys, xs, indexing="ij")
            nodes = torch.stack((xx, yy, zz), dim=-1).reshape(-1, 3)
            chunk = 1_000_000
            flat = tau.view(-1)
            for start in range(0, nodes.shape[0], chunk):
                p = nodes[start:start + chunk]
                s_exit = (hi[1] - p[:, 1]) / sun[1]
                ds = s_exit / steps
                acc = torch.zeros_like(s_exit)
                for i in range(steps):
                    s = s_exit * ((i + 0.5) / steps)
                    q = p + s[:, None] * sun
                    acc = acc + grid.sample_local(q) * ds
                flat[start:start + p.shape[0]] = acc * kappa

        tau_grid = VolumeGrid(tau, grid.lo, grid.hi, device=device)
        tau_grid.set_offset(grid.offset)
        return cls(tau_grid, sun_dir, kappa=kappa)

    # ------------------------------------------------------------------
    def tau(self, points: torch.Tensor) -> torch.Tensor:
        return self.tau_grid.sample(points)

    def transmittance(self, points: torch.Tensor) -> torch.Tensor:
        return torch.exp(-self.tau(points))

    def save(self, path: str) -> None:
        np.save(path, self.tau_grid._tex.detach().cpu().numpy())

    def to(self, device) -> "SunCache":
        self.tau_grid.to(device)
        self.sun_dir = self.sun_dir.to(device)
        return self
