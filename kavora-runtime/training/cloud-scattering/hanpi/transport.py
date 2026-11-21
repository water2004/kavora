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
def short_depth(rho, base_density, p, span, height, sigma, light, steps):
    vertical = height - p[1] if light[1] >= 0 else p[1]
    length = min(vertical / max(abs(light[1]), 1e-10), height / max(abs(light[1]), 0.5))
    step = length / (steps * steps)
    depth = 0.0
    for tap in range(steps):
        center = tap + 0.5
        density = sample(rho if tap < 3 else base_density,
                         p + light * (center * center * step), span, height)
        depth += 2.0 * center * step * density * sigma
    return min(depth, 24.0)


@njit
def write_solar_features(out, rho, coarse_density, p, span, height, sigma, column, light, view, row, norm, g, steps):
    sun = short_depth(rho, coarse_density, p, span, height, sigma, light, steps)
    inward = short_depth(rho, coarse_density, p, span, height, sigma, view, 3)
    mu = max(-1.0, min(1.0, view @ light))
    out[0] = np.log1p(sun)
    out[1] = np.log1p(inward)
    out[2] = np.log1p(min(128., column))
    out[3] = light[1]
    out[4] = mu
    out[5] = phase_probability(row, norm, mu)
    out[6] = view[1]
    out[7] = g


@njit
def beam_visibility(rho, p, span, height, sigma, light, majorant):
    # Ratio tracking directly estimates transmission through the training medium.
    distance = 0.0
    exit_distance = (height - p[1]) / light[1]
    transmission = 1.0
    while True:
        distance -= np.log1p(-np.random.random()) / majorant
        if distance >= exit_distance:
            return transmission
        transmission *= max(0.0, 1 - sigma * sample(rho, p + distance * light, span, height) / majorant)
        if transmission < .1:
            if np.random.random() >= transmission:
                return 0.0
            transmission = 1.0


@njit(parallel=True)
def solve(fields, span, height, sigma, vertical, column_tau, light, points, views, row, norm, g, inverse, steps, spp, seed,
          base_density=None):
    rho = fields[0]
    coarse_density = rho if base_density is None else base_density
    majorant = sigma * np.max(rho)
    solar_features = np.empty((len(points), 8), np.float32)
    sky_features = np.empty((len(points), 10), np.float32)
    solar = np.zeros(len(points), np.float32)
    sky = np.zeros(len(points), np.float32)
    for i in prange(len(points)):
        np.random.seed(seed + i)
        p, view = points[i], views[i]
        for j in range(4):
            density = sample(fields[j], p, span, height)
            sky_features[i, j] = density
        write_solar_features(solar_features[i], rho, coarse_density, p, span, height, sigma, column_tau[i],
                             light, view, row, norm, g, steps)
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
                sun_total += weight * phase_probability(row, norm, mu) * beam_visibility(
                    rho, q, span, height, sigma, light, majorant)
                order += 1
                if order >= 16:
                    if np.random.random() >= 0.9:
                        break
                    weight /= 0.9
        solar[i] = sun_total / spp
        sky[i] = sky_total / spp
    return solar_features, solar, sky_features, sky


@njit(parallel=True)
def sample_visible_points(rho, points, views, span, height, sigma, seed):
    majorant = sigma * np.max(rho)
    for i in prange(len(points)):
        np.random.seed(seed + i)
        w = views[i]
        p = points[i].copy()
        # Start at the viewing boundary. At grazing angles the periodic slab
        # supplies at most one horizontal period of foreground.
        limit = min(span, (height - p[1]) / -w[1] if w[1] < 0 else p[1] / max(w[1], 1e-12))
        origin = p - w * limit
        distance = 0.0
        while distance < limit:
            distance -= np.log1p(-np.random.random()) / majorant
            if distance < limit:
                candidate = origin + w * distance
                if np.random.random() * majorant < sigma * sample(rho, candidate, span, height):
                    points[i] = candidate
                    break


