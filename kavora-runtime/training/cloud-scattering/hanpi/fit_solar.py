# SPDX-License-Identifier: Apache-2.0
"""Fit and validate the directional and upper-hemisphere sources."""

import argparse
import copy
import json
from pathlib import Path
import sys

import numpy as np
import torch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from neural.feature_layout import feature_names
from neural.model import MultiScatterMLP
from pack_weights import pack_weights


def dataset(files, device, channel="sun"):
    xs, ys = [], []
    for path in files:
        with np.load(path) as data:
            prefix = "" if channel == "sun" else "sky_"
            features = data[prefix + "features"]
            target = data[prefix + "source"]
            if channel == "sun":
                # The beam lookup is consumed at collisions. Empty positions
                # can have the same three integrated depths as actual material.
                occupied = data["sky_features"][:, 0] > 0
                features, target = features[occupied], target[occupied]
            xs.append(features)
            ys.append(target)
    return tuple(torch.tensor(np.concatenate(arrays), device=device) for arrays in (xs, ys))


def fit_channel(files, device, channel, activation, smoke):
    torch.manual_seed(3321)
    def region(path):
        return int(path.stem.split("-")[1])
    x, y = dataset([p for p in files if region(p) not in (4, 5)], device, channel)
    val, reference = dataset([p for p in files if region(p) == 4], device, channel)
    model = MultiScatterMLP(len(feature_names(channel)), hidden=96, layers=4, activation=activation).to(device)
    model.set_feature_norm(x.mean(0).cpu().numpy(), x.std(0).cpu().numpy())
    optimizer = torch.optim.AdamW(model.parameters(), lr=0.002, weight_decay=0.0001)
    iterations = 2 if smoke else 6000
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, iterations, eta_min=0.0001)
    if channel == "sun":
        # Balance the forward peak against the rest of the angular domain.
        # All optical states in a material/angle bin carry the same loss weight.
        edges = x.new_tensor([-.98, -.75, 0, .75, .95, .99, .999, .9999])
        bins = torch.bucketize(x[:, 4].contiguous(), edges)
        bins += (x[:, -1] > x[:, -1].mean()).long() * (len(edges) + 1)
        means = torch.bincount(bins, weights=y) / torch.bincount(bins).clamp(min=1)
        loss_scales = means[bins].clamp(min=.01)
        angle_bands = torch.bucketize(val[:, 4].contiguous(), val.new_tensor([-.98, -.75, .75, .99]))
        validation_bands = [angle_bands == band for band in range(5)]
        validation_bands = [(mask, reference[mask].mean().clamp(min=.01))
                            for mask in validation_bands if mask.any()]
    best = float("inf")
    for step in range(1, iterations + 1):
        ids = torch.randint(len(x), (512,), device=device)
        target = y[ids]
        predicted = model(x[ids]).ravel()
        # The variance of the path estimate grows with the expected source.
        # Detaching the variance weight retains the conditional mean as target.
        scale = loss_scales[ids] if channel == "sun" else (0.02 + predicted.detach()).sqrt()
        loss = ((predicted - target).square() / scale).mean()
        optimizer.zero_grad(set_to_none=True)
        loss.backward()
        optimizer.step()
        scheduler.step()
        if step % 100 == 0 or step == iterations:
            with torch.no_grad():
                residual = model(val).ravel() - reference
                absolute = residual.abs()
                if channel == "sun":
                    # Give each angular band equal influence on checkpoint selection.
                    error = sum(((absolute[mask].mean() + residual[mask].mean().abs()) / scale).item()
                                for mask, scale in validation_bands)
                else:
                    error = absolute.mean().item()
            if error < best:
                best = error
                selected = copy.deepcopy(model.state_dict())
    model.load_state_dict(selected)
    model.cpu()
    metrics = {}
    with torch.no_grad():
        for path in (p for p in files if region(p) == 5):
            x, y = dataset([path], "cpu", channel)
            p = model(x).ravel()
            cosine = x[:, 4] if channel == "sun" else x[:, 6]
            for name, mask in (
                ("all", torch.ones_like(y, dtype=torch.bool)),
                ("forward", cosine > .99),
                ("backscatter", cosine < -.75),
            ):
                if mask.any():
                    metrics[f"{path.stem}/{name}"] = {
                        "count": int(mask.sum()),
                        "reference": y[mask].mean().item(),
                        "prediction": p[mask].mean().item(),
                        "mae": (p[mask] - y[mask]).abs().mean().item(),
                    }
    return model, metrics


def fit(labels, out, device, activation, smoke=False):
    out.mkdir(parents=True, exist_ok=True)
    files = sorted(labels.glob("region-*.npz"))
    if {p.stem for p in files} != {f"region-{r}-{l}" for r in range(8) for l in range(2)}:
        raise ValueError("Expected eight independent regions of both cloud layers")
    metrics = {}
    for channel in ("sun", "sky"):
        model, metrics[channel] = fit_channel(files, device, channel, activation, smoke)
        model.export_numpy(out / f"{channel}.npz", feature_names(channel))
    pack_weights(out / "sun.npz", out / "sky.npz", out / "cloud_neural_lighting.bin", activation)
    report = json.dumps(metrics, indent=2)
    (out / "validation.json").write_text(report + "\n", encoding="utf-8")
    print(report, flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("labels", type=Path)
    parser.add_argument("out", type=Path)
    parser.add_argument("--device", default="cuda")
    parser.add_argument("--activation", choices=("silu", "leaky_relu"), default="leaky_relu")
    parser.add_argument("--smoke", action="store_true")
    args = parser.parse_args()
    fit(args.labels, args.out, args.device, args.activation, args.smoke)
