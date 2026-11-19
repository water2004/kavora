# SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
# SPDX-License-Identifier: Apache-2.0
"""Torch volume grid: trilinear sampling of a periodic-in-XZ voxel field.

The voxel array is stored as ``(nz, ny, nx)`` (depth, height, width) and is
sampled with ``F.grid_sample3d(..., align_corners=True)``.  To keep the
horizontal tiling exact, one wrap-around border slice is prepended/appended on
the X and Z axes; sampling then never hits the clamped border and the periodic
interpolation at the seam is exact.

This class is the single source of truth for *both* CPU and GPU evaluation:
all renderers (numpy validation, torch training, torch runtime) must agree.
"""
from __future__ import annotations


import numpy as np
import torch
import torch.nn.functional as F


def resolve_device(name: str | None = "auto") -> torch.device:
    if name in (None, "auto"):
        return torch.device("cuda" if torch.cuda.is_available() else "cpu")
    return torch.device(name)


def periodic_texture(channels, device):
    """Pack (C, Z, Y, X) values with the X/Z seam needed by grid_sample."""
    if isinstance(channels, np.ndarray):
        channels = np.ascontiguousarray(channels)
    values = torch.as_tensor(channels, dtype=torch.float32, device=device)
    return F.pad(values.unsqueeze(0), (1, 1, 0, 0, 1, 1), mode="circular")


class VolumeGrid:
    """Samples a periodic slab voxel field at arbitrary world positions."""

    def __init__(self, data: np.ndarray | torch.Tensor, bounds_min, bounds_max, device="cpu"):
        if not isinstance(data, torch.Tensor):
            data = np.asarray(data, dtype=np.float32)
        if data.ndim != 3:
            raise ValueError(f"grid must be 3D, got {data.shape}")
        self.shape = data.shape                       # (nz, ny, nx)
        self.nz, self.ny, self.nx = data.shape
        self.lo = np.asarray(bounds_min, dtype=np.float64).reshape(3)
        self.hi = np.asarray(bounds_max, dtype=np.float64).reshape(3)
        self.device = torch.device(device)

        # periodic in x/z -> spacing covers the whole period
        self.dx = (self.hi[0] - self.lo[0]) / self.nx
        self.dz = (self.hi[2] - self.lo[2]) / self.nz
        self.dy = (self.hi[1] - self.lo[1]) / max(self.ny - 1, 1)

        self._tex = periodic_texture(data[None], self.device)
        self.offset = torch.zeros(3, dtype=torch.float32, device=self.device)

    # ------------------------------------------------------------------
    @property
    def num_voxels(self) -> int:
        return self.nz * self.ny * self.nx

    def set_offset(self, offset) -> None:
        """Rigid horizontal advection: sample the field at ``p - offset``."""
        self.offset = torch.as_tensor(offset, dtype=torch.float32, device=self.device).reshape(3)

    def to(self, device) -> "VolumeGrid":
        self.device = torch.device(device)
        self._tex = self._tex.to(self.device)
        self.offset = self.offset.to(self.device)
        return self

    # ------------------------------------------------------------------
    def sample(self, points: torch.Tensor) -> torch.Tensor:
        """Trilinear sample at world positions ``points (..., 3)``."""
        return self.sample_local(points - self.offset)

    def sample_local(self, points: torch.Tensor) -> torch.Tensor:
        """Sample positions in the untranslated density snapshot."""
        lead = points.shape[:-1]
        p = points.reshape(-1, 3)
        xn = self._norm_xz(p[:, 0] - self.lo[0], self.dx, self.nx)
        yn = self._norm_y(p[:, 1] - self.lo[1])
        zn = self._norm_xz(p[:, 2] - self.lo[2], self.dz, self.nz)
        grid = torch.stack((xn, yn, zn), dim=-1).view(1, -1, 1, 1, 3)
        out = F.grid_sample(
            self._tex, grid, mode="bilinear", padding_mode="border", align_corners=True
        )
        return out.view(lead)

    def _norm_xz(self, rel: torch.Tensor, spacing: float, n: int) -> torch.Tensor:
        idx = torch.remainder(rel / spacing, float(n))
        return 2.0 * (idx + 1.0) / (n + 1.0) - 1.0

    def _norm_y(self, rel: torch.Tensor) -> torch.Tensor:
        idx = torch.clamp(rel / self.dy, 0.0, float(self.ny - 1))
        return 2.0 * idx / max(self.ny - 1, 1) - 1.0

    # ------------------------------------------------------------------
    def sample_numpy(self, points: np.ndarray) -> np.ndarray:
        """CPU reference sampler (independent implementation, used in tests)."""
        pts = np.asarray(points, dtype=np.float64).reshape(-1, 3)
        pts = pts - self.offset.detach().cpu().numpy().astype(np.float64)
        # reconstruct the padded array from the torch texture
        padded = self._tex.detach().cpu().numpy()[0, 0]
        ix = np.mod((pts[:, 0] - self.lo[0]) / self.dx, self.nx)
        iy = np.clip((pts[:, 1] - self.lo[1]) / self.dy, 0.0, self.ny - 1)
        iz = np.mod((pts[:, 2] - self.lo[2]) / self.dz, self.nz)

        x0 = np.floor(ix).astype(np.int64) + 1
        y0 = np.clip(np.floor(iy).astype(np.int64), 0, self.ny - 2)
        z0 = np.floor(iz).astype(np.int64) + 1
        fx = ix - np.floor(ix)
        fy = iy - y0
        fz = iz - np.floor(iz)

        def g(zc, yc, xc):
            return padded[zc, yc, xc]

        c000 = g(z0, y0, x0)
        c100 = g(z0, y0, x0 + 1)
        c010 = g(z0, y0 + 1, x0)
        c110 = g(z0, y0 + 1, x0 + 1)
        c001 = g(z0 + 1, y0, x0)
        c101 = g(z0 + 1, y0, x0 + 1)
        c011 = g(z0 + 1, y0 + 1, x0)
        c111 = g(z0 + 1, y0 + 1, x0 + 1)
        c00 = c000 + (c100 - c000) * fx
        c10 = c010 + (c110 - c010) * fx
        c01 = c001 + (c101 - c001) * fx
        c11 = c011 + (c111 - c011) * fx
        c0 = c00 + (c10 - c00) * fy
        c1 = c01 + (c11 - c01) * fy
        return c0 + (c1 - c0) * fz

    # ------------------------------------------------------------------
    @property
    def max_value(self) -> float:
        return float(self._tex.max().item())