def column_depths(rho, span, height, sigma, points, rng, samples):
    """Bilinear column footprint at a random atlas origin and supported world scale."""
    from scipy.ndimage import map_coordinates
    side = rho.shape[0]
    pitch = rng.choice((375., 937.5, 1500.))
    # Sample the same cell centers and vertical quadrature used by the atlas.
    plane = np.arange(side)
    z, x = np.meshgrid(plane, plane, indexing="ij")
    column = np.zeros((side, side))
    for i in range(samples):
        y = (i + .5) * (rho.shape[1] - 1) / samples
        column += map_coordinates(rho, [z, np.full_like(z, y, dtype=float), x], order=1, mode="nearest")
    column *= height * sigma / samples
    offset = rng.uniform(0, pitch, size=2)
    uv = (points[:, [2, 0]] - offset) / pitch
    base = np.floor(uv)
    frac = uv - base
    result = np.zeros(len(points))
    for a in (0, 1):
        for b in (0, 1):
            coords = ((base + [a, b]) * pitch + offset).T * side / span
            weight = (frac[:, 0] if a else 1-frac[:, 0]) * (frac[:, 1] if b else 1-frac[:, 1])
            result += weight * map_coordinates(column, coords, order=1, mode="grid-wrap")
    return result


def generate(directory, out, smoke=False):
    out.mkdir(parents=True, exist_ok=True)
    phase, norm, moment, inverse = transport_tables()
    for source in sorted(directory.glob("region-*.npz")):
        region, layer = map(int, source.stem.split("-")[1:])
        heldout = region == 5
        rng = np.random.default_rng(6011 + region * 2 + layer)
        with np.load(source) as data:
            fields = data["fields"]
            base_density = data["baseDensity"]
            span, height, sigma = map(float, (data["span"], data["height"], data["sigma"]))
        rho = fields[0]
        occupied = np.argwhere(rho > 0)
        if not len(occupied):
            raise ValueError(f"{source.stem} contains no scattering medium")
        mass = rho[tuple(occupied.T)].astype(np.float64)
        mass /= mass.sum()
        # Revelation shades both low-cloud shapes with the liquid cumulus row.
        material = 0 if layer == 0 else 2
        vertical = sun_depth(rho, span, height, sigma, np.array([0.0, 1.0, 0.0]))
        rows, targets, sky_rows, sky_targets = [], [], [], []
        for e, elevation in enumerate((35,) if smoke else (6, 16, 35, 57, 83)):
            az, el = rng.uniform(0, 2 * np.pi), np.radians(elevation + rng.uniform(-2, 2))
            light = np.array([np.sin(az) * np.cos(el), np.sin(el), np.cos(az) * np.cos(el)])
            count = (512 if heldout else 1536) if layer == 0 else (128 if heldout else 384)
            if smoke:
                count = 64
            points = rng.uniform(size=(count, 3)) * [span, height, span]
            n = 3 * count // 4
            selected = occupied[rng.choice(len(occupied), size=n, p=mass)][:, [2, 1, 0]]
            points[:n] = (selected + rng.uniform(-0.5, 0.5, size=(n, 3))) * [
                span / rho.shape[2],
                height / (rho.shape[1] - 1),
                span / rho.shape[0],
            ]
            points[:, 1] = np.clip(points[:, 1], 0, height)
            views = rng.normal(size=(count, 3))
            views /= np.linalg.norm(views, axis=1, keepdims=True)
            # Cover the narrow forward lobe and give the dim backward hemisphere
            # enough independent observations to fit its solar contribution.
            n = count // 4
            tangent = np.array([light[2], 0.0, -light[0]])
            tangent /= np.linalg.norm(tangent)
            u = rng.uniform(size=n)
            phi = rng.uniform(0, 2 * np.pi, size=n)
            cosines = np.interp(u, np.linspace(0, 1, len(inverse[material])), inverse[material])
            views[:n] = cosines[:, None] * light + np.sqrt(1 - cosines**2)[:, None] * (
                np.cos(phi)[:, None] * tangent + np.sin(phi)[:, None] * np.cross(light, tangent)
            )
            cosines = -1.0 + .25 * rng.uniform(size=n)**2
            phi = rng.uniform(0, 2 * np.pi, size=n)
            views[n:2*n] = cosines[:, None] * light + np.sqrt(1 - cosines**2)[:, None] * (
                np.cos(phi)[:, None] * tangent + np.sin(phi)[:, None] * np.cross(light, tangent)
            )
            sample_visible_points(rho, points[::2], views[::2], span, height, sigma,
                                  13000000 + region * 70000 + layer * 3000 + e * count)
            columns = column_depths(rho, span, height, sigma, points, rng, 16 if layer == 0 else 2)
            x, y, sx, sy = solve(
                fields, span, height, sigma, vertical, columns, light, points, views,
                phase[material], norm[material], moment[material], inverse[material], 6 if layer == 0 else 3,
                4 if smoke else 4096 if region in (4, 5) else 1536,
                900000 * region + 70000 * layer + 3000 * e,
                base_density,
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
