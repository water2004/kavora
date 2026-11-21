# SPDX-License-Identifier: Apache-2.0
"""Capture production density features on disjoint cloud slabs for transport training."""

import argparse
from pathlib import Path
import re
import sys

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "tests/rtx/unit"))
from test_cloud_transport import OPTIONS, SHADERS, Probe, cloud_parameters, cloud_textures, spy

# Regions 4 and 5 are reserved for checkpoint selection and evaluation.
LOW_REGIONS = [(8043, 1240), (-18000, 7300), (36000, -3000), (2000, -41000), (37000, 19000),
               (-62000, -31000), (-44000, 50000), (14000, -33000)]
HIGH_REGIONS = [(-150000, 300000), (100000, 100000), (200000, 50000), (-200000, -100000),
                (-350000, -350000), (300000, 250000), (75000, -280000), (-400000, 90000)]


def cloud_medium_defaults():
    """Use the native cloud shell geometry and extinction for reference transport."""
    header = SHADERS.parent / "rtx_render/rtx_global_volumetrics.h"
    defaults = dict(re.findall(r'RTX_OPTION_(?:ARGS|FLAG)\("rtx\.clouds", float, (\w+), ([\d.]+)f,',
                               header.read_text(encoding="utf-8")))
    base = float(defaults["worldBaseHeightMeters"]) + float(defaults["altitudeOffsetMeters"])
    thickness = float(defaults["thicknessMeters"])
    return {
        "baseHeight": base,
        "thickness": thickness,
        "extinction": float(defaults["extinctionPerMeter"]),
        "highBaseHeight": max(base + thickness + 1000.0, float(defaults["highAltitudeMeters"])),
        "highThickness": float(defaults["highThicknessMeters"]),
        "highExtinction": float(defaults["highExtinctionPerMeter"]),
        "highCoverage": float(defaults["highCoverage"]),
        "shapeScale": float(defaults["shapeScaleMeters"]),
        "coverage": float(defaults["coverage"]),
    }


def capture(output, smoke=False):
    output.mkdir(parents=True, exist_ok=True)
    device = spy.Device(type=spy.DeviceType.vulkan, compiler_options=OPTIONS)
    probe = Probe(device)
    textures = cloud_textures(device, None)
    defaults = cloud_medium_defaults()
    media = (
        (defaults["baseHeight"], defaults["thickness"], defaults["extinction"]),
        (defaults["highBaseHeight"], defaults["highThickness"], defaults["highExtinction"]),
    )
    print("Reference cloud media (base, thickness, extinction):", media, flush=True)
    for region, (low, high) in enumerate(zip(LOW_REGIONS, HIGH_REGIONS)):
        for layer, (cx, cz) in enumerate((low, high)):
            side, levels = (24, 16) if smoke else (192, 81) if layer == 0 else (96, 48)
            span = 12000.0 if layer == 0 else 24000.0
            base, depth, extinction = media[layer]
            args = cloud_parameters(**defaults)
            args["groundReference"] = [cx, 0, cz]
            args["coverage"] += (0.0, 0.15, 0.05)[region % 3]
            if region == 7:
                args["wetness"] = .45
            axis = np.arange(side) * (span / side) - span / 2
            x, z = np.meshgrid(axis + cx, axis + cz)
            offset_squared = (x - cx)**2 + (z - cz)**2
            radius = args["planetRadius"]
            fields, base_density = [], []
            for y in np.linspace(base, base + depth, levels):
                slab_y = np.sqrt((radius + y)**2 - offset_squared) - radius
                points = np.column_stack((x.ravel(), slab_y.ravel(), z.ravel(), np.full(x.size, layer)))
                fields.append(
                    probe.run("neuralDensities", points, clouds=args, textures=textures).reshape(
                        side, side, 4
                    )
                )
                if layer == 0:
                    base_density.append(probe.run("neuralBaseDensity", points, clouds=args,
                                                  textures=textures)[:, 0].reshape(side, side))
            features = np.stack(fields, axis=1).transpose(3, 0, 1, 2).copy()
            coarse_density = np.stack(base_density, axis=1) if layer == 0 else features[0]
            np.savez(output / f"region-{region}-{layer}.npz", fields=features, baseDensity=coarse_density,
                     span=span, height=depth, sigma=extinction)
            print(region, layer, "occupied", np.mean(features[0] > 0.01), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("out", type=Path)
    parser.add_argument("--smoke", action="store_true")
    args = parser.parse_args()
    capture(args.out, args.smoke)
