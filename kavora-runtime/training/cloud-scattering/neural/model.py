# SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
# SPDX-License-Identifier: Apache-2.0
"""Neural multiple-scattering source model (report §6.2): a small point MLP."""
from __future__ import annotations

import json
import os

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F


class MultiScatterMLP(nn.Module):
    """Predicts the non-negative multi-scatter source ``J_ms >= 0`` at a point.

    Input: feature vector (see :mod:`neural.features`).
    Output: ``J_ms`` in the same units as the single-scatter source ``J1``.
    """

    def __init__(self, in_dim: int, hidden: int = 64, layers: int = 4,
                 out_scale: float = 0.25, activation: str = "silu"):
        super().__init__()
        self.in_dim = int(in_dim)
        self.hidden = int(hidden)
        self.layers = int(layers)
        self.out_scale = float(out_scale)
        self.activation = activation

        act = {"silu": nn.SiLU, "relu": nn.ReLU, "gelu": nn.GELU}[activation]
        dims = [self.in_dim] + [self.hidden] * self.layers
        blocks = []
        for i in range(len(dims) - 1):
            blocks.append(nn.Linear(dims[i], dims[i + 1]))
            blocks.append(act())
        self.body = nn.Sequential(*blocks)
        self.head = nn.Linear(self.hidden, 1)
        nn.init.zeros_(self.head.weight)
        nn.init.constant_(self.head.bias, -2.0)

        self.register_buffer("feat_mean", torch.zeros(self.in_dim))
        self.register_buffer("feat_std", torch.ones(self.in_dim))

    # ------------------------------------------------------------------
    def set_feature_norm(self, mean: np.ndarray, std: np.ndarray) -> None:
        with torch.no_grad():
            self.feat_mean.copy_(torch.as_tensor(np.asarray(mean, dtype=np.float32)))
            self.feat_std.copy_(torch.as_tensor(np.maximum(np.asarray(std, dtype=np.float32), 1e-3)))

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        z = (x - self.feat_mean) / self.feat_std
        return F.softplus(self.head(self.body(z))) * self.out_scale

    # ------------------------------------------------------------------
    def config(self) -> dict:
        return {
            "in_dim": self.in_dim,
            "hidden": self.hidden,
            "layers": self.layers,
            "out_scale": self.out_scale,
            "activation": self.activation,
        }

    @classmethod
    def from_config(cls, cfg: dict) -> "MultiScatterMLP":
        return cls(cfg["in_dim"], cfg["hidden"], cfg["layers"], cfg["out_scale"],
                   cfg.get("activation", "silu"))

    def save(self, path: str, extra: dict | None = None) -> None:
        os.makedirs(os.path.dirname(os.path.abspath(path)) or ".", exist_ok=True)
        payload = {"config": self.config(), "state": self.state_dict(), "extra": extra or {}}
        torch.save(payload, path)

    @classmethod
    def load(cls, path: str, map_location="cpu"):
        payload = torch.load(path, map_location=map_location, weights_only=True)
        model = cls.from_config(payload["config"])
        model.load_state_dict(payload["state"])
        return model, payload.get("extra", {})

    def export_numpy(self, path: str, features: list[str]) -> None:
        """Dump weights so the runtime can run *without* torch (portability)."""
        sd = {k: v.detach().cpu().numpy() for k, v in self.state_dict().items()}
        np.savez(path, **sd, config=json.dumps(self.config()), features=np.asarray(features))

    def num_parameters(self) -> int:
        return sum(p.numel() for p in self.parameters())
