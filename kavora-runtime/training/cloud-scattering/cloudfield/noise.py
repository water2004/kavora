# SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
# SPDX-License-Identifier: Apache-2.0
"""Deterministic, period-aware procedural noise (numpy, CPU, one-off cost).

The cloud field is generated once per scene into a voxel grid, so this module
is allowed to be slower than the renderer.  All noise is *periodic* along the
axes flagged in ``periodic`` with a period of exactly ``cells`` lattice units,
which is what makes the horizontal tiling of the cloud layer exact.
"""
from __future__ import annotations

import numpy as np

_GRADS = np.array(
    [
        [1, 1, 0], [-1, 1, 0], [1, -1, 0], [-1, -1, 0],
        [1, 0, 1], [-1, 0, 1], [1, 0, -1], [-1, 0, -1],
        [0, 1, 1], [0, -1, 1], [0, 1, -1], [0, -1, -1],
    ],
    dtype=np.float32,
)


def smoothstep(edge0, edge1, x):
    t = np.clip((x - edge0) / np.maximum(edge1 - edge0, 1e-12), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def _fade(t):
    return t * t * t * (t * (t * 6.0 - 15.0) + 10.0)


def hash_u32(ix, iy, iz, seed: int = 0) -> np.ndarray:
    """Fast integer hash -> uint32 (wrapping arithmetic)."""
    h = (
        ix.astype(np.uint32) * np.uint32(0x1F1F1F1F)
        ^ iy.astype(np.uint32) * np.uint32(0x2C2C2C2C)
        ^ iz.astype(np.uint32) * np.uint32(0x3D3D3D3D)
        ^ np.uint32((seed * 2654435761) & 0xFFFFFFFF)
    )
    h ^= h >> np.uint32(15)
    h *= np.uint32(0x7FEB352D)
    h ^= h >> np.uint32(10)
    h *= np.uint32(0x846CA68B)
    h ^= h >> np.uint32(16)
    return h


def hash01(ix, iy, iz, seed: int = 0) -> np.ndarray:
    return hash_u32(ix, iy, iz, seed).astype(np.float32) * np.float32(1.0 / 4294967296.0)


def _axis_coords(n: int, cells: int, periodic: bool) -> np.ndarray:
    if periodic:
        # node i sits at i/n of the period -> node n would coincide with node 0
        return np.arange(n, dtype=np.float64) * (cells / n)
    return np.linspace(0.0, cells, n, dtype=np.float64)


def _wrap(idx, cells: int):
    return np.mod(idx, cells).astype(np.int64)


def perlin3(
    shape: tuple[int, int, int],
    cells: tuple[int, int, int],
    seed: int = 0,
    periodic: tuple[bool, bool, bool] = (True, False, True),
) -> np.ndarray:
    """Periodic 3D Perlin gradient noise, roughly in [-1, 1].

    ``shape`` is ``(nz, ny, nx)``; ``cells`` is the number of lattice cells
    across each axis.
    """
    nz, ny, nx = shape
    cz, cy, cx = cells

    fx = _axis_coords(nx, cx, periodic[2])
    fy = _axis_coords(ny, cy, periodic[1])
    fz = _axis_coords(nz, cz, periodic[0])

    ix0 = np.floor(fx).astype(np.int64)
    iy0 = np.floor(fy).astype(np.int64)
    iz0 = np.floor(fz).astype(np.int64)
    tx = _fade(fx - ix0).astype(np.float32)
    ty = _fade(fy - iy0).astype(np.float32)
    tz = _fade(fz - iz0).astype(np.float32)

    # broadcast the axis-wise integer coordinates
    IZ0 = iz0[:, None, None]
    IY0 = iy0[None, :, None]
    IX0 = ix0[None, None, :]
    TZ = np.broadcast_to(tz[:, None, None], (nz, ny, nx))
    TY = np.broadcast_to(ty[None, :, None], (nz, ny, nx))
    TX = np.broadcast_to(tx[None, None, :], (nz, ny, nx))

    offsets = [(0, 0, 0), (1, 0, 0), (0, 1, 0), (1, 1, 0), (0, 0, 1), (1, 0, 1), (0, 1, 1), (1, 1, 1)]
    corners = {}
    for oz, oy, ox in offsets:
        izc, iyc, ixc = IZ0 + oz, IY0 + oy, IX0 + ox
        if periodic[0]:
            izc = _wrap(izc, cz)
        if periodic[1]:
            iyc = _wrap(iyc, cy)
        if periodic[2]:
            ixc = _wrap(ixc, cx)
        izb = np.broadcast_to(izc, (nz, ny, nx))
        iyb = np.broadcast_to(iyc, (nz, ny, nx))
        ixb = np.broadcast_to(ixc, (nz, ny, nx))
        gi = (hash_u32(izb, iyb, ixb, seed) % np.uint32(12)).astype(np.int64)
        g = _GRADS[gi]                       # (nz, ny, nx, 3)
        dx = TX - ox
        dy = TY - oy
        dz = TZ - oz
        corners[(oz, oy, ox)] = g[..., 0] * dx + g[..., 1] * dy + g[..., 2] * dz
        del g

    def lerp(a, b, t):
        return a + (b - a) * t

    x00 = lerp(corners[(0, 0, 0)], corners[(0, 0, 1)], TX)
    x10 = lerp(corners[(0, 1, 0)], corners[(0, 1, 1)], TX)
    x01 = lerp(corners[(1, 0, 0)], corners[(1, 0, 1)], TX)
    x11 = lerp(corners[(1, 1, 0)], corners[(1, 1, 1)], TX)
    y0 = lerp(x00, x10, TY)
    y1 = lerp(x01, x11, TY)
    out = lerp(y0, y1, TZ)
    # empirically Perlin 3D peaks around +-0.75; normalise to ~[-1, 1]
    return np.clip(out * 1.35, -1.0, 1.0).astype(np.float32)


def fbm3(
    shape: tuple[int, int, int],
    cells: tuple[int, int, int],
    octaves: int = 5,
    seed: int = 0,
    lacunarity: float = 2.0,
    gain: float = 0.5,
    periodic: tuple[bool, bool, bool] = (True, False, True),
) -> np.ndarray:
    """Fractal sum of periodic Perlin octaves, normalised to roughly [0, 1]."""
    total = np.zeros(shape, dtype=np.float32)
    amp = 1.0
    norm = 0.0
    cz, cy, cx = cells
    for o in range(octaves):
        scale = lacunarity ** o
        cells_o = (max(1, int(round(cz * scale))), max(1, int(round(cy * scale))),
                   max(1, int(round(cx * scale))))
        total += amp * perlin3(shape, cells_o, seed=seed + 101 * o, periodic=periodic)
        norm += amp
        amp *= gain
    return (0.5 * (total / max(norm, 1e-9)) + 0.5).astype(np.float32)


def fbm2(
    shape: tuple[int, int],
    cells: tuple[int, int],
    octaves: int = 4,
    seed: int = 0,
    lacunarity: float = 2.0,
    gain: float = 0.5,
) -> np.ndarray:
    """2D fractal noise on a ``(n_row, n_col)`` grid, in [0, 1]."""
    n_row, n_col = shape
    total = np.zeros(shape, dtype=np.float32)
    amp = 1.0
    norm = 0.0
    for o in range(octaves):
        scale = lacunarity ** o
        cells_o = (max(1, int(round(cells[0] * scale))), max(1, int(round(cells[1] * scale))))
        n = perlin3((1, n_row, n_col), (1, cells_o[0], cells_o[1]), seed=seed + 101 * o,
                    periodic=(True, True, True))[0]
        total += amp * n
        norm += amp
        amp *= gain
    return (0.5 * (total / max(norm, 1e-9)) + 0.5).astype(np.float32)


def billow3(
    shape: tuple[int, int, int],
    cells: tuple[int, int, int],
    octaves: int = 3,
    seed: int = 0,
) -> np.ndarray:
    """Billowy fractal noise in [0, 1] (|Perlin| summed), good for erosion."""
    total = np.zeros(shape, dtype=np.float32)
    amp = 1.0
    norm = 0.0
    cz, cy, cx = cells
    for o in range(octaves):
        scale = 2.0 ** o
        cells_o = (max(1, int(round(cz * scale))), max(1, int(round(cy * scale))),
                   max(1, int(round(cx * scale))))
        n = np.abs(perlin3(shape, cells_o, seed=seed + 977 * o))
        total += amp * n
        norm += amp
        amp *= 0.5
    return np.clip(total / max(norm, 1e-9), 0.0, 1.0).astype(np.float32)


def remap(value, low_a, high_a, low_b, high_b):
    """Smooth remap of ``value`` from [low_a, high_a] to [low_b, high_b]."""
    t = np.clip((value - low_a) / np.maximum(high_a - low_a, 1e-9), 0.0, 1.0)
    return low_b + (high_b - low_b) * (t * t * (3.0 - 2.0 * t))
