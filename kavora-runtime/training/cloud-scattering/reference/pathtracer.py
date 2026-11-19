# SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
# SPDX-License-Identifier: Apache-2.0
"""Offline reference: volumetric Monte-Carlo path tracer with delta tracking.

The training reference estimates radiance for a frozen medium and a finite
scattering-depth cap. In thick clouds the cap must be checked independently of
sampling noise:

* free-flight distances are sampled by **delta tracking** (null collisions are
  weighted out), so there is no step-size bias in the transmittance;
* at every real collision a **next-event estimate** towards the sun is added
  using the same cached sun optical depth as the runtime;
* continuation directions are sampled from the HG phase function, which makes
  the continuation weight exactly 1.

The slab has a directional sun, an optional uniform upper sky boundary and a
black lower boundary. The solar visibility texture is shared with the runtime.
"""
from __future__ import annotations

import math

import torch

from common.medium import hg_phase_torch, hg_sample_cos_torch
from runtime.renderer import camera_rays_torch, slab_interval
from runtime.volumegrid import resolve_device


def _orthonormal_basis_torch(d: torch.Tensor):
    helper = torch.where(
        d[:, 1:2].abs() < 0.99,
        torch.tensor([0.0, 1.0, 0.0], device=d.device).expand_as(d),
        torch.tensor([1.0, 0.0, 0.0], device=d.device).expand_as(d),
    )
    t = torch.cross(helper, d, dim=-1)
    t = t / t.norm(dim=-1, keepdim=True).clamp_min(1e-12)
    b = torch.cross(d, t, dim=-1)
    return t, b


def sample_hg_direction(axis: torch.Tensor, g: float, u1: torch.Tensor,
                        u2: torch.Tensor) -> torch.Tensor:
    cos_theta = hg_sample_cos_torch(g, u1)
    phi = 2.0 * math.pi * u2
    t, b = _orthonormal_basis_torch(axis)
    sin_theta = torch.sqrt(torch.clamp(1.0 - cos_theta * cos_theta, min=0.0))
    return (
        cos_theta[:, None] * axis
        + (sin_theta * torch.cos(phi))[:, None] * t
        + (sin_theta * torch.sin(phi))[:, None] * b
    )


