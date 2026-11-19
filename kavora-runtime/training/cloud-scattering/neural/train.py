# SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
# SPDX-License-Identifier: Apache-2.0
"""Train the multi-scatter MLP with image supervision (report §8).

Usage
-----
    python -m neural.train --iterations 600 --steps 64 --out outputs/runs/exp1
"""
from __future__ import annotations

import argparse
import json
import math
import os
import random
import time

import numpy as np
import torch

from common.config import save_config
from neural.dataset import ReferenceDataset
from neural.feature_layout import feature_names
from neural.features import FeatureBuilder
from neural.model import MultiScatterMLP
from runtime.renderer import VolumeRenderer
from runtime.volumegrid import resolve_device


# ----------------------------------------------------------------------------
# Scene context
# ----------------------------------------------------------------------------
class SceneContext:
    """Renderer + feature builder for one (density snapshot, sun) pair."""

    def __init__(self, cfg: dict, density: np.ndarray, device, channel: str = "sun", sky_boundary="slab_sky"):
        self.cfg = cfg
        self.channel = channel
        self.renderer = VolumeRenderer(cfg, density, device=device, mip_levels=4)
        # the reference sky images contain the uniform sky *background*; the
        # deterministic renderer must reproduce the same boundary so that the
        # network only has to learn the in-scattered part
        if channel == "sky":
            self.renderer.set_lighting(sun_irradiance=0.0, sky_radiance=1.0, background=sky_boundary)
        else:
            self.renderer.set_lighting(sun_irradiance=1.0, sky_radiance=0.0, background="black")
        self.features = FeatureBuilder(
            cfg, self.renderer.grid, self.renderer.mips, self.renderer.sun_cache,
            self.renderer.sun_dir_np, device=self.renderer.device,
            tau_up=self.renderer.tau_up, channel=channel,
        )


def build_contexts(dataset: ReferenceDataset, jobs, device, channel: str = "sun", sky_boundary="slab_sky") -> dict:
    ctx = {}
    for (scene, sun), group in dataset.groups(jobs).items():
        cfg = dataset.load_scene_config(group[0])
        cfg["lighting"]["solar_elevation_deg"] = sun
        density = np.load(os.path.join(group[0].dir, "density.npy"))
        ctx[(scene, sun)] = SceneContext(cfg, density, device, channel=channel, sky_boundary=sky_boundary)
        print(f"[ctx] scene={scene} sun={sun} channel={channel} "
              f"views={[j.id for j in group]}")
    return ctx


# ----------------------------------------------------------------------------
# Losses
# ----------------------------------------------------------------------------
def relative_l1(pred: torch.Tensor, target: torch.Tensor) -> torch.Tensor:
    scale = target.detach().mean().clamp_min(1e-9)
    return (pred - target).abs().mean() / scale


def log_l1(pred: torch.Tensor, target: torch.Tensor, alpha: float) -> torch.Tensor:
    a = torch.log1p(alpha * pred.clamp_min(0.0))
    b = torch.log1p(alpha * target)
    return (a - b).abs().mean() / b.detach().mean().clamp_min(1e-9)


def gradient_l1(pred: torch.Tensor, target: torch.Tensor) -> torch.Tensor:
    dx_p = pred[..., 1:] - pred[..., :-1]
    dx_t = target[..., 1:] - target[..., :-1]
    dy_p = pred[1:, :] - pred[:-1, :]
    dy_t = target[1:, :] - target[:-1, :]
    return (dx_p - dx_t).abs().mean() + (dy_p - dy_t).abs().mean()


