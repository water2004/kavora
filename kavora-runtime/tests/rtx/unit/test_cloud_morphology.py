# SPDX-License-Identifier: Apache-2.0
"""Compare Vulkan density to an independent evaluation of Revelation's field."""

import itertools

import numpy as np
import slangpy as spy

from test_cloud_transport import CLOUD_ASSETS, OPTIONS, Probe, TEXTURE_FILES, cloud_parameters, cloud_textures


def interpolate(texture, uv, repeat=True):
    """Sample texel-centred, normalized coordinates in XYZ order."""
    dimensions = np.array(texture.shape[:-1][::-1])
    texel = uv * dimensions - 0.5
    lower = np.floor(texel).astype(np.int64)
    fraction = texel - lower
    result = np.zeros((len(uv), texture.shape[-1]))
    for offsets in itertools.product((0, 1), repeat=uv.shape[-1]):
        indices = lower + offsets
        indices = indices % dimensions if repeat else np.clip(indices, 0, dimensions - 1)
        weight = np.prod(np.where(offsets, fraction, 1 - fraction), axis=-1)
        result += weight[:, None] * texture[tuple(indices.T[::-1])]
    return result / 255.0


def clamp01(x):
    return np.clip(x, 0.0, 1.0)


def reference_low(maps, body, curl, points, wetness):
    low, regional_noise, gradient, *_ = maps
    position = points[:, :3]
    xz = position[:, [0, 2]]
    weather = interpolate(low, xz / 64000)
    amount, kind = weather[:, 0], weather[:, 1]
    edge = np.array([0.45, 0.75]) * (1 - wetness**2) + np.array([0.1, 0.3]) * wetness**2
    coverage = clamp01((amount - edge[0]) / (edge[1] - edge[0]))
    sky = interpolate(regional_noise, xz / 512000 + 0.75)[:, 2]
    local = coverage * clamp01((sky - edge[0]) / (0.8 * edge[1] - edge[0]))
    height = clamp01(points[:, 3] / np.maximum(amount, 1e-9))
    profile = interpolate(gradient, np.column_stack([kind, height]), repeat=False)[:, 0]
    signal = local * profile
    wind = np.array([np.cos(np.deg2rad(20)), 0.5, np.sin(np.deg2rad(20))])
    noise = position - (600 * height[:, None]) * wind
    noise[:, 1] += noise[:, [0, 2]] @ [0.2, 0.3]
    noise /= 2000
    turbulence = interpolate(curl, 2 * noise)[:, :3]
    noise += 0.3 * (profile * (1 - local))[:, None] * turbulence
    density = signal - 0.75 * (1 - interpolate(body, noise)[:, 0] * profile)
    fade = clamp01((height - 0.1) / 0.4)
    fade = fade**2 * (3 - 2 * fade)
    result = (density * (1 - signal) + np.sqrt(np.maximum(density, 0)) * signal) * (0.1 + 0.9 * fade)
    return np.where((coverage >= 0.1) & (signal >= 0.05) & (density >= 0.001), result, 0)


def reference_high(maps, points, map_origin, lookup_origin):
    high, curl, cirrus = maps[3:]
    local = points[:, [0, 2]]
    position = local + map_origin
    cloud = interpolate(high, position / 786000)
    types = (clamp01(cloud[:, :3] * 1.5 - 0.5) * [1, 1, 2])**3
    angle = 2.39996322973
    rotation = np.array([[np.cos(angle), np.sin(angle)], [-np.sin(angle), np.cos(angle)]])
    uv = ((local * 0.001 + interpolate(curl, position / 24000)[:, :2]) @ rotation) * 0.05
    uv += lookup_origin - 0.1 * cloud[:, 3, None]
    fine = interpolate(cirrus, uv * 2)[:, :3]**2
    weights = fine * (1 - cloud[:, :3]) + interpolate(cirrus, uv)[:, :3] * cloud[:, :3]
    value = clamp01(np.sum(weights * types, axis=-1) * cloud[:, 3]**2 * 8)
    return np.where((cloud[:, 3] >= 0.001) & (np.sum(types, axis=-1) >= 1e-6), value, 0)


def check_source_morphology(probe, textures):
    raw = np.fromfile(CLOUD_ASSETS / TEXTURE_FILES["Weather"], dtype=np.uint8)
    maps = []
    offset = 0
    for side in (1024, 256, 32, 512, 512, 1024):
        next_offset = offset + side * side * 4
        maps.append(raw[offset:next_offset].reshape(side, side, 4))
        offset = next_offset
    body = np.fromfile(CLOUD_ASSETS / TEXTURE_FILES["Base"], dtype=np.uint8).reshape(128, 128, 128, 1)
    curl = np.fromfile(CLOUD_ASSETS / TEXTURE_FILES["Detail"], dtype=np.uint8).reshape(64, 64, 64, 4)
    rng = np.random.default_rng(321)
    points = rng.uniform(-390000, 390000, (24000, 4)).astype(np.float32)
    points[:, 1] = rng.uniform(900, 4700, len(points))
    points[:, 3] = rng.uniform(0, 1, len(points))
    for wetness, origin, uv in ((0.0, [0, 0], [0, 0]),
                                  (0.5, [19200, -7200], [0.23, -0.43]),
                                  (1.0, [-25486, 8261], [0.11, 0.37])):
        args = cloud_parameters(coverage=0.5, highCoverage=0.5, thickness=2000, wetness=wetness,
                                 highMapOrigin=origin, highLookupOrigin=uv)
        actual = probe.run("sourceDensities", points, clouds=args, textures=textures)[:, :2]
        expected = np.column_stack([reference_low(maps, body, curl, points, wetness),
                                    reference_high(maps, points, origin, uv)])
        # Hardware bilinear interpolation of UNORM texels rounds at subtexel precision.
        error = np.abs(actual - expected)
        assert np.count_nonzero(expected[:, 0] > 0.03) > 50
        assert np.count_nonzero(expected[:, 1] > 0.03) > 50
        p99 = np.quantile(error, 0.99, axis=0)
        assert np.all(p99 < 0.025), p99
        assert np.max(np.mean(error, axis=0)) < 0.004, np.mean(error, axis=0)
        print(f"PASS Revelation density, wetness={wetness:.1f}: p99 low/high absolute errors {p99}")


if __name__ == "__main__":
    device = spy.Device(type=spy.DeviceType.vulkan, compiler_options=OPTIONS)
    check_source_morphology(Probe(device), cloud_textures(device))
