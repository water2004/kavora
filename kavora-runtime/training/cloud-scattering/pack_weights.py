# SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors
# SPDX-License-Identifier: MIT
"""Pack the solar and upper-hemisphere scattering models for Kavora's Vulkan pass."""

import argparse
import json
from pathlib import Path

import numpy as np

from neural.feature_layout import SKY_FEATURE_NAMES, SUN_FEATURE_NAMES

_LAYOUT = {"in_dim": 16, "hidden": 96, "layers": 4, "out_scale": 0.25, "activation": "silu"}


def _read_tensor(weights, name, shape):
    value = weights[name]
    if value.shape != shape or value.dtype != np.float32 or not np.isfinite(value).all():
        raise ValueError(f"{name} must be a finite float32 array of shape {shape}")
    return value


def model_payload(source: Path, channel: str, activation: str = "leaky_relu") -> np.ndarray:
    names = SKY_FEATURE_NAMES if channel == "sky" else SUN_FEATURE_NAMES
    inputs = len(names)
    packed = []
    with np.load(source, allow_pickle=False) as weights:
        config = json.loads(weights["config"].item())
        if config != {**_LAYOUT, "in_dim": inputs, "activation": activation}:
            raise ValueError(f"Model configuration does not match the {channel} network layout")
        if "features" not in weights or weights["features"].tolist() != names:
            raise ValueError(f"The feature order must match Kavora's {channel} model")
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
                matrix = np.pad(matrix, ((0, 0), (0, _LAYOUT["in_dim"] - inputs)))
            packed.extend((bias, matrix.T.flatten()))
        packed.extend((_read_tensor(weights, "head.bias", (1,)),
                       _read_tensor(weights, "head.weight", (1, width)).flatten()))

    payload = np.concatenate(packed).astype("<f4")
    if not np.isfinite(payload).all():
        raise ValueError("The packed weights contain non-finite coefficients")
    return payload


def pack_weights(sun: Path, sky: Path, output: Path, activation: str = "leaky_relu") -> None:
    payload = np.concatenate((model_payload(sun, "sun", activation), model_payload(sky, "sky", activation)))
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(payload.tobytes())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("sun", type=Path, help="Solar model NumPy export")
    parser.add_argument("sky", type=Path, help="Upper-hemisphere model NumPy export")
    parser.add_argument("output", type=Path, help="Packed Vulkan weights")
    parser.add_argument("--activation", choices=("silu", "leaky_relu"), default="leaky_relu")
    args = parser.parse_args()
    try:
        pack_weights(args.sun, args.sky, args.output, args.activation)
    except (ValueError, KeyError, OSError) as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