# ----------------------------------------------------------------------------
# Feature normalisation
# ----------------------------------------------------------------------------
def collect_feature_stats(context_by_job: dict, jobs, samples_per_ray: int = 48,
                          max_rays: int = 1500) -> tuple[np.ndarray, np.ndarray]:
    from runtime.renderer import camera_rays_torch, slab_interval

    feats = []
    rng = np.random.default_rng(0)
    for job in jobs:
        ctx = context_by_job[job.id]
        r = ctx.renderer
        view = {**ctx.cfg["camera"], **job.view}
        width, height = job.width, job.height
        dirs = camera_rays_torch(view, width, height, r.device).reshape(-1, 3)
        idx = rng.choice(dirs.shape[0], size=min(max_rays, dirs.shape[0]), replace=False)
        d = dirs[torch.as_tensor(idx)]
        t0, t1, _ = slab_interval(float(view["position"][1]), d[:, 1],
                                  float(r.lo[1]), float(r.hi[1]), r.max_distance)
        s = t0[:, None] + (torch.rand(len(idx), samples_per_ray, device=r.device)) * (t1 - t0)[:, None]
        pts = torch.as_tensor(view["position"], device=r.device)[None, None, :] + s[..., None] * d[:, None, :]
        cos_sun = (d * r.sun_dir).sum(-1)
        with torch.no_grad():
            feats.append(ctx.features.build(pts, d, cos_sun).reshape(-1, ctx.features.n_features))
    allf = torch.cat(feats, dim=0).cpu().numpy()
    mean = allf.mean(axis=0)
    std = allf.std(axis=0)
    std[std < 1e-3] = 1.0
    return mean.astype(np.float32), std.astype(np.float32)


# ----------------------------------------------------------------------------
# Evaluation
# ----------------------------------------------------------------------------
def evaluate(ctx, jobs, mlp, steps: int, mode: str = "neural", channel: str = "sun") -> dict:
    out = {}
    with torch.no_grad():
        for job in jobs:
            target = torch.as_tensor(job.image[..., 0], device=ctx.renderer.device)
            res = ctx.renderer.render(
                {**ctx.cfg["camera"], **job.view}, job.width, job.height,
                mode=mode, steps=steps,
                mlp=mlp, feature_builder=ctx.features, channel=channel,
            )
            pred = res.color[..., 0]
            err = (pred - target).abs()
            out[job.id] = {
                "rel_mae": float(err.mean() / target.mean().clamp_min(1e-9)),
                "mae": float(err.mean()),
                "psnr": float(10.0 * math.log10(1.0 / max(float(((pred - target) ** 2).mean()), 1e-12))),
                "target_mean": float(target.mean()),
                "pred_mean": float(pred.mean()),
            }
    return out