class VolumePathTracer:
    def __init__(self, cfg: dict, grid, sun_cache, device="cuda"):
        self.cfg = cfg
        self.device = resolve_device(device)
        self.grid = grid
        self.sun_cache = sun_cache
        self.kappa = float(cfg["medium"]["extinction_scale_per_m"])
        self.albedo = float(cfg["medium"]["single_scattering_albedo"])
        self.g = float(cfg["medium"]["g"])
        self.irradiance = float(cfg["lighting"]["sun_irradiance"])
        from common.config import sun_direction

        self.sun_dir_np = sun_direction(cfg)
        self.sun_dir = torch.as_tensor(self.sun_dir_np, dtype=torch.float32, device=self.device)
        self.lo = grid.lo
        self.hi = grid.hi
        self.max_distance = float(cfg["render"]["max_distance_m"])
        self.majorant = max(self.kappa * grid.max_value, 1e-6)
        # uniform radiance over the upper hemisphere (black ground); the sky is a
        # *boundary condition*, so it is sampled by path continuation and needs
        # no shadow rays at all
        self.sky_radiance = float(cfg["lighting"].get("sky_radiance", 0.0))

    # ------------------------------------------------------------------
    def _trace_collisions(self, origins: torch.Tensor, dirs: torch.Tensor,
                          t0: torch.Tensor, t1: torch.Tensor, max_steps: int = 4000):
        """Delta tracking.  Returns ``(hit_mask, t_hit)`` for every input ray."""
        n = dirs.shape[0]
        hit_t = torch.full((n,), float("inf"), dtype=dirs.dtype, device=self.device)

        keep = t1 > t0
        ids = torch.nonzero(keep, as_tuple=False).squeeze(1)
        if ids.numel() == 0:
            return torch.zeros(n, dtype=torch.bool, device=self.device), hit_t

        o = origins[ids]
        d = dirs[ids]
        t = t0[ids]
        t_end = t1[ids]

        hit_ids, hit_ts = [], []
        for _ in range(max_steps):
            step = -torch.log(torch.rand(ids.numel(), device=self.device)) / self.majorant
            t = t + step

            inside = t < t_end
            if not bool(inside.all()):
                o, d, t, t_end, ids = o[inside], d[inside], t[inside], t_end[inside], ids[inside]
                if ids.numel() == 0:
                    break

            # every sampled position inside the slab must be tested for a real
            # collision, otherwise the free-flight distribution is biased
            pos = o + t[:, None] * d
            sigma = self.kappa * self.grid.sample(pos)
            real = torch.rand(ids.numel(), device=self.device) < (sigma / self.majorant)

            if bool(real.any()):
                hit_ids.append(ids[real])
                hit_ts.append(t[real])

            cont = ~real
            if not bool(cont.all()):
                o, d, t, t_end, ids = o[cont], d[cont], t[cont], t_end[cont], ids[cont]
                if ids.numel() == 0:
                    break

        if hit_ids:
            hit_ids = torch.cat(hit_ids)
            mask = torch.zeros(n, dtype=torch.bool, device=self.device)
            mask[hit_ids] = True
            hit_t[hit_ids] = torch.cat(hit_ts)
        else:
            mask = torch.zeros(n, dtype=torch.bool, device=self.device)
        return mask, hit_t

    # ------------------------------------------------------------------
    def render(self, view: dict, width: int, height: int, spp: int = 256,
               max_depth: int = 8, samples_per_batch: int = 16,
               seed: int | None = None, return_aov: bool = False):
        if seed is not None:
            torch.manual_seed(int(seed))
        device = self.device

        dirs2d = camera_rays_torch(view, width, height, device)
        rays_d = dirs2d.reshape(-1, 3)
        num_px = rays_d.shape[0]
        eye = torch.as_tensor(view["position"], dtype=torch.float32, device=device)
        t0, t1, _ = slab_interval(float(eye[1].item()), rays_d[:, 1],
                                  float(self.lo[1]), float(self.hi[1]), self.max_distance)

        acc = torch.zeros(num_px, device=device)

        n_batches = int(math.ceil(spp / samples_per_batch))
        done = 0
        for _ in range(n_batches):
            b = min(samples_per_batch, spp - done)
            done += b
            d = rays_d.repeat(b, 1)
            o = eye.expand_as(d)
            tt0 = t0.repeat(b)
            tt1 = t1.repeat(b)
            radiance = self._trace_batch(o, d, tt0, tt1, max_depth)
            radiance = radiance.reshape(b, num_px)
            acc += radiance.sum(dim=0)

        img = (acc / max(done, 1)).reshape(height, width, 1)
        out = {"color": img.expand(-1, -1, 3).clone()}
        if return_aov:
            out["spp"] = done
        return out

    # ------------------------------------------------------------------
    def _trace_batch(self, o: torch.Tensor, d: torch.Tensor, t0: torch.Tensor,
                     t1: torch.Tensor, max_depth: int) -> torch.Tensor:
        device = self.device
        n = d.shape[0]
        L = torch.zeros(n, device=device)

        cur_ids = torch.arange(n, device=device)
        cur_o, cur_d, cur_t0, cur_t1 = o, d, t0, t1
        throughput = torch.ones(n, device=device)

        for bounce in range(max_depth):
            hit, t_hit = self._trace_collisions(cur_o, cur_d, cur_t0, cur_t1)
            t_safe = torch.where(hit, t_hit, torch.zeros_like(t_hit))
            pos = cur_o + t_safe[:, None] * cur_d
            cos_sun = (cur_d * self.sun_dir).sum(-1).clamp(-1.0, 1.0)
            phase = hg_phase_torch(cos_sun, self.g)
            t_sun = self.sun_cache.transmittance(pos)
            contrib = throughput * self.albedo * phase * self.irradiance * t_sun
            L.index_add_(0, cur_ids, torch.where(hit, contrib, torch.zeros_like(contrib)))

            # ---- sky boundary condition ------------------------------------
            # a path that leaves through the cloud top sees the uniform sky; the
            # throughput already contains the medium attenuation, so adding
            # ``throughput * L_sky`` here is an unbiased boundary estimate.
            if self.sky_radiance > 0.0:
                escaped = ~hit
                if bool(escaped.any()):
                    exit_y = cur_o[:, 1] + cur_t1 * cur_d[:, 1]
                    through_top = (exit_y >= float(self.hi[1]) - 1.0) & \
                                  (cur_t1 < self.max_distance - 1.0)
                    sky_mask = escaped & through_top
                    if bool(sky_mask.any()):
                        L.index_add_(0, cur_ids[sky_mask],
                                     throughput[sky_mask] * self.sky_radiance)

            if bounce == max_depth - 1:
                break
            idx = torch.nonzero(hit, as_tuple=False).squeeze(1)
            if idx.numel() == 0:
                break

            ids_next = cur_ids[idx]
            new_d = sample_hg_direction(cur_d[idx], self.g,
                                        torch.rand(idx.numel(), device=device),
                                        torch.rand(idx.numel(), device=device))
            new_o = pos[idx]
            new_t1 = slab_exit(new_o, new_d, self.lo[1], self.hi[1], self.max_distance)
            thr = throughput[idx] * self.albedo

            # Russian roulette (unbiased): survival probability p = min(1, thr)
            p = thr.clamp(0.0, 1.0)
            survive = torch.rand(idx.numel(), device=device) < p
            weight = thr / p.clamp_min(1e-6)
            keep = survive & (new_t1 > 1e-3)
            if not bool(keep.any()):
                break

            cur_ids = ids_next[keep]
            cur_o = new_o[keep]
            cur_d = new_d[keep]
            cur_t0 = torch.zeros(int(keep.sum()), device=device)
            cur_t1 = new_t1[keep]
            throughput = weight[keep]

        return L


def slab_exit(o: torch.Tensor, d: torch.Tensor, y0: float, y1: float, tmax: float) -> torch.Tensor:
    """Distance until a ray leaves the slab (``tmax`` for horizontal rays)."""
    oy = o[:, 1]
    dy = d[:, 1]
    safe = torch.where(dy.abs() < 1e-8, torch.full_like(dy, 1e-8), dy)
    dist = torch.where(dy > 0, (y1 - oy) / safe, (y0 - oy) / safe)
    dist = torch.where(dy.abs() < 1e-8, torch.full_like(dy, tmax), dist)
    return dist.clamp(0.0, tmax)
