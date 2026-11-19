# SPDX-License-Identifier: Apache-2.0
"""Solar source after the first scattering in a periodic production-density slab."""

import argparse
from pathlib import Path

import numpy as np
from numba import njit, prange


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
def hg(mu, g):
    return (1 - g * g) / (4 * np.pi * (1 + g * g - 2 * g * mu) ** 1.5)


@njit
def scatter(w, g):
    u = np.random.random()
    mu = 2 * u - 1 if abs(g) < 1e-4 else (1 + g * g - ((1 - g * g) / (1 - g + 2 * g * u)) ** 2) / (2 * g)
    phi = 2 * np.pi * np.random.random()
    t = np.array([w[2], 0.0, -w[0]]) if abs(w[1]) < 0.99 else np.array([0.0, -w[2], w[1]])
    t /= np.sqrt(t @ t)
    return mu * w + np.sqrt(max(0.0, 1 - mu * mu)) * (np.cos(phi) * t + np.sin(phi) * np.cross(w, t))


@njit(parallel=True)
def solve(fields, span, height, sigma, tau, light, points, views, gs, spp, seed):
    rho = fields[0]
    majorant = sigma * np.max(rho)
    features = np.empty((len(points), 15), np.float32)
    labels = np.zeros(len(points), np.float32)
    for i in prange(len(points)):
        np.random.seed(seed + i)
        p, view, g = points[i], views[i], gs[i]
        for j in range(4):
            features[i, j] = sample(fields[j], p, span, height)
        depth = sample(tau, p, span, height)
        mu = max(-1.0, min(1.0, view @ light))
        features[i, 4:10] = np.array(
            [np.exp(-depth), depth / (1 + depth), p[1] / height, mu, hg(mu, g), view[1]]
        )
        for j, distance in enumerate((150.0, 500.0, 1200.0, -300.0)):
            features[i, 10 + j] = sample(rho, p + light * distance, span, height)
        features[i, 14] = sample(rho, p + np.array([0.0, 500.0, 0.0]), span, height)
        total = 0.0
        for path in range(spp):
            q, w, weight = p, view, 1.0
            order = 0
            while majorant > 0:
                w = scatter(w, g if np.random.random() < 0.5 else -0.25)
                escape = (height - q[1]) / w[1] if w[1] > 0 else -q[1] / min(w[1], -1e-15)
                distance = 0.0
                while distance < escape:
                    distance += -np.log1p(-np.random.random()) / majorant
                    if distance < escape and np.random.random() * majorant < sigma * sample(
                        rho, q + w * distance, span, height
                    ):
                        break
                if distance >= escape:
                    break
                q = q + w * distance
                mu = max(-1.0, min(1.0, w @ light))
                total += weight * 0.5 * (hg(mu, g) + hg(mu, -0.25)) * np.exp(-sample(tau, q, span, height))
                order += 1
                if order >= 16:
                    if np.random.random() >= 0.9:
                        break
                    weight /= 0.9
        labels[i] = total / spp
    return features, labels


def generate(directory, out, smoke=False):
    out.mkdir(parents=True, exist_ok=True)
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
        rows, targets = [], []
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
            gs = np.full(count, 0.65)
            gs[::4] = rng.uniform(0.0, 0.92, size=len(gs[::4]))
            x, y = solve(
                fields,
                span,
                height,
                sigma,
                tau,
                light,
                points,
                views,
                gs,
                4 if smoke else 512 if heldout else 96,
                900000 * region + 70000 * layer + 3000 * e,
            )
            rows.append(x)
            targets.append(y)
        np.savez(out / source.name, features=np.concatenate(rows), source=np.concatenate(targets))
        print(source.stem, sum(map(len, rows)), "solar source samples", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("fields", type=Path)
    parser.add_argument("labels", type=Path)
    parser.add_argument("--smoke", action="store_true")
    args = parser.parse_args()
    generate(args.fields, args.labels, args.smoke)
