# SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors
# SPDX-License-Identifier: MIT
"""Pack a trained solar scattering model for Kavora's Vulkan pass."""

import argparse
import hashlib
import json
from pathlib import Path

import numpy as np

from neural.feature_layout import SUN_FEATURE_NAMES

_REFERENCE_EXPORT_SHA256 = "75d0aaddf7e87fbf74412f269998620b05ca1f4b2a5ed4fedd72ae42a63382b9"
_LAYOUT = {"in_dim": 15, "hidden": 96, "layers": 4, "out_scale": 0.25, "activation": "silu"}


def _read_tensor(weights, name, shape):
    value = weights[name]
    if value.shape != shape or value.dtype != np.float32 or not np.isfinite(value).all():
        raise ValueError(f"{name} must be a finite float32 array of shape {shape}")
    return value


def pack_weights(source: Path, output: Path) -> None:
    packed = []
    with np.load(source, allow_pickle=False) as weights:
        config = json.loads(weights["config"].item())
        if config != _LAYOUT:
            raise ValueError(f"Model configuration must match the Vulkan layout {_LAYOUT}")
        if "features" in weights:
            if weights["features"].tolist() != SUN_FEATURE_NAMES:
                raise ValueError("The feature order must match Kavora's solar model")
        elif hashlib.sha256(source.read_bytes()).hexdigest() != _REFERENCE_EXPORT_SHA256:
            raise ValueError("The export must declare its feature order or be the original sun.npz")
        inputs = _LAYOUT["in_dim"]
        width = _LAYOUT["hidden"]
        for layer in range(_LAYOUT["layers"]):
            prefix = f"body.{2 * layer}"
            matrix = _read_tensor(weights, f"{prefix}.weight", (width, inputs if layer == 0 else width))
            bias = _read_tensor(weights, f"{prefix}.bias", (width,))
            if layer == 0:
                std = _read_tensor(weights, "feat_std", (inputs,))
                if np.any(std < 1e-3):
                    raise ValueError("feat_std must be at least 0.001")
                matrix = matrix / std
                bias = bias - matrix @ _read_tensor(weights, "feat_mean", (inputs,))
            packed.extend((bias, matrix.T.flatten()))
        packed.extend((_read_tensor(weights, "head.bias", (1,)),
                       _read_tensor(weights, "head.weight", (1, width)).flatten()))

    # Match the experimental demo's 25 directions and order-2 projection.
    index = np.arange(25) + 0.5
    phi = np.arccos(1.0 - 2.0 * index / 25.0)
    theta = np.pi * (1.0 + np.sqrt(5.0)) * index
    directions = np.stack((np.cos(theta) * np.sin(phi), np.cos(phi), np.sin(theta) * np.sin(phi)), axis=1)
    x, y, z = directions.T
    basis = np.stack((np.ones_like(x), x, y, z, x * y, y * z, z * x, x * x - y * y, 3.0 * z * z - 1.0), axis=1)
    packed.extend((directions.flatten(), np.linalg.pinv(basis).flatten()))
    payload = np.concatenate(packed).astype("<f4")
    if not np.isfinite(payload).all():
        raise ValueError("The packed weights contain non-finite coefficients")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(payload.tobytes())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("weights", type=Path, help="Model NumPy export")
    parser.add_argument("output", type=Path, help="Packed Vulkan weights")
    args = parser.parse_args()
    try:
        pack_weights(args.weights, args.output)
    except (ValueError, KeyError, OSError) as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