# ----------------------------------------------------------------------------
# Main
# ----------------------------------------------------------------------------
def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--index", default="outputs/dataset/index.json")
    ap.add_argument("--out", default="outputs/runs/exp1")
    ap.add_argument("--init-model", help="fine-tune a checkpoint with its feature layout and normalization")
    ap.add_argument("--iterations", type=int, default=600)
    ap.add_argument("--steps", type=int, default=64)
    ap.add_argument("--lr", type=float, default=2e-3)
    ap.add_argument("--hidden", type=int, default=64)
    ap.add_argument("--layers", type=int, default=4)
    ap.add_argument("--out-scale", type=float, default=0.25)
    ap.add_argument("--lambda-log", type=float, default=0.35)
    ap.add_argument("--lambda-grad", type=float, default=0.0)
    ap.add_argument("--alpha", type=float, default=10.0)
    ap.add_argument("--seed", type=int, default=1234)
    ap.add_argument("--eval-every", type=int, default=50)
    ap.add_argument("--channel", default="sun", choices=["sun", "sky"])
    ap.add_argument("--sky-boundary", choices=("slab_sky", "sky"), default="slab_sky",
                    help="use the reference top boundary; sky reproduces the historical infinite dome")
    ap.add_argument("--device", default="cuda")
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)
    device = resolve_device(args.device)
    torch.manual_seed(args.seed)
    random.seed(args.seed)
    np.random.seed(args.seed)

    names = feature_names(args.channel)
    suffix = "" if args.channel == "sun" else "_sky"
    n_features = len(names)

    dataset = ReferenceDataset(args.index)
    train_jobs = dataset.split("train" + suffix)
    val_jobs = dataset.split("val" + suffix)
    assert train_jobs, f"no training jobs for split train{suffix} in {args.index}"
    print(f"[data] {len(train_jobs)} train / {len(val_jobs)} val jobs (channel={args.channel})")

    ctxs = build_contexts(dataset, train_jobs + val_jobs, device, channel=args.channel, sky_boundary=args.sky_boundary)
    ctx_of = {}
    for job in train_jobs + val_jobs:
        ctx_of[job.id] = ctxs[(job.scene, job.sun)]

    if args.init_model:
        mlp, meta = MultiScatterMLP.load(args.init_model)
        if (mlp.in_dim != n_features or meta.get("features", names) != names
                or meta.get("channel", args.channel) != args.channel):
            raise ValueError("initial checkpoint and selected lighting channel have different features")
        args.hidden, args.layers, args.out_scale = mlp.hidden, mlp.layers, mlp.out_scale
    else:
        mlp = MultiScatterMLP(n_features, hidden=args.hidden, layers=args.layers,
                              out_scale=args.out_scale)
        mean, std = collect_feature_stats(ctx_of, train_jobs)
        mlp.set_feature_norm(mean, std)
    mlp = mlp.to(device)
    print(f"[model] {mlp.num_parameters()} parameters, {n_features} features")

    opt = torch.optim.Adam(mlp.parameters(), lr=args.lr)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=args.iterations,
                                                       eta_min=args.lr * 0.05)

    log = {"args": vars(args), "features": names, "iters": []}
    best = float("inf")
    t_start = time.perf_counter()

    for it in range(1, args.iterations + 1):
        job = train_jobs[int(torch.randint(len(train_jobs), (1,)).item())]
        ctx = ctx_of[job.id]
        target = torch.as_tensor(job.image[..., 0], device=device)
        out = ctx.renderer.render(
            {**ctx.cfg["camera"], **job.view}, job.width, job.height,
            mode="neural", steps=args.steps, mlp=mlp, feature_builder=ctx.features,
            channel=args.channel,
        )
        pred = out.color[..., 0]
        l1 = relative_l1(pred, target)
        lg = log_l1(pred, target, args.alpha)
        loss = l1 + args.lambda_log * lg
        if args.lambda_grad > 0:
            loss = loss + args.lambda_grad * gradient_l1(pred, target) / target.mean().clamp_min(1e-9)

        opt.zero_grad(set_to_none=True)
        if loss.requires_grad:
            loss.backward()
            torch.nn.utils.clip_grad_norm_(mlp.parameters(), 1.0)
            opt.step()
        sched.step()

        evaluate_now = it % args.eval_every == 0 or it == args.iterations
        if it % 10 == 0 or it == 1 or evaluate_now:
            entry = {"it": it, "loss": float(loss.detach()), "l1": float(l1.detach()), "log": float(lg.detach()),
                     "lr": float(sched.get_last_lr()[0]), "job": job.id}
            log["iters"].append(entry)
            print(f"[{it:4d}] loss={float(loss.detach()):.4f} l1={float(l1.detach()):.4f} "
                  f"log={float(lg.detach()):.4f} ({time.perf_counter() - t_start:.0f}s)", flush=True)

        if evaluate_now:
            scores = {}
            for job in val_jobs:
                scores.update(evaluate(ctx_of[job.id], [job], mlp, args.steps, channel=args.channel))
            mean_rel = float(np.mean([s["rel_mae"] for s in scores.values()]))
            log["iters"][-1]["val"] = scores
            log["iters"][-1]["val_rel_mae"] = mean_rel
            print(f"      val rel_mae={mean_rel:.4f} " +
                  " ".join(f"{k}={v['rel_mae']:.3f}" for k, v in scores.items()), flush=True)
            mlp.save(os.path.join(args.out, "model_last.pt"),
                     extra={"features": names, "channel": args.channel, "args": vars(args)})
            if mean_rel < best:
                best = mean_rel
                mlp.save(os.path.join(args.out, "model_best.pt"),
                         extra={"features": names, "channel": args.channel, "args": vars(args)})

    mlp.save(os.path.join(args.out, "model_last.pt"),
             extra={"features": names, "channel": args.channel, "args": vars(args)})
    mlp.eval()
    mlp.export_numpy(os.path.join(args.out, "model_numpy.npz"), names)
    log["best_val_rel_mae"] = best
    log["train_seconds"] = time.perf_counter() - t_start
    with open(os.path.join(args.out, "train_log.json"), "w", encoding="utf-8") as fh:
        json.dump(log, fh, indent=2)
    save_config({"features": names, "args": vars(args),
                 "feature_mean": mlp.feat_mean.tolist(), "feature_std": mlp.feat_std.tolist()},
                os.path.join(args.out, "train_config.json"))
    print(f"[done] best val rel_mae={best:.4f} in {log['train_seconds']:.0f}s -> {args.out}")


if __name__ == "__main__":
    main()