def build_mip_chain(data: np.ndarray, levels: int = 4) -> list[np.ndarray]:
    """Successive 2x box-filtered versions of a periodic slab grid.

    Filtering is periodic in X/Z and clamped in Y so the multi-scale features
    see the same field as the fine grid.
    """
    from scipy.ndimage import uniform_filter

    levels = max(1, int(levels))
    chain = [np.ascontiguousarray(np.asarray(data, dtype=np.float32))]
    for _ in range(levels - 1):
        prev = chain[-1]
        smooth = uniform_filter(
            prev, size=3, mode=["grid-wrap", "nearest", "grid-wrap"]
        )
        down = smooth[::2, ::2, ::2]
        # keep at least 2 samples per axis and remain periodic in x/z
        down = np.ascontiguousarray(down.astype(np.float32))
        if down.shape[0] < 2 or down.shape[2] < 2:
            break
        chain.append(down)
    return chain


def vertical_optical_depth(data: np.ndarray, dy: float, kappa: float) -> np.ndarray:
    """Optical depth from every voxel to the *top* of the slab.

    Used by the analytic sky term: a plane-parallel approximation of the
    hemispherical sky visibility (see ``runtime.renderer.SkyBands``).
    """
    data = np.asarray(data, dtype=np.float64)
    flip = data[:, ::-1, :]
    csum = np.cumsum(flip, axis=1)
    tau = (csum - 0.5 * flip) * (dy * kappa)
    return np.ascontiguousarray(tau[:, ::-1, :].astype(np.float32))
