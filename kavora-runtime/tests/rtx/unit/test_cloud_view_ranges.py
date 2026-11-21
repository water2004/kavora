# SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
# SPDX-License-Identifier: MIT
"""Exercise full-range view transport, including cloud-rich and above-cloud rays."""
import argparse
import json
from pathlib import Path

import numpy as np
import slangpy as spy


def full_range_rays():
    origins, directions = [], []
    for height in (139.0, 1200.0, 3000.0):
        for position_angle in np.linspace(0, 2 * np.pi, 12, endpoint=False):
            origin = [8043 + 8192 * np.cos(position_angle), height,
                      1240 + 8192 * np.sin(position_angle)]
            for azimuth in np.linspace(0, 2 * np.pi, 12, endpoint=False):
                for angle in (10, 30, 75):
                    elevation = np.radians(-angle if height == 3000 else angle)
                    origins.append(origin)
                    directions.append([np.cos(elevation) * np.cos(azimuth), np.sin(elevation),
                                       np.cos(elevation) * np.sin(azimuth), 0])
    return np.asarray(origins, np.float32), np.asarray(directions, np.float32)


def check_view_ranges(probe, textures, clouds):
    origins, directions = full_range_rays()
    results = []
    for name, changes in (("broken", {"coverage": 0.45}),
                          ("cloud-rich", {"coverage": 0.85}),
                          ("rain", {"coverage": 0.85, "wetness": 1.0})):
        for steps in (64, 128, 256, 384):
            parameters = {**clouds, **changes, "steps": steps, "baseHeight": 800.0,
                          "extinction": 0.03, "highExtinction": 0.0,
                          "maxDistance": 96000.0, "viewAngularFootprint": 0.003}
            previous = np.ones(len(origins))
            for distance in (1700.0, 12000.0, 24000.0, 96000.0):
                rays = np.column_stack((origins, np.full(len(origins), distance)))
                transmission = probe.run("viewTransmission", rays, directions, parameters, textures)[:, 0]
                assert np.isfinite(transmission).all() and np.all((0 <= transmission) & (transmission <= 1))
                assert np.all(transmission <= previous + 2e-6), (
                    "extending a view-ray prefix increased transmitted energy", name, steps, distance,
                    float(np.max(transmission - previous)))
                previous = transmission
                results.append({"weather": name, "steps": steps, "distanceMeters": distance,
                                "rays": len(origins), "meanTransmission": float(transmission.mean()),
                                "openRays": int(np.count_nonzero(transmission > 0.1)),
                                "cloudRays": int(np.count_nonzero(transmission < 0.95))})
            assert np.any(previous < 0.95), ("full-range rays missed every cloud", name, steps)
    # Opaque rain horizons are valid. Only the broken-weather fixture is required
    # to include open rays; this is not a mandate to remove clouds in every sky.
    assert all(row["openRays"] > 0 for row in results if row["weather"] == "broken")
    disabled = probe.run("viewTransmission", np.column_stack((origins, np.full(len(origins), 96000.0))),
                         directions, {**clouds, "enable": 0}, textures)[:, 0]
    np.testing.assert_array_equal(disabled, 1)
    print(f"PASS full-range view transport: {sum(row['rays'] for row in results)} ray prefixes, "
          "12 locations, three elevations/heights, three weather states and four budgets")
    return results


def main():
    from test_cloud_transport import OPTIONS, Probe, cloud_parameters, cloud_textures

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dump", type=Path)
    args = parser.parse_args()
    device = spy.Device(type=spy.DeviceType.vulkan, compiler_options=OPTIONS)
    results = check_view_ranges(Probe(device), cloud_textures(device, args.dump), cloud_parameters())
    if args.dump:
        args.dump.mkdir(parents=True, exist_ok=True)
        (args.dump / "cloud-full-range-transport.json").write_text(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()
