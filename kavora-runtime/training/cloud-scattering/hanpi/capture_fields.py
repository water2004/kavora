# SPDX-License-Identifier: Apache-2.0
"""Capture production density features on disjoint cloud slabs for transport training."""

import argparse
from pathlib import Path
import sys

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "tests/rtx/unit"))
from test_cloud_transport import OPTIONS, Probe, cloud_parameters, cloud_textures, spy

# Independent weather regions. The last low and high slabs are reserved for evaluation.
REGIONS = [(8043, 1240), (-18000, 7300), (28000, -19000), (2000, -41000), (37000, 19000), (-62000, -31000)]


def capture(output, smoke=False):
    output.mkdir(parents=True, exist_ok=True)
    device = spy.Device(type=spy.DeviceType.vulkan, compiler_options=OPTIONS)
    probe = Probe(device)
    textures = cloud_textures(device, None)
    for region, (cx, cz) in enumerate(REGIONS):
        for layer in (0, 1):
            side, levels = (24, 16) if smoke else (96, 48)
            span = 6000.0 if layer == 0 else 24000.0
            base, depth, extinction = (800.0, 1500.0, 0.03) if layer == 0 else (6375.0, 50.0, 0.024)
            args = cloud_parameters(
                groundReference=[cx, 0, cz],
                baseHeight=800,
                thickness=1500,
                extinction=0.03,
                highBaseHeight=6375,
                highThickness=50,
                highExtinction=0.024,
                highCoverage=0.35,
                coverage=(0.45, 0.65, 0.55)[region % 3],
            )
            axis = np.arange(side) * (span / side) - span / 2
            x, z = np.meshgrid(axis + cx, axis + cz)
            fields = []
            for y in np.linspace(base, base + depth, levels):
                points = np.column_stack((x.ravel(), np.full(x.size, y), z.ravel(), np.full(x.size, layer)))
                fields.append(
                    probe.run("neuralDensities", points, clouds=args, textures=textures).reshape(
                        side, side, 4
                    )
                )
            features = np.stack(fields, axis=1).transpose(3, 0, 1, 2).copy()
            np.savez(
                output / f"region-{region}-{layer}.npz",
                fields=features,
                span=span,
                height=depth,
                sigma=extinction,
            )
            print(region, layer, "occupied", np.mean(features[0] > 0.01), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("out", type=Path)
    parser.add_argument("--smoke", action="store_true")
    args = parser.parse_args()
    capture(args.out, args.smoke)
