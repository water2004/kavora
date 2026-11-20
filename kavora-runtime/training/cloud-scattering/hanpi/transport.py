# SPDX-License-Identifier: Apache-2.0
"""Solar and sky source after the first scattering in production-density slabs."""

import argparse
from pathlib import Path

import numpy as np
from numba import njit, prange

from common.revelation_phase import transport_tables


@njit
def sample(grid, p, span, height):
    nz, ny, nx = grid.shape
    if p[1] < 0 or p[1] > height:
        return 0.0
    x, y, z = (p[0] * nx / span) % nx, p[1] * (ny - 1) / height, (p[2] * nz / span) % nz
    i, j, k = int(x), int(y), int(z)
    dx, dy, dz = x - i, y - j, z - k
    i, k = i % nx, k % nz
    ix, jy, kz = (i + 1) % nx, min(j + 1, ny - 1), (k + 1) % nz
    a = (grid[k, j, i] * (1 - dx) + grid[k, j, ix] * dx) * (1 - dy) + (
        grid[k, jy, i] * (1 - dx) + grid[k, jy, ix] * dx
    ) * dy
    b = (grid[kz, j, i] * (1 - dx) + grid[kz, j, ix] * dx) * (1 - dy) + (
        grid[kz, jy, i] * (1 - dx) + grid[kz, jy, ix] * dx
    ) * dy
    return a * (1 - dz) + b * dz


@njit(parallel=True)
def sun_depth(rho, span, height, sigma, light):
    # Integrate one vertical interval at a time, from the lit boundary down.
    nz, ny, nx = rho.shape
    result = np.zeros_like(rho)
    ds = height / ((ny - 1) * light[1])
    steps = max(1, int(np.ceil(ds / (span / nx / 2))))
    for y in range(ny - 2, -1, -1):
        for z in prange(nz):
            for x in range(nx):
                p = np.array([x * span / nx, y * height / (ny - 1), z * span / nz])
                q = p + light * ds
                q[1] = (y + 1) * height / (ny - 1)
                tau = sample(result, q, span, height)
                for i in range(steps):
                    tau += (
                        sigma * ds / steps * sample(rho, p + light * ((i + 0.5) * ds / steps), span, height)
                    )
                result[z, y, x] = tau
    return result


@njit
def phase_probability(row, norm, mu):
    centered = np.arccos(min(1.0, max(-1.0, mu))) * (2 / np.pi) - 1
    symmetric = 0.5 + 0.5 * centered / (1.98 - 0.98 * abs(centered))
    coordinate = symmetric / (0.84 + 0.16 * symmetric) * (len(row) - 1)
    index = min(int(coordinate), len(row) - 2)
    return np.exp2(row[index] + (coordinate - index) * (row[index + 1] - row[index])) / norm


@njit
def scatter(w, inverse):
    sample = np.random.random() * (len(inverse) - 1)
    index = int(sample)
    mu = inverse[index] + (sample - index) * (inverse[index + 1] - inverse[index])
    phi = 2 * np.pi * np.random.random()
    t = np.array([w[2], 0.0, -w[0]]) if abs(w[1]) < 0.99 else np.array([0.0, -w[2], w[1]])
    t /= np.sqrt(t @ t)
    return mu * w + np.sqrt(max(0.0, 1 - mu * mu)) * (np.cos(phi) * t + np.sin(phi) * np.cross(w, t))


@njit
def short_depth(rho, p, span, height, sigma, light, steps):
    length = min((height - p[1]) / light[1], height / max(light[1], 0.5))
    step = length / (steps * steps)
    depth = 0.0
    for tap in range(steps):
        center = tap + 0.5
        density = sample(rho, p + light * (center * center * step), span, height)
        depth += 2.0 * center * step * density * sigma
    return min(depth, 24.0)


