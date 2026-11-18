# SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors
# SPDX-License-Identifier: MIT
"""Pack the selected Neural_Volumetric_Cloud solar model for the Vulkan pass."""

import argparse
import hashlib
import json
from pathlib import Path

import numpy as np


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("weights", type=Path, help="sun.npz from the selected model export")
    parser.add_argument("output", type=Path)
    options = parser.parse_args()
    digest = hashlib.sha256(options.weights.read_bytes()).hexdigest()
    if digest != "75d0aaddf7e87fbf74412f269998620b05ca1f4b2a5ed4fedd72ae42a63382b9":
        parser.error("Expected the selected depth64_angles solar export")

    packed = []
    with np.load(options.weights, allow_pickle=False) as weights:
        config = json.loads(weights["config"].item())
        if (config["in_dim"], config["hidden"], config["layers"], config["out_scale"], config["activation"]) != (15, 96, 4, 0.25, "silu"):
            parser.error("Model does not match the shader layout")
        for layer in range(4):
            matrix = weights[f"body.{2 * layer}.weight"]
            bias = weights[f"body.{2 * layer}.bias"]
            if layer == 0:
                matrix = matrix / weights["feat_std"]
                bias = bias - matrix @ weights["feat_mean"]
            packed.extend((bias, matrix.T.flatten()))
        packed.extend((weights["head.bias"], weights["head.weight"].flatten()))

    # Match the experimental demo's 25 directions and order-2 projection.
    index = np.arange(25) + 0.5
    phi = np.arccos(1.0 - 2.0 * index / 25.0)
    theta = np.pi * (1.0 + np.sqrt(5.0)) * index
    directions = np.stack((np.cos(theta) * np.sin(phi), np.cos(phi), np.sin(theta) * np.sin(phi)), axis=1)
    x, y, z = directions.T
    basis = np.stack((np.ones_like(x), x, y, z, x * y, y * z, z * x, x * x - y * y, 3.0 * z * z - 1.0), axis=1)
    packed.extend((directions.flatten(), np.linalg.pinv(basis).flatten()))
    options.output.write_bytes(np.concatenate(packed).astype("<f4").tobytes())


if __name__ == "__main__":
    main()
