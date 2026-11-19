# SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
# SPDX-License-Identifier: Apache-2.0
"""Radiative-transfer primitives: Henyey-Greenstein phase function, medium.

Conventions used everywhere in this project
-------------------------------------------
``p(cos_theta)`` with ``cos_theta = dot(w_in, w_out)`` where ``w_in`` and
``w_out`` are *propagation* directions of the photon before and after the
scattering event (i.e. both point along the direction the light travels).

For a camera ray with direction ``d`` (from the eye into the scene) the photon
that reaches the eye propagates along ``-d``.  Sun light propagating towards
the shading point travels along ``-sunDir`` (``sunDir`` points *to* the sun).
Hence

    cos_theta_single = dot(-sunDir, -d) = dot(sunDir, d)

which gives the forward-scattering peak when looking towards the sun
(``d ~ sunDir``), i.e. silver lining.  This sign is unit-tested in
``tests/test_phase_function.py``.
"""
from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np

FOUR_PI = 4.0 * math.pi


def hg_phase(cos_theta, g: float):
    """Normalised Henyey-Greenstein phase function [1/sr].

    ``int_{4pi} p dOmega == 1``.  Works for numpy arrays and scalars.
    """
    g = float(g)
    if abs(g) < 1e-8:
        return np.full_like(np.asarray(cos_theta, dtype=np.float64), 1.0 / FOUR_PI)
    ct = np.clip(np.asarray(cos_theta, dtype=np.float64), -1.0, 1.0)
    denom = 1.0 + g * g - 2.0 * g * ct
    return (1.0 - g * g) / (FOUR_PI * np.power(denom, 1.5))


def hg_phase_torch(cos_theta, g: float):
    """Torch version of :func:`hg_phase` (keeps autograd)."""
    import torch

    g = float(g)
    ct = torch.clamp(cos_theta, -1.0, 1.0)
    if abs(g) < 1e-8:
        return torch.full_like(ct, 1.0 / FOUR_PI)
    denom = 1.0 + g * g - 2.0 * g * ct
    return (1.0 - g * g) / (FOUR_PI * torch.pow(denom, 1.5))


def hg_sample_cos(g: float, u: np.ndarray) -> np.ndarray:
    """Inverse-CDF sampling of the HG deflection cosine for uniform ``u``."""
    g = float(g)
    u = np.asarray(u, dtype=np.float64)
    if abs(g) < 1e-8:
        return 1.0 - 2.0 * u
    s = (1.0 - g * g) / (1.0 + g - 2.0 * g * u)
    return (1.0 + g * g - s * s) / (2.0 * g)


def hg_sample_cos_torch(g: float, u):
    """Torch version of :func:`hg_sample_cos`."""

    g = float(g)
    if abs(g) < 1e-8:
        return 1.0 - 2.0 * u
    s = (1.0 - g * g) / (1.0 + g - 2.0 * g * u)
    return (1.0 + g * g - s * s) / (2.0 * g)


def orthonormal_basis(d: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Return two unit vectors orthogonal to ``d`` (and to each other)."""
    d = np.asarray(d, dtype=np.float64)
    helper = np.array([0.0, 1.0, 0.0]) if abs(d[1]) < 0.99 else np.array([1.0, 0.0, 0.0])
    t = np.cross(helper, d)
    t /= np.linalg.norm(t)
    b = np.cross(d, t)
    return t, b


def sample_direction_around(axis: np.ndarray, cos_theta: np.ndarray, phi: np.ndarray) -> np.ndarray:
    """Build directions at angle ``acos(cos_theta)`` from ``axis`` with azimuth ``phi``."""
    axis = np.asarray(axis, dtype=np.float64)
    t, b = orthonormal_basis(axis)
    sin_theta = np.sqrt(np.clip(1.0 - cos_theta ** 2, 0.0, 1.0))
    return (
        cos_theta[..., None] * axis
        + (sin_theta * np.cos(phi))[..., None] * t
        + (sin_theta * np.sin(phi))[..., None] * b
    )


@dataclass(frozen=True)
class Medium:
    """Homogeneous *material*: extinction = scale * density."""

    extinction_scale: float
    albedo: float
    g: float

    def extinction(self, density):
        return self.extinction_scale * density

    def scattering(self, density):
        return self.albedo * self.extinction(density)

    def phase(self, cos_theta):
        return hg_phase(cos_theta, self.g)


def medium_from_cfg(cfg: dict) -> Medium:
    med = cfg["medium"]
    return Medium(
        extinction_scale=float(med["extinction_scale_per_m"]),
        albedo=float(med["single_scattering_albedo"]),
        g=float(med["g"]),
    )
