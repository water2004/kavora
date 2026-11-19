# SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
# SPDX-License-Identifier: MIT
"""Read back production cloud diffusion and test slab energy independently of Minecraft.

Install NumPy and SlangPy. Both CPU and Vulkan devices execute the production
math module. The energy check includes the actual dual-HG single-scattering
distribution and continuous outgoing directions, not only the two ordinates.
It bounds this parameter grid; it is not a proof for heterogeneous 3D clouds.
"""
import argparse
from pathlib import Path

import numpy as np
from numpy.polynomial.legendre import leggauss
import slangpy as spy


HERE = Path(__file__).resolve().parent
SHADERS = HERE.parents[2] / "src/dxvk/shaders"


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


def beam_reference(above, below, cosine):
    """Integrate the positive Robin Green kernel against the incident beam."""
    if cosine == 0 or above + below == 0:
        return 0.0
    d = 1.0
    total = above + below
    nodes, weights = leggauss(20)
    cutoff = min(total / cosine, 80.0)
    split = min(above / cosine, cutoff)
    edges = np.unique(np.clip([0, 1, 2, 4, 8, 16, 32, 80, split, cutoff], 0, cutoff))
    integral = 0.0
    for lower, upper in zip(edges[:-1], edges[1:]):
        v = lower + (nodes + 1) * 0.5 * (upper - lower)
        source_depth = cosine * v
        green = np.where(source_depth < above,
                         (below + d) * (source_depth + d),
                         (above + d) * (total - source_depth + d))
        integral += np.sum(weights * green * np.exp(-v)) * 0.5 * (upper - lower)
    return cosine * integral / (4 * np.pi * d * d * (total + 2 * d))


def check_beam(probe):
    samples = np.array([(total * fraction, total * (1 - fraction), cosine, 0)
                        for total in (0, 1e-8, 1e-6, 0.001, 0.1, 1, 10, 100, 10000)
                        for fraction in (0, 0.001, 0.1, 0.5, 0.9, 0.999, 1)
                        for cosine in (0, 1e-8, 1e-5, 0.001, 0.01, 0.1, 0.5, 1)], dtype=np.float32)
    actual = probe.run("beamRadiance", samples)[:, 0]
    expected = np.array([beam_reference(float(t), float(b), float(m)) for t, b, m, _ in samples])
    assert np.isfinite(actual).all() and np.all(actual >= 0)
    np.testing.assert_allclose(actual, expected, rtol=2e-5, atol=3e-9)
    zero = (samples[:, :2].sum(axis=1) == 0) | (samples[:, 2] == 0)
    np.testing.assert_array_equal(actual[zero], 0)

    grazing = [(t, b, cosine, 0) for t, b in ((0, 0), (0.1, 10), (10, 0.1), (1, 1))
               for cosine in (-1e-7, 0, 1e-7)]
    near_horizon = probe.run("beamRadiance", grazing)[:, 0].reshape(-1, 3)
    assert np.max(np.abs(np.diff(near_horizon, axis=1))) < 1e-7
    thin = probe.run("beamRadiance", [(t / 2, t / 2, 0.5, 0) for t in (1e-6, 1e-4)])[:, 0]
    np.testing.assert_allclose(thin / [1e-6, 1e-4], thin[0] / 1e-6, rtol=3e-4)
    print(f"PASS beam Green integral: {len(samples)} positive/finite samples, zero thickness and grazing continuity")


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


def single_escape(outgoing_cosine, thickness, incident_cosine):
    """Analytically integrate one beam collision and escape along each direction."""
    result = np.empty_like(outgoing_cosine)
    downward = outgoing_cosine > 0
    mu = outgoing_cosine[downward]
    equal = np.abs(mu - incident_cosine) < 1e-7
    transmitted = np.empty_like(mu)
    transmitted[equal] = thickness / incident_cosine * np.exp(-thickness / incident_cosine)
    transmitted[~equal] = mu[~equal] / (mu[~equal] - incident_cosine) * (
        np.exp(-thickness / mu[~equal]) - np.exp(-thickness / incident_cosine))
    result[downward] = transmitted
    mu = -outgoing_cosine[~downward]
    result[~downward] = mu / (mu + incident_cosine) * -np.expm1(
        -thickness * (1 / incident_cosine + 1 / np.maximum(mu, 1e-100)))
    return result


def phase_escape(thickness, incident_cosine, g, nodes, weights, azimuth):
    # HG inverse-CDF quadrature resolves the g=0.95 forward peak without a huge uniform angular grid.
    cosine = ((1 + g * g - ((1 - g * g) / (1 - g + 2 * g * nodes)) ** 2) / (2 * g)
              if abs(g) > 1e-6 else 2 * nodes - 1)
    outgoing = (incident_cosine * cosine[:, None]
                + np.sqrt(1 - incident_cosine ** 2) * np.sqrt(np.maximum(0, 1 - cosine ** 2))[:, None]
                * np.cos(azimuth)[None, :])
    return np.sum(single_escape(outgoing, thickness, incident_cosine) * weights[:, None]) / len(azimuth)


def check_slab_energy(probe):
    angular, angular_weights = leggauss(128)
    angular = (angular + 1) / 2
    angular_weights /= 2
    azimuth = (np.arange(256) + 0.5) * 2 * np.pi / 256
    depth_nodes, depth_weights = leggauss(24)
    worst = (0, None)
    cases = 0
    for incident_cosine in (0.0001, 0.001, 0.01, 0.035, 0.1, 0.3, 0.5, 0.7, 0.9, 1):
        for thickness in (0.0001, 0.001, 0.01, 0.03, 0.1, 0.3, 1, 3, 10, 30, 100):
            anchors = np.array([0, 1e-5, 1e-4, 0.001, 0.01, 0.1, 0.5, 1, 3, 10, 30, 100])
            edges = np.unique(np.clip(np.r_[anchors, thickness - anchors, thickness], 0, thickness))
            depths = np.concatenate([a + (depth_nodes + 1) * 0.5 * (b - a)
                                     for a, b in zip(edges[:-1], edges[1:])])
            weights = np.concatenate([depth_weights * 0.5 * (b - a)
                                      for a, b in zip(edges[:-1], edges[1:])])
            samples = np.column_stack([depths, thickness - depths,
                                       np.full(len(depths), incident_cosine), np.zeros(len(depths))])
            diffuse = probe.run("beamRadiance", samples)[:, 0].astype(np.float64)
            escape = np.sum((np.exp(-depths[:, None] / angular)
                             + np.exp(-(thickness - depths[:, None]) / angular)) * angular_weights, axis=1)
            multiple_power = 2 * np.pi * np.sum(diffuse * escape * weights) / incident_cosine
            backward = phase_escape(thickness, incident_cosine, -0.25, angular, angular_weights, azimuth)
            for g in (0, 0.3, 0.5, 0.65, 0.75, 0.85, 0.9, 0.95):
                forward = phase_escape(thickness, incident_cosine, g, angular, angular_weights, azimuth)
                outgoing = np.exp(-thickness / incident_cosine) + 0.85 * forward + 0.15 * backward + multiple_power
                assert np.isfinite(outgoing) and 0 <= outgoing <= 1 + 2e-6, (incident_cosine, thickness, g, outgoing)
                if outgoing > worst[0]:
                    worst = (outgoing, (incident_cosine, thickness, g))
                cases += 1
    print(f"PASS slab energy: {cases} dual-HG cases at maximum multiple strength, "
          f"maximum outgoing/incoming {worst[0]:.9f} at (mu, tau, g)={worst[1]}")


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