@njit(parallel=True)
def solve(fields, span, height, sigma, tau, vertical, light, points, views, row, norm, g, inverse, steps, spp, seed):
    rho = fields[0]
    majorant = sigma * np.max(rho)
    solar_features = np.empty((len(points), 16), np.float32)
    sky_features = np.empty((len(points), 10), np.float32)
    solar = np.zeros(len(points), np.float32)
    sky = np.zeros(len(points), np.float32)
    for i in prange(len(points)):
        np.random.seed(seed + i)
        p, view = points[i], views[i]
        for j in range(4):
            density = sample(fields[j], p, span, height)
            solar_features[i, j] = density
            sky_features[i, j] = density
        depth = short_depth(rho, p, span, height, sigma, light, steps)
        mu = max(-1.0, min(1.0, view @ light))
        solar_features[i, 4:10] = np.array(
            [np.exp(-depth), depth / (1 + depth), p[1] / height, mu, phase_probability(row, norm, mu), view[1]]
        )
        for j, distance in enumerate((150.0, 500.0, 1200.0, -300.0)):
            solar_features[i, 10 + j] = sample(rho, p + light * distance, span, height)
        solar_features[i, 14] = sample(rho, p + np.array([0.0, 500.0, 0.0]), span, height)
        solar_features[i, 15] = g
        up_depth = sample(vertical, p, span, height)
        sky_features[i, 4:10] = np.array([
            up_depth / (1 + up_depth), p[1] / height, view[1],
            sample(rho, p + np.array([0.0, 300.0, 0.0]), span, height),
            sample(rho, p + np.array([0.0, 800.0, 0.0]), span, height), g,
        ])
        sun_total = 0.0
        sky_total = 0.0
        for path in range(spp):
            q, w, weight = p, view, 1.0
            order = 0
            while majorant > 0:
                w = scatter(w, inverse)
                escape = (height - q[1]) / w[1] if w[1] > 0 else -q[1] / min(w[1], -1e-15)
                distance = 0.0
                while distance < escape:
                    distance += -np.log1p(-np.random.random()) / majorant
                    if distance < escape and np.random.random() * majorant < sigma * sample(
                        rho, q + w * distance, span, height
                    ):
                        break
                if distance >= escape:
                    # The analytic boundary term accounts for order-one sky light.
                    if order > 0 and w[1] > 0:
                        sky_total += weight
                    break
                q = q + w * distance
                mu = w @ light
                sun_total += weight * phase_probability(row, norm, mu) * np.exp(-sample(tau, q, span, height))
                order += 1
                if order >= 16:
                    if np.random.random() >= 0.9:
                        break
                    weight /= 0.9
        solar[i] = sun_total / spp
        sky[i] = sky_total / spp
    return solar_features, solar, sky_features, sky

def generate(directory, out, smoke=False):
    out.mkdir(parents=True, exist_ok=True)
    phase, norm, moment, inverse = transport_tables()
    for source in sorted(directory.glob("region-*.npz")):
        region, layer = map(int, source.stem.split("-")[1:])
        heldout = region == 5
        rng = np.random.default_rng(6011 + region * 2 + layer)
        with np.load(source) as data:
            fields = data["fields"]
            span, height, sigma = map(float, (data["span"], data["height"], data["sigma"]))
        rho = fields[0]
        occupied = np.argwhere(rho > 0.01)
        if not len(occupied):
            continue
        # Revelation shades both low-cloud shapes with the liquid cumulus row.
        material = 0 if layer == 0 else 2
        vertical = sun_depth(rho, span, height, sigma, np.array([0.0, 1.0, 0.0]))
        rows, targets, sky_rows, sky_targets = [], [], [], []
        for e, elevation in enumerate((35,) if smoke else (6, 16, 35, 57, 83)):
            az, el = rng.uniform(0, 2 * np.pi), np.radians(elevation + rng.uniform(-2, 2))
            light = np.array([np.sin(az) * np.cos(el), np.sin(el), np.cos(az) * np.cos(el)])
            tau = sun_depth(rho, span, height, sigma, light)
            count = (256 if heldout else 1024) if layer == 0 else (128 if heldout else 384)
            if smoke:
                count = 64
            points = rng.uniform(size=(count, 3)) * [span, height, span]
            n = 3 * count // 4
            selected = occupied[rng.integers(len(occupied), size=n), :][:, [2, 1, 0]]
            points[:n] = (selected + rng.uniform(-0.5, 0.5, size=(n, 3))) * [
                span / rho.shape[2],
                height / (rho.shape[1] - 1),
                span / rho.shape[0],
            ]
            points[:, 1] = np.clip(points[:, 1], 0, height)
            views = rng.normal(size=(count, 3))
            views /= np.linalg.norm(views, axis=1, keepdims=True)
            # Sample the solar lobe as well as uniform view directions. This
            # gives the fit data near the forward peak and not just its tails.
            n = count // 4
            tangent = np.array([light[2], 0.0, -light[0]])
            tangent /= np.linalg.norm(tangent)
            u = rng.uniform(size=n)
            phi = rng.uniform(0, 2 * np.pi, size=n)
            cosines = np.interp(u, np.linspace(0, 1, len(inverse[material])), inverse[material])
            views[:n] = cosines[:, None] * light + np.sqrt(1 - cosines**2)[:, None] * (
                np.cos(phi)[:, None] * tangent + np.sin(phi)[:, None] * np.cross(light, tangent)
            )
            x, y, sx, sy = solve(
                fields, span, height, sigma, tau, vertical, light, points, views,
                phase[material], norm[material], moment[material], inverse[material], 6 if layer == 0 else 3,
                4 if smoke else 512 if heldout else 96,
                900000 * region + 70000 * layer + 3000 * e,
            )
            sky_rows.append(sx)
            sky_targets.append(sy)
            rows.append(x)
            targets.append(y)
        np.savez(out / source.name, features=np.concatenate(rows), source=np.concatenate(targets),
                 sky_features=np.concatenate(sky_rows), sky_source=np.concatenate(sky_targets))
        print(source.stem, sum(map(len, rows)), "solar source samples", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("fields", type=Path)
    parser.add_argument("labels", type=Path)
    parser.add_argument("--smoke", action="store_true")
    args = parser.parse_args()
    generate(args.fields, args.labels, args.smoke)
