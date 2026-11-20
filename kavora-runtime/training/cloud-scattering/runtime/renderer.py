# SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
# SPDX-License-Identifier: Apache-2.0
"""Deterministic volume renderer (torch, CPU or CUDA).

Implements exactly the composite convention of the report (§7):

    dL   = T_before * albedo * (1 - exp(-sigma_t * ds)) * (J1 + J_ms)
    T   *= exp(-sigma_t * ds)

with ``J`` the phase-weighted *incident* radiance, so ``sigma_s`` is **never**
applied twice.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field

import numpy as np
import torch

from common.config import bounds, sun_direction
from common.revelation_phase import TorchPhase, sky_quadrature
from runtime.volumegrid import VolumeGrid, build_mip_chain, resolve_device, vertical_optical_depth
from runtime.sun_cache import SunCache


class SkyBands:
    """Restore the order-one sky term with the same angular bands as Vulkan."""

    def __init__(self, phase, device):
        quadrature = np.array(list(sky_quadrature(phase.inverse)), dtype=np.float32)
        self.weights = torch.as_tensor(quadrature[:, 0, :], device=device)
        self.airmass = torch.as_tensor(quadrature[:, 1, :], device=device)

    def j1(self, tau_up: torch.Tensor, cos_up: torch.Tensor) -> torch.Tensor:
        u = (cos_up.clamp(-1.0, 1.0) + 1.0) * 1.5
        lower = u.floor().clamp(0, 2).long()
        upper = lower + 1
        first = (self.weights[lower, None, :] * (-self.airmass[lower, None, :] * tau_up[..., None]).exp()).sum(-1)
        last = (self.weights[upper, None, :] * (-self.airmass[upper, None, :] * tau_up[..., None]).exp()).sum(-1)
        return first.lerp(last, (u - lower).unsqueeze(1))


def camera_rays_torch(view: dict, width: int, height: int, device) -> torch.Tensor:
    """Torch mirror of :func:`common.camera.camera_rays` -> ``(H, W, 3)``."""
    eye = np.asarray(view["position"], dtype=np.float64)
    target = np.asarray(view["target"], dtype=np.float64)
    up = np.asarray(view.get("up", [0.0, 1.0, 0.0]), dtype=np.float64)
    fov_y = math.radians(float(view.get("fov_deg", 55.0)))

    forward = target - eye
    forward = forward / np.linalg.norm(forward)
    right = np.cross(forward, up)
    right = right / np.linalg.norm(right)
    true_up = np.cross(right, forward)

    aspect = width / height
    tan_half = math.tan(0.5 * fov_y)
    px = (np.arange(width) + 0.5) / width * 2.0 - 1.0
    py = 1.0 - (np.arange(height) + 0.5) / height * 2.0

    dirs = (
        forward[None, None, :]
        + (px[None, :, None] * tan_half * aspect) * right[None, None, :]
        + (py[:, None, None] * tan_half) * true_up[None, None, :]
    )
    dirs /= np.linalg.norm(dirs, axis=-1, keepdims=True)
    return torch.as_tensor(dirs, dtype=torch.float32, device=device)


def slab_interval(o_y, dirs_y: torch.Tensor, y0: float, y1: float, tmax):
    """Ray / infinite-slab intersection, clipped to ``[0, tmax]``."""
    oy = torch.as_tensor(o_y, dtype=dirs_y.dtype, device=dirs_y.device)
    limit = torch.as_tensor(tmax, dtype=dirs_y.dtype, device=dirs_y.device)
    parallel = dirs_y.abs() < 1e-12
    inside = (oy >= y0) & (oy <= y1)
    dy = torch.where(parallel, 1.0, dirs_y)
    ta, tb = (y0 - oy) / dy, (y1 - oy) / dy
    near = torch.where(parallel, torch.where(inside, 0.0, 1e30), torch.minimum(ta, tb))
    far = torch.where(parallel, torch.where(inside, limit, -1.0), torch.maximum(ta, tb))
    t0 = near.clamp_min(0.0)
    t1 = torch.minimum(far, limit)
    hit = t1 > t0
    t1 = torch.where(hit, t1, t0)
    return t0, t1, hit


@dataclass
class RenderOutput:
    color: torch.Tensor                 # (H, W, 3)
    aov: dict = field(default_factory=dict)


@dataclass
class SegmentResult:
    """Per-ray monochrome in-scatter and extinction, before endpoint composition."""
    single: torch.Tensor
    multi: torch.Tensor
    optical_depth: torch.Tensor

    @property
    def radiance(self):
        return self.single + self.multi

    @property
    def transmittance(self):
        return torch.exp(-self.optical_depth)


@dataclass
class CachedChannelResult:
    """In-scattered radiance under unit sun and sky, excluding endpoint light."""
    sun: torch.Tensor
    sky: torch.Tensor
    optical_depth: torch.Tensor

    @property
    def transmittance(self):
        return torch.exp(-self.optical_depth)


class VolumeRenderer:
    """Bundles the medium, the density grid and the caches used by a scene."""

    def __init__(
        self,
        cfg: dict,
        density: np.ndarray,
        device: str | torch.device = "cuda",
        mip_levels: int = 4,
        sun_cache: SunCache | None = None,
        build_sun: bool = True,
    ):
        self.cfg = cfg
        self.device = resolve_device(device)
        self.kappa = float(cfg["medium"]["extinction_scale_per_m"])
        self.albedo = float(cfg["medium"]["single_scattering_albedo"])
        self.phase = TorchPhase(int(cfg["medium"].get("phase_material", 0)), self.device)
        self.irradiance = float(cfg["lighting"]["sun_irradiance"])
        self.sun_dir_np = sun_direction(cfg)
        self.sun_dir = torch.as_tensor(self.sun_dir_np, dtype=torch.float32, device=self.device)
        self.lo, self.hi = bounds(cfg)
        self.max_distance = float(cfg["render"]["max_distance_m"])

        self.grid = VolumeGrid(density, self.lo, self.hi, device=self.device)
        self.mips = [
            VolumeGrid(level, self.lo, self.hi, device=self.device)
            for level in build_mip_chain(density, mip_levels)
        ]
        # vertical optical depth above each voxel (analytic sky visibility)
        self.tau_up = VolumeGrid(
            vertical_optical_depth(density, self.grid.dy, self.kappa),
            self.lo, self.hi, device=self.device,
        )
        self.sky_bands = SkyBands(self.phase, self.device)
        self.sky_radiance = float(cfg["lighting"].get("sky_radiance", 0.0))
        self.sun_cache = sun_cache
        if self.sun_cache is None and build_sun:
            self.build_sun_cache()

    # ------------------------------------------------------------------
    def build_sun_cache(self, resolution=None, steps: int | None = None) -> None:
        self.sun_cache = SunCache.build(
            self.grid,
            self.sun_dir_np,
            resolution or self.cfg["lighting"]["sun_cache_resolution"],
            steps=int(steps or self.cfg["lighting"]["sun_cache_steps"]),
            kappa=self.kappa,
            device=self.device,
        )

    def set_sun(self, elevation_deg: float | None = None, azimuth_deg: float | None = None,
                rebuild_cache: bool = True) -> None:
        if elevation_deg is not None:
            self.cfg["lighting"]["solar_elevation_deg"] = float(elevation_deg)
        if azimuth_deg is not None:
            self.cfg["lighting"]["solar_azimuth_deg"] = float(azimuth_deg)
        self.sun_dir_np = sun_direction(self.cfg)
        self.sun_dir = torch.as_tensor(self.sun_dir_np, dtype=torch.float32, device=self.device)
        if rebuild_cache:
            self.build_sun_cache()

    # ------------------------------------------------------------------
    def _march(
        self,
        origin: torch.Tensor,
        dirs: torch.Tensor,
        t0: torch.Tensor,
        t1: torch.Tensor,
        steps: int,
        sigma_scale: float,
        source_fn,
    ):
        """March ``steps`` mid-point samples and integrate the source term."""
        dt = (t1 - t0) / steps
        idx = torch.arange(steps, device=self.device, dtype=torch.float32)
        s = t0[:, None] + (idx[None, :] + 0.5) * dt[:, None]                # (R, N)
        pts = origin.reshape(-1, 1, 3) + s[..., None] * dirs[:, None, :]

        rho = self.grid.sample(pts)
        sigma = self.kappa * rho * sigma_scale
        dtau = sigma * dt[:, None]
        if source_fn is None:
            tau = dtau.sum(dim=1)
            zero = torch.zeros_like(tau)
            return zero, zero, tau

        cs = torch.cumsum(dtau, dim=1)
        t_before = torch.exp(-(cs - dtau))

        first, second = source_fn(pts, dirs)
        w = t_before * (-torch.expm1(-dtau)) * self.albedo
        return (w * first).sum(dim=1), (w * second).sum(dim=1), cs[:, -1]

    def set_wind(self, offset) -> None:
        """Rigid horizontal advection applied consistently to every cache."""
        for g in [self.grid, self.tau_up, *self.mips]:
            g.set_offset(offset)
        if self.sun_cache is not None:
            self.sun_cache.tau_grid.set_offset(offset)

    def set_lighting(self, sun_irradiance: float | None = None,
                     sky_radiance: float | None = None,
                     background: str | None = None) -> None:
        """Switch the channel budget without rebuilding any cache."""
        if sun_irradiance is not None:
            self.irradiance = float(sun_irradiance)
            self.cfg["lighting"]["sun_irradiance"] = float(sun_irradiance)
        if sky_radiance is not None:
            self.sky_radiance = float(sky_radiance)
            self.cfg["lighting"]["sky_radiance"] = float(sky_radiance)
        if background is not None:
            self.cfg["render"]["background"] = background

    # ------------------------------------------------------------------
    def _unit_sun_j1(self, pts: torch.Tensor, cos_sun: torch.Tensor):
        """Single-scatter source per unit solar irradiance."""
        tau_sun = self.sun_cache.tau(pts)
        phase = self.phase.probability(cos_sun)[:, None]                   # (R, 1)
        return phase * torch.exp(-tau_sun)

    def _unit_sky_j1(self, pts: torch.Tensor, cos_up: torch.Tensor):
        """Single-scatter source per unit upper-hemisphere radiance."""
        tau = self.tau_up.sample(pts)
        return self.sky_bands.j1(tau, cos_up)

    def render(
        self,
        view: dict,
        width: int | None = None,
        height: int | None = None,
        mode: str = "neural",
        steps: int | None = None,
        mlp=None,
        feature_builder=None,
        sh_cache=None,
        collect_aovs: bool = False,
        ray_chunk: int | None = None,
        channel: str = "sun",
    ) -> RenderOutput:
        width = int(width or self.cfg["render"]["width"])
        height = int(height or self.cfg["render"]["height"])
        directions = camera_rays_torch(view, width, height, self.device).reshape(-1, 3)
        segment = self.integrate_segments(view["position"], directions, 0.0, self.max_distance,
                                          mode=mode, channel=channel, steps=steps, mlp=mlp,
                                          feature_builder=feature_builder, sh_cache=sh_cache,
                                          ray_chunk=ray_chunk)
        t = segment.transmittance
        color = segment.radiance + t * self._background(directions, view["position"][1])[:, 0]
        out = RenderOutput(color=color.reshape(height, width, 1).expand(-1, -1, 3))
        if collect_aovs:
            for key, values in (("transmittance", t), ("optical_depth", segment.optical_depth),
                                ("single", segment.single), ("multi", segment.multi)):
                out.aov[key] = values.reshape(height, width)
        return out

    def integrate_segments(self, origins, directions, t_min, t_max, *, mode="single",
                           channel="sun", steps=None, mlp=None, feature_builder=None,
                           sh_cache=None, ray_chunk=None) -> SegmentResult:
        """Integrate unit-direction rays between distances in metres, up to a surface or light.

        Origins can be shared or per ray. Both distance limits can be scalars or (R,)
        arrays. An infinite far limit uses the scene's configured viewing distance.
        The caller supplies endpoint radiance. Shadow rays use mode='transmittance'.
        """
        if (channel not in ("sun", "sky")
                or mode not in ("single", "baseline", "neural", "cache", "transmittance")):
            raise ValueError(f"unknown channel {channel!r} or mode {mode!r}")
        if mode == "baseline" and channel != "sun":
            raise ValueError("mode='baseline' (octave approximation) is only defined for the solar channel")
        if mode == "neural" and (mlp is None or feature_builder is None):
            raise ValueError("mode='neural' requires mlp and feature_builder")
        if mode == "cache" and sh_cache is None:
            raise ValueError("mode='cache' requires sh_cache")

        def integrate_block(o, d, near, far, steps):
            return self._integrate_block(o, d, near, far, steps, mode, channel, mlp, feature_builder, sh_cache)

        components = self._integrate_clipped(origins, directions, t_min, t_max, steps, ray_chunk,
                                             integrate_block, keep_misses=mode == "neural" and torch.is_grad_enabled())
        return SegmentResult(*components)

    def integrate_cached_channels(self, origins, directions, t_min, t_max, *, sun_sh=None, sky_sh=None,
                                  sun_active=True, steps=None, ray_chunk=None) -> CachedChannelResult:
        """Integrate both unit lighting responses on the same density samples.

        A missing SH texture uses analytic single scatter for that channel.
        Apply RGB illumination to the returned responses and add T * L_endpoint.
        """
        sun_active = sun_active and self.sun_dir_np[1] > 1e-6

        def integrate_block(o, d, near, far, steps):
            cos_sun = d @ self.sun_dir

            def source(pts, dirs):
                sky = self._unit_sky_j1(pts, dirs[:, 1])
                if sky_sh is not None:
                    sky = sky + sky_sh.eval_radiance(pts, dirs)
                if not sun_active:
                    return torch.zeros_like(sky), sky
                sun = self._unit_sun_j1(pts, cos_sun)
                if sun_sh is not None:
                    sun = sun + sun_sh.eval_radiance(pts, dirs)
                return sun, sky

            return self._march(o, d, near, far, steps, 1.0, source)

        components = self._integrate_clipped(origins, directions, t_min, t_max, steps, ray_chunk, integrate_block)
        return CachedChannelResult(*components)

    def _integrate_clipped(self, origins, directions, t_min, t_max, steps, ray_chunk, integrate_block,
                           keep_misses=False):
        steps = int(self.cfg["render"]["camera_ray_steps"] if steps is None else steps)
        if steps <= 0 or (ray_chunk is not None and ray_chunk <= 0):
            raise ValueError("steps and ray_chunk must be positive")
        ray_chunk = ray_chunk or max(1, 2_500_000 // steps)
        d = torch.atleast_2d(torch.as_tensor(directions, dtype=torch.float32, device=self.device))
        if d.ndim != 2 or d.shape[1] != 3:
            raise ValueError("directions must have shape (3,) or (R, 3)")
        o = torch.as_tensor(origins, dtype=d.dtype, device=self.device).expand_as(d)
        begin = torch.as_tensor(t_min, dtype=d.dtype, device=self.device).expand(len(d))
        limit = torch.as_tensor(t_max, dtype=d.dtype, device=self.device).expand(len(d))
        limit = torch.where(torch.isposinf(limit), self.max_distance, limit)
        near, far, hit = slab_interval(o[:, 1], d[:, 1], self.lo[1], self.hi[1], limit)
        near = torch.maximum(near, begin)
        hit = hit & (far > near)
        # Keep a differentiable zero for a training batch containing only misses.
        if keep_misses:
            active = torch.arange(len(d), device=self.device)
            near, far = torch.where(hit, near, 0.0), torch.where(hit, far, 0.0)
        else:
            active = hit.nonzero().flatten()
        values = d.new_zeros((len(d), 3))
        for start in range(0, len(active), ray_chunk):
            ids = active[start:start + ray_chunk]
            block = integrate_block(o[ids], d[ids], near[ids], far[ids], steps)
            values[ids] = torch.stack(block, dim=-1)
        return values.unbind(dim=1)

    def _integrate_block(self, origins, dirs, near, far, steps, mode, channel, mlp, features, cache):
        strength = self.irradiance if channel == "sun" else self.sky_radiance
        if mode == "transmittance" or strength == 0 or (channel == "sun" and self.sun_dir_np[1] <= 1e-6):
            return self._march(origins, dirs, near, far, steps, 1.0, None)
        cosine = (dirs * self.sun_dir).sum(-1) if channel == "sun" else dirs[:, 1]
        if mode == "baseline" and channel == "sun":
            return self._render_baseline(origins, dirs, near, far, steps, cosine)

        def source(pts, ray_dirs):
            j1 = strength * (self._unit_sun_j1(pts, cosine) if channel == "sun" else self._unit_sky_j1(pts, cosine))
            if mode == "neural":
                multi = strength * self._mlp_source(pts, ray_dirs, cosine, mlp, features)
            elif mode == "cache":
                multi = strength * cache.eval_radiance(pts, ray_dirs)
            else:
                multi = torch.zeros_like(j1)
            return j1, multi

        return self._march(origins, dirs, near, far, steps, 1.0, source)

    # ------------------------------------------------------------------
    def _mlp_source(self, pts, dirs, cos_sun, mlp, feature_builder):
        feats = feature_builder.build(pts, dirs, cos_sun)
        shape = pts.shape[:-1]
        vals = mlp(feats.reshape(-1, feats.shape[-1]))
        return vals.reshape(shape)

    def _render_baseline(self, origin, d, t0, t1, steps, cos_sun):
        """Traditional multiple-scattering octave approximation (baseline B0)."""
        def source0(pts, _dirs):
            j = self.irradiance * self._unit_sun_j1(pts, cos_sun)
            return j, torch.zeros_like(j)

        single, multi, tau = self._march(origin, d, t0, t1, steps, 1.0, source0)
        n_oct = int(self.cfg["render"].get("baseline_octaves", 3))
        iso = 1.0 / (4.0 * math.pi)

        for i in range(1, n_oct + 1):
            b = 0.5 ** i                    # reduced extinction -> deeper penetration
            w = (0.5 * self.albedo) ** i    # energy of the i-th scattering order
            mix = 1.0 - 0.5 ** i            # progressive isotropisation

            def source(pts, _dirs, b=b, w=w, mix=mix):
                tau_sun = self.sun_cache.tau(pts)
                t_sun = torch.exp(-tau_sun * b)
                p_aniso = self.phase.probability(cos_sun)[:, None]
                p_i = (1.0 - mix) * p_aniso + mix * iso
                j = w * p_i * self.irradiance * t_sun
                return torch.zeros_like(j), j

            _, octave_multi, _ = self._march(origin, d, t0, t1, steps, b, source)
            multi = multi + octave_multi
        return single, multi, tau

    # ------------------------------------------------------------------
    def slab_sky_visible(self, origin_y, directions_y):
        """Uniform upper boundary reached before the reference tracer's distance cutoff."""
        return (directions_y > 0) & (origin_y + (self.max_distance - 1.0) * directions_y > self.hi[1])

    def _background(self, dirs: torch.Tensor, origin_y) -> torch.Tensor:
        kind = self.cfg["render"].get("background", "black")
        if kind == "black":
            return torch.zeros((dirs.shape[0], 1), dtype=dirs.dtype, device=dirs.device)
        if kind in ("sky", "slab_sky"):
            sky = float(self.cfg["lighting"].get("sky_radiance", 0.0))
            up = self.slab_sky_visible(origin_y, dirs[:, 1:2]) if kind == "slab_sky" else dirs[:, 1:2] > 0
            return sky * up
        raise ValueError(f"unknown background {kind!r}")
