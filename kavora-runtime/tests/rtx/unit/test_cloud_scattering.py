# SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
# SPDX-License-Identifier: MIT
"""Read back production cloud diffusion and test slab energy independently of Minecraft.

Install NumPy and SlangPy. Both CPU and Vulkan devices execute the production
math module. The energy check includes the actual Revelation single-scattering
distribution and continuous outgoing directions, not only the two ordinates.
It bounds this parameter grid; it is not a proof for heterogeneous 3D clouds.
"""
import argparse
from pathlib import Path
import sys

import numpy as np
import slangpy as spy


HERE = Path(__file__).resolve().parent
SHADERS = HERE.parents[2] / "src/dxvk/shaders"
sys.path.insert(0, str(HERE.parents[2] / "training/cloud-scattering"))
from common.revelation_phase import transport_tables


class Probe:
    def __init__(self, device):
        self.device = device
        self.kernels = {}

    def run(self, entry, samples):
        data = np.asarray(samples, dtype=np.float32).reshape(-1, 4)
        if entry not in self.kernels:
            program = self.device.load_program("cloud_scattering_contracts.comp.slang", [entry])
            self.kernels[entry] = self.device.create_compute_kernel(program)
        output = self.device.create_buffer(element_count=len(data), struct_size=16,
                                           usage=spy.BufferUsage.unordered_access)
        bindings = {
            "Inputs": self.device.create_buffer(data=data, usage=spy.BufferUsage.shader_resource),
            "Results": output, "Count": len(data),
        }
        self.kernels[entry].dispatch(thread_count=[len(data), 1, 1], vars=bindings)
        return output.to_numpy().view(np.float32).reshape(-1, 4)


def check_hemisphere(probe):
    up = np.array([[1, 0, 0], [0, 1, 0], [0, 0, 1], [0, -1, 0], [1, 2, 3]], dtype=np.float32)
    up /= np.linalg.norm(up, axis=1)[:, None]
    samples = np.zeros((len(up), 8192, 4), dtype=np.float32)
    samples[:, :, :3] = up[:, None, :]
    result = probe.run("hemisphereQuadrature", samples).astype(np.float64).reshape(len(up), -1, 4)
    direction, weight = result[:, :, :3], result[:, :, 3]
    assert np.isfinite(result).all() and np.all(weight > 0)
    assert np.all(np.sum(direction * up[:, None, :], axis=-1) > 0)
    np.testing.assert_allclose(np.linalg.norm(direction, axis=-1), 1, atol=3e-7, rtol=0)
    np.testing.assert_allclose(weight.sum(axis=1), 0.5, atol=1e-8, rtol=0)
    np.testing.assert_allclose(np.sum(direction * weight[:, :, None], axis=1), 0.25 * up, atol=7e-5, rtol=0)
    moment = np.einsum("bni,bnj,bn->bij", direction, direction, weight)
    np.testing.assert_allclose(moment, np.broadcast_to(np.eye(3) / 6, moment.shape), atol=5e-5, rtol=0)
    # HG times 4*pi has unit spherical average; its polar hemisphere integrals
    # are analytic and resolve the narrow g=0.9 Mie lobe used by the sky.
    g = 0.9
    cosine = np.sum(direction * up[:, None, :], axis=-1)
    polar = (1 - g * g) / (2 * g) * (1 / (1 - g) - 1 / np.sqrt(1 + g * g))
    for sign, expected in ((1, polar), (-1, 1 - polar)):
        radiance = (1 - g * g) / (1 + g * g - 2 * g * sign * cosine) ** 1.5
        np.testing.assert_allclose(np.sum(radiance * weight, axis=1), expected, rtol=5e-5, atol=1e-8)
    print("PASS sky quadrature: 8192 production nodes in 5 frames, normalization, angular moments and HG polar integrals")


def check_boundaries(probe):
    samples = np.array([(above, below, g, 0)
                        for above in (0, 1e-8, 0.001, 0.1, 1, 10, 10000)
                        for below in (0, 1e-8, 0.001, 0.1, 1, 10, 10000)
                        for g in (-0.0375, 0.515, 0.77)], dtype=np.float32)
    result = probe.run("boundaryRadiance", samples)
    d = 1 / np.sqrt(3)
    depth = samples[:, :2].astype(np.float64) * (1 - samples[:, 2, None])
    fraction = (d + depth[:, 1]) / (2 * d + depth.sum(axis=1))
    expected = fraction[:, None] * [0.25, 1.5, 8.0]
    np.testing.assert_allclose(result[:, :3], expected, rtol=3e-6, atol=3e-7)
    np.testing.assert_allclose(result[:, 3], 1.56, rtol=0, atol=3e-7)
    assert np.all(result[:, :3] >= 0) and np.all(result[:, :3] <= [0.25, 1.5, 8.0])
    empty = result[(samples[:, 0] == 0) & (samples[:, 1] == 0), :3]
    np.testing.assert_allclose(empty, np.tile([0.125, 0.75, 4.0], (len(empty), 1)), atol=1e-7)
    print(f"PASS diffuse boundaries: {len(samples)} upper/lower columns, thin limit and white furnace")


