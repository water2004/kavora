# SPDX-FileCopyrightText: Copyright (c) 2026 HaringPro
# SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors
# SPDX-License-Identifier: Apache-2.0
"""Revelation's scalar log2 phase tables and Kavora's derived transport data."""
import argparse
from pathlib import Path

import numpy as np

RUNTIME = Path(__file__).resolve().parents[3] / "src/dxvk"
ASSET = RUNTIME / "rtx_render/clouds/revelation_phase.bin"
CONSTANTS = RUNTIME / "shaders/rtx/utility/cloud_phase_constants.slang"
WIDTH = 512
ROWS = 3
# The mean angular radius of Kavora's 0.53-degree sun and 0.52-degree moon.
DISK_RADIUS = np.deg2rad(0.2625)


def coordinate(cosine):
    centered = np.arccos(np.clip(cosine, -1, 1)) * (2 / np.pi) - 1
    symmetric = 0.5 + 0.5 * centered / (1.98 - 0.98 * np.abs(centered))
    return symmetric / (0.84 + 0.16 * symmetric)


def cosine_at_coordinate(u):
    centered = 2 * 0.84 * u / (1 - 0.16 * u) - 1
    return np.cos(np.pi * (0.5 + 0.5 * 1.98 * centered / (1 + 0.98 * np.abs(centered))))


def raw_rows(source=ASSET):
    with source.open("rb") as asset:
        return np.frombuffer(asset.read(2 * WIDTH * ROWS), "<f2").reshape(ROWS, WIDTH)


def table_probability(row, cosine):
    return np.exp2(np.interp(coordinate(cosine) * (WIDTH - 1), np.arange(WIDTH), row))


def transport_tables(source=ASSET):
    """Use the runtime's log-linear interpolation for moments and inverse CDFs."""
    rows = raw_rows(source).astype(np.float64)
    mu = -np.cos(np.linspace(0, np.pi, 32769))
    values = np.array([table_probability(row, mu) for row in rows])
    mass = np.diff(mu) * np.pi * (values[:, 1:] + values[:, :-1])
    cdf = np.column_stack((np.zeros(ROWS), np.cumsum(mass, axis=1)))
    norm = cdf[:, -1].copy()
    cdf /= norm[:, None]
    g = (2 * np.pi * np.trapezoid(values * mu, x=mu, axis=1)) / norm
    inverse = np.stack([np.interp(np.linspace(0, 1, 16385), f, mu) for f in cdf])
    return rows, norm, g, inverse


def prefilter_disk(row, norm, view_mu):
    # Integrate the radiance of a uniform disk in solid angle.
    knots, weights = np.polynomial.legendre.leggauss(16)
    disk_mu = 1 - (1 - np.cos(DISK_RADIUS)) * (knots + 1) * 0.5
    azimuth = (np.arange(64) + 0.5) * (2 * np.pi / 64)
    projection = np.sqrt(1 - disk_mu**2)[:, None] * np.cos(azimuth)
    mu = view_mu[:, None, None] * disk_mu[None, :, None] + (
        np.sqrt(np.maximum(0, 1 - view_mu**2))[:, None, None] * projection[None, :, :]
    )
    return np.sum(table_probability(row, mu).mean(axis=2) * weights * 0.5, axis=1) / norm


def sky_quadrature(inverse):
    # Sample the *phase* measure so narrow forward peaks contribute their
    # correct fraction of the energy, including at the zenith and nadir.
    mu = np.interp((np.arange(4096) + 0.5) / 4096, np.linspace(0, 1, len(inverse)), inverse)
    azimuth = (np.arange(256) + 0.5) * (2 * np.pi / 256)
    lateral = np.sqrt(1 - mu**2)[:, None] * np.cos(azimuth)
    for view_up in np.linspace(-1, 1, 4):
        up = (view_up * mu[:, None] + np.sqrt(1 - view_up**2) * lateral).ravel()
        upper = up[up > 0]
        bins = np.searchsorted([0.26, 0.57, 0.86], upper)
        counts = np.bincount(bins, minlength=4)
        sums = np.bincount(bins, weights=upper, minlength=4)
        yield counts / len(up), counts / sums


def vector(values):
    return f"float{len(values)}(" + ", ".join(f"{v:.9f}f" for v in values) + ")"


class TorchPhase:
    """Phase PDF and inverse CDF for the offline image reference renderer."""

    def __init__(self, material, device):
        import torch

        rows, norm, moments, inverse = transport_tables()
        self.inverse = inverse[material]
        self.normalization = float(norm[material])
        self.mean_cosine = float(moments[material])
        self.row = torch.tensor(rows[material], dtype=torch.float32, device=device)
        self.quantiles = torch.tensor(self.inverse, dtype=torch.float32, device=device)

    def probability(self, cosine):
        centered = cosine.clamp(-1, 1).acos() * (2 / np.pi) - 1
        symmetric = 0.5 + 0.5 * centered / (1.98 - 0.98 * centered.abs())
        coordinate = symmetric / (0.84 + 0.16 * symmetric) * (WIDTH - 1)
        index = coordinate.long().clamp(0, WIDTH - 2)
        return self.row[index].lerp(self.row[index + 1], coordinate - index).exp2() / self.normalization

    def sample(self, uniform):
        coordinate = uniform * (len(self.inverse) - 1)
        index = coordinate.long().clamp(0, len(self.inverse) - 2)
        return self.quantiles[index].lerp(self.quantiles[index + 1], coordinate - index)


def generate(source):
    rows, norm, g, inverse = transport_tables(source)
    phase_mu = cosine_at_coordinate(np.linspace(0, 1, WIDTH))
    disk = np.stack([prefilter_disk(row, n, phase_mu) for row, n in zip(rows, norm)])
    ASSET.write_bytes(np.vstack((rows, np.log2(disk))).astype("<f2").tobytes())
    quadrature = np.array([list(sky_quadrature(row)) for row in inverse])
    lines = [
        "// SPDX-License-Identifier: Apache-2.0",
        "// Generated by training/cloud-scattering/common/revelation_phase.py.",
        "module cloud_phase_constants;",
        "",
        f"public static const float3 kCloudPhaseNormalization = {vector(1 / norm)};",
        f"public static const float3 kCloudPhaseMeanCosine = {vector(g)};",
    ]
    for index, name in enumerate(("Weights", "Airmass")):
        lines.extend(("", f"public static const float4 kCloudPhaseSky{name}[12] = {{"))
        lines.extend(f"  {vector(values)}," for values in quadrature[:, :, index, :].reshape(12, 4))
        lines.append("};")
    CONSTANTS.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    print("Revelation phase integrals and first moments:", norm, g)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=ASSET)
    generate(parser.parse_args().source)