def check_ground_boundary(probe):
    samples = np.array([(tau, g, albedo, beam)
                        for tau in (0, 1e-8, 0.01, 1, 10, 100, 10000)
                        for g in (-0.0375, 0.515, 0.77)
                        for albedo in (0, 0.18, 0.8, 1)
                        for beam in (0, 0.1, 1)], dtype=np.float32)
    actual = probe.run("groundBoundary", samples)[:, :3].astype(np.float64)
    depth, g, albedo, beam = samples.astype(np.float64).T
    transmission = (2 / np.sqrt(3)) / (2 / np.sqrt(3) + depth * (1 - g))
    expected = albedo[:, None] * (transmission[:, None] * [0.25, 1.5, 8.0] + beam[:, None])
    expected /= (1 - albedo * (1 - transmission))[:, None]
    np.testing.assert_allclose(actual, expected, atol=2e-5, rtol=4e-4)
    assert np.isfinite(actual).all() and np.all(actual >= 0)
    np.testing.assert_array_equal(actual[albedo == 0], 0)
    empty = depth == 0
    np.testing.assert_allclose(actual[empty], albedo[empty, None] * (
        [0.25, 1.5, 8.0] + beam[empty, None]), rtol=2e-7, atol=1e-7)
    white = (albedo == 1) & (beam == 0)
    np.testing.assert_allclose(actual[white], np.broadcast_to([0.25, 1.5, 8.0], actual[white].shape),
                               rtol=4e-4, atol=2e-5)
    print(f"PASS terrain boundary: {len(samples)} black/white terrain, empty/thick columns and reflected-beam feedback")


def check_beam(probe):
    samples = [(tau, g, cosine, 0) for g in (0, 0.77, 0.8525, 1)
               for cosine in (0, 1e-7, .2, .85, 1)
               for tau in (0, 1e-7, .1, 1, 10, 10000)]
    transfer = probe.run("groundBeamFlux", samples).reshape(4, 5, 6, 4)
    flux, beam = transfer[..., 0], transfer[..., 1]
    cosines = np.array([0, 1e-7, .2, .85, 1])
    # Only flux outside the delta beam belongs to the next deck's diffuse
    # sky. Check the energy partition, including the empty and forward limits.
    diffuse = flux - beam * cosines[None, :, None]
    assert np.all(diffuse >= -1e-6)
    np.testing.assert_allclose(diffuse[:, :, 0], 0, atol=1e-7)
    np.testing.assert_allclose(diffuse[-1], 0, atol=1e-7)
    assert diffuse[1, 3, 4] > 0.1
    np.testing.assert_allclose(flux[:, :, 0], np.broadcast_to(cosines, (4, 5)), atol=1e-7)
    np.testing.assert_allclose(flux[-1], np.broadcast_to(cosines[:, None], (5, 6)), atol=1e-7)
    assert np.isfinite(flux).all() and np.all(flux >= 0)
    assert np.all(np.diff(flux, axis=-1) <= 1e-6)
    print("PASS downward flux: empty and thick slabs, forward scattering and grazing incidence")


def check_column_support(probe):
    samples = [(thickness, cloud_type, top_scale, 0.58 if cloud_type < 0.5 else 0.9) for thickness in (50, 800, 1500)
               for cloud_type in (0, 0.49, 0.5, 1) for top_scale in (1, 1.1, 1.6, 2)]
    expected = []
    for thickness, cloud_type, top_scale, _ in samples:
        profile_limit = 0.58 if cloud_type < 0.5 else 0.9
        normalized_height = profile_limit / (1 - (top_scale - 1) * profile_limit)
        expected.append(thickness * min(normalized_height, 1))
    actual = probe.run("columnSupport", samples)[:, 0]
    np.testing.assert_allclose(actual, expected, rtol=2e-6)
    print(f"PASS local column support: {len(samples)} cloud types, top stretches and shell heights")


def slab_transmission(thickness, cosine, inverse, rng, paths=32768):
    """Follow incident photons until they escape through either slab boundary."""
    mu = np.full(paths, cosine)
    depth = np.zeros(paths)
    transmitted = 0
    quantiles = np.linspace(0, 1, len(inverse))
    while len(mu):
        depth += rng.exponential(size=len(mu)) * mu
        transmitted += np.count_nonzero(depth >= thickness)
        inside = (depth > 0) & (depth < thickness)
        depth, mu = depth[inside], mu[inside]
        scatter = np.interp(rng.random(len(mu)), quantiles, inverse)
        azimuth = rng.uniform(0, 2 * np.pi, len(mu))
        mu = mu * scatter + np.sqrt(np.maximum(0, (1 - mu**2) * (1 - scatter**2))) * np.cos(azimuth)
    return transmitted / paths


def check_slab_energy(probe):
    _, _, moments, inverse = transport_tables()
    rng = np.random.default_rng(20261121)
    samples, expected = [], []
    for layer in (0, 2):
        for cosine in (.25, .85, 1.0):
            for thickness in (.2, 2, 8):
                samples.append((thickness, moments[layer], cosine, 0))
                expected.append(slab_transmission(thickness, cosine, inverse[layer], rng))
    predicted = probe.run("groundBeamFlux", samples)[:, 0] / np.asarray(samples)[:, 2]
    error = np.abs(predicted - expected)
    print(f"Ground flux against Revelation photon transport: mean error {error.mean():.4f}, max {error.max():.4f}")
    # The two-stream boundary approximates the full angular transport. The
    # reference's worst-case binomial standard error is below 0.003.
    assert np.max(error) < .08 and np.mean(error) < .03


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", choices=("cpu", "vulkan"), default="cpu")
    args = parser.parse_args()
    device = spy.Device(type=getattr(spy.DeviceType, args.device), compiler_options={
        "include_paths": [str(HERE), str(SHADERS)], "defines": {"__SLANG__": "1"},
    })
    probe = Probe(device)
    check_hemisphere(probe)
    check_boundaries(probe)
    check_ground_boundary(probe)
    check_beam(probe)
    check_column_support(probe)
    check_slab_energy(probe)


if __name__ == "__main__":
    main()
