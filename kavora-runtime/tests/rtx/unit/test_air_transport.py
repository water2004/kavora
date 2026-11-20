# SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
# SPDX-License-Identifier: MIT
"""Read back production air geometry and radiative transfer on a Vulkan device.

Requires NumPy and SlangPy. Software Vulkan is sufficient. These contracts
check numerical transport and prefix stability, not sky appearance or timing.
"""
from pathlib import Path

import numpy as np
import slangpy as spy

HERE = Path(__file__).resolve().parent
SHADERS = HERE.parents[2] / "src/dxvk/shaders"


def parameters(**changes):
    result = {
        "up": [0, 1, 0], "worldToKilometers": .001,
        "groundReference": [2000, 63, -3000], "innerRadius": 6348.,
        "lowerDepth": 12., "atmosphereHeight": 112.,
        "rayleighExtinction": [.005, .01, .02], "rayleighHeight": 8.,
        "mieHeight": 1.2, "mieExtinction": .005, "mieAlbedo": .9,
        "mieAnisotropy": .7, "enable": 1,
        "transmittanceTextureIndex": 0, "scatteringTextureIndex": 0,
        "transmittanceExtent": [2, 2], "scatteringSize": 2,
        "lights": [{"directionToLight": [0, 1, 0], "angularRadius": .004625123,
                    "irradiance": [1, 2, 3]},
                   {"directionToLight": [0, -1, 0], "angularRadius": .004625123,
                    "irradiance": [0, 0, 0]}],
    }
    result.update(changes)
    return result


def shell_reference(args, origin, direction):
    up = np.asarray(args["up"], dtype=np.float64)
    scale = float(np.float32(args["worldToKilometers"]))
    center = np.asarray(args["groundReference"], np.float32).astype(np.float64) - (
        args["innerRadius"] + args["lowerDepth"]) / scale * up
    point = np.asarray(origin, dtype=np.float64) - center
    ray = np.asarray(direction, dtype=np.float64)
    ray /= np.linalg.norm(ray)

    def roots(radius):
        b = np.dot(point, ray)
        perpendicular = point - b * ray
        discriminant = radius ** 2 - np.dot(perpendicular, perpendicular)
        if discriminant < 0:
            return None
        return np.array([-b - np.sqrt(discriminant), -b + np.sqrt(discriminant)])

    top = roots((args["innerRadius"] + args["atmosphereHeight"]) / scale)
    if top is None or top[1] <= 0:
        return [0, 0]
    begin, end = max(0, top[0]), top[1]
    lower = roots(args["innerRadius"] / scale)
    if lower is not None and lower[0] >= begin:
        end = min(end, lower[0])
    return [begin, end] if end > begin else [0, 0]


class Probe:
    def __init__(self):
        self.device = spy.Device(type=spy.DeviceType.vulkan, compiler_options={
            "include_paths": [str(HERE), str(SHADERS)], "defines": {"__SLANG__": "1"}})
        self.kernels = {}
        sampler = self.device.create_sampler(address_u=spy.TextureAddressingMode.clamp_to_edge,
                                            address_v=spy.TextureAddressingMode.clamp_to_edge)
        self.keep = [sampler]

        def texture(data, dim):
            array = np.asarray(data, np.float32)
            options = {"type": spy.TextureType.texture_3d if dim == 3 else spy.TextureType.texture_2d,
                       "format": spy.Format.rgba32_float if array.shape[-1] == 4 else spy.Format.rg32_float,
                       "width": array.shape[-2], "height": array.shape[-3],
                       "usage": spy.TextureUsage.shader_resource}
            if dim == 3:
                options["depth"] = array.shape[0]
            result = self.device.create_texture(**options, data=array)
            view = result.create_view(sampler=sampler)
            self.keep.extend([result, view])
            return view

        self.texture2d = lambda data: texture(data, 2)

        from test_cloud_transport import weather_fixture
        weather = weather_fixture(self.device, np.zeros((3, 1, 1, 4))).create_view(sampler=sampler)
        self.keep.append(weather)
        trans = texture(np.ones((2, 2, 4)), 2)
        scatter = texture(np.zeros((2, 2, 4)), 2)
        volume = texture(np.array([[[[0., 0., 0., 1.]]]]), 3)
        chroma = texture(np.zeros((1, 1, 1, 2)), 3)
        self.bindings = {"Transmission": trans.descriptor_handle_ro,
                         "Scattering": scatter.descriptor_handle_ro,
                         "TableSampler": sampler.descriptor_handle,
                         "Weather": weather.descriptor_handle_combined,
                         "Volume": volume.descriptor_handle_combined,
                         "Chroma": chroma.descriptor_handle_combined}

    def bake_transmission(self, args, width=512, height=256):
        # Independent double-precision integral along each spherical ray, in kilometres.
        radius, top, sea = args["innerRadius"], args["atmosphereHeight"], args["lowerDepth"]
        v = np.linspace(-1, 1, height)[:, None]
        h = sea + v * abs(v) * np.where(v < 0, sea, top - sea)
        rho = np.sqrt(h * (2 * radius + h))
        d = top - h + np.linspace(0, 1, width) * (rho + np.sqrt(top * (2 * radius + top)) - (top - h))
        along = ((top - h) * (2 * radius + top + h) - d*d) / (2 * np.maximum(d, 1e-30))
        mass = np.zeros((height, width, 2))
        nodes, weights = np.polynomial.legendre.leggauss(96)
        for node, weight in zip(nodes, weights):
            t = (node + 1) * d * .5
            altitude = np.sqrt((radius + h)**2 + t * (2 * along + t)) - radius
            for i, scale in enumerate((args["rayleighHeight"], args["mieHeight"])):
                mass[..., i] += weight * np.exp((sea - altitude) / scale)
        tau = (mass[..., :1] * args["rayleighExtinction"] + mass[..., 1:] * args["mieExtinction"]) * d[..., None] * .5
        data = np.ones((height, width, 4), np.float32)
        data[..., :3] = np.exp(-tau)
        self.bindings["Transmission"] = self.texture2d(data).descriptor_handle_ro
        args["transmittanceExtent"] = [width, height]

    def run(self, entry, args, origins, directions, medium_extinction=(0, 0, 0), diffuse_boundary=(0, 0, 0)):
        origins = np.asarray(origins, np.float32).reshape(-1, 4)
        directions = np.asarray(directions, np.float32).reshape(-1, 4)
        if entry not in self.kernels:
            program = self.device.load_program("air_transport_contracts.comp.slang", [entry])
            self.kernels[entry] = self.device.create_compute_kernel(program)
        transport = entry in ("integrate", "underRoof")
        stride = 5 if transport else 1
        output = self.device.create_buffer(element_count=len(origins) * stride, struct_size=16,
                                          usage=spy.BufferUsage.unordered_access)
        bindings = {"Air": args, "Origins": self.device.create_buffer(data=origins, usage=spy.BufferUsage.shader_resource),
                    "Directions": self.device.create_buffer(data=directions, usage=spy.BufferUsage.shader_resource),
                    "Count": len(origins), "Results": output}
        if transport:
            bindings.update(self.bindings)
            bindings["MediumExtinction"] = list(medium_extinction)
            bindings["DiffuseBoundary"] = list(diffuse_boundary)
        elif entry == "fogSky":
            bindings.update({key: self.bindings[key] for key in ("Volume", "Chroma")})
        self.kernels[entry].dispatch(thread_count=[len(origins), 1, 1], vars=bindings)
        return output.to_numpy().view(np.float32).reshape(len(origins), stride, 4)


def main():
    probe = Probe()
    optical = [[8, 8, .5, 0], [8, 8, .5, 0], [8, 0, .5, 0], [8, 8, .5, 0]]
    rays = [[0, 0, 0, 64], [0, 0, 0, 2048], [0, 0, 0, 256], [4, 0, 0, 2048]]
    fog = probe.run("fogSky", parameters(), optical, rays)[:, 0]
    np.testing.assert_allclose(fog[:, 0], [1, 1, np.exp(-8), 1], rtol=1e-5)
    np.testing.assert_allclose(fog[:, 1], np.exp([-4, -4, -8, -4]), rtol=1e-5)
    np.testing.assert_array_equal(fog[:3, 2], np.ones(3))
    assert fog[3, 2] == 0
    print("PASS fog sky angular transport, absorption, and measured in-frustum radiance across the cache boundary")
    origins = [[2000, 64, -3000, end] for end in (16, 1e8, 1e8, 1e8)]
    origins.append([2000, 5000, -3000, 1e8])
    rays = [[1, 0, 0, 0], [1, 0, 0, 0], [0, 1, 0, 0], [0, -1, 0, 0], [0, 1, 0, 0]]
    intervals = probe.run("weatherIntervals", parameters(), origins, rays)[:, 0]
    np.testing.assert_array_equal(intervals[:4, 0], 0)
    np.testing.assert_array_equal(intervals[:4, 2], 1)
    assert intervals[0, 1] == 16 and intervals[1, 1] > 100_000
    np.testing.assert_allclose(intervals[2:4, 1], [2000 - 64, 12001], atol=1)
    assert intervals[4, 2] == 0
    print("PASS weather layer clips at terrain, cloudward exit and deep-air boundary; long horizon rays exceed calibration distance")
    # Both view segments are far shorter than the first atmospheric quadrature cell.
    # Only the first lies under the roof. Diffuse sky light still reaches both.
    origins = [[0, 80, 0, 16], [64, 80, 0, 16]]
    rays = [[1, 0, 0, .5]] * 2
    sky = probe.texture2d(np.full((2, 2, 4), .2, np.float32))
    clear_table = probe.bindings["Scattering"]
    probe.bindings["Scattering"] = sky.descriptor_handle_ro
    clear = probe.run("integrate", parameters(), origins, rays)
    shadow = probe.run("underRoof", parameters(), origins, rays)
    np.testing.assert_array_equal(shadow[:, 1], clear[:, 1])
    np.testing.assert_array_equal(shadow[1], clear[1])
    assert np.all((shadow[0, 0, :3] > 0) & (shadow[0, 0, :3] < clear[0, 0, :3]))
    probe.bindings["Scattering"] = clear_table
    dark = probe.run("underRoof", parameters(), origins, rays)
    np.testing.assert_array_equal(dark[0, 0, :3], 0)
    print("PASS terrain shadow gates single scattering within the visible segment and preserves diffuse sky and extinction")
    intervals = np.array([[13292.854, 51125.395], [4645.4365, 30093.865], [0, 51125.395]], np.float32)
    inputs = np.pad(intervals, ((0, 0), (0, 2)))
    boundaries = probe.run("airGridEndpoints", parameters(), inputs, np.zeros_like(inputs))[:, 0]
    np.testing.assert_array_equal(boundaries[:, 0], intervals[:, 0])
    assert np.all(boundaries[:, 1] < intervals[:, 1])
    np.testing.assert_array_equal(boundaries[:, 2], intervals[:, 1])
    np.testing.assert_array_equal(boundaries[:, 3], intervals[:, 1])
    print("PASS exact terminal air-grid endpoints, including subtraction roundoff")

    origins, directions = [], []
    for height in (-11999, -500, 0, 76, 800, 50000, 99999, 100000, 100001, 200000):
        for angle in (-90, -30, -4, -.1, 0, .1, 4, 30, 90):
            ray = [np.cos(np.radians(angle)), np.sin(np.radians(angle)), 0]
            origins.append([2250, height + 63, -3170, 2e6])
            directions.append([*ray, .3765])
    origins = np.asarray(origins)
    directions = np.asarray(directions)
    maximum_error = 0
    for permutation in ([0, 1, 2], [1, 2, 0], [2, 0, 1]):
        for scale in (.0001, .001, .01):
            factor = .001 / scale
            args = parameters(worldToKilometers=scale)
            args["up"] = np.asarray(args["up"])[permutation].tolist()
            args["groundReference"] = (np.asarray(args["groundReference"])[permutation] * factor).tolist()
            values = origins.copy()
            values[:, :3] = values[:, permutation] * factor
            values[:, 3] *= factor
            rays = directions.copy()
            rays[:, :3] = rays[:, permutation]
            actual = probe.run("intervals", args, values, rays)[:, 0, :2]
            # Compare the public float inputs to an independent double-precision
            # sphere. Intersections near a tangent are ill-conditioned: a few
            # millimetres of float32 height roundoff move the root by metres.
            values = values.astype(np.float32).astype(np.float64)
            rays = rays.astype(np.float32).astype(np.float64)
            expected = np.asarray([shell_reference(args, a[:3], b[:3]) for a, b in zip(values, rays)])
            center = np.asarray(args["groundReference"], np.float32).astype(np.float64) - (
                args["innerRadius"] + args["lowerDepth"]) / float(np.float32(scale)) * np.asarray(args["up"])
            normalized = rays[:, :3] / np.linalg.norm(rays[:, :3], axis=1, keepdims=True)
            endpoints = values[:, None, :3] - center + expected[..., None] * normalized[:, None, :]
            slope = np.abs(np.sum(endpoints * normalized[:, None, :], axis=-1)
                           / np.linalg.norm(endpoints, axis=-1))
            # Two metres on ordinary rays, plus a 3 cm radial roundoff budget
            # divided by the sphere intersection's radial derivative.
            bound = factor * (2 + .03 / np.maximum(slope, 1e-6))
            assert np.all(abs(actual - expected) <= bound), (actual, expected, bound)
            assert np.isfinite(actual).all() and np.all(actual[:, 1] >= actual[:, 0])
            maximum_error = max(maximum_error, float(np.max(abs(actual - expected)) / factor))
    print(f"PASS 810 rotated/scaled atmosphere-shell intersections; maximum world-metre error {maximum_error:.4g}")

    selected_origins, selected_rays = [], []
    for height in (76, 800, 50000, 200000):
        for angle in (-90, -30, 0, 30, 90):
            for distance in (0, 1, 900, 10000, 96000, 500000):
                for split in (0, .0137, .3765, .5, .9731, 1):
                    selected_origins.append([2000, height + 63, -3000, distance])
                    selected_rays.append([np.cos(np.radians(angle)), np.sin(np.radians(angle)), 0, split])
    selected_origins = np.asarray(selected_origins)
    selected_rays = np.asarray(selected_rays)
    unit = parameters(rayleighExtinction=[0, 0, 0], mieExtinction=0)
    actual = probe.run("integrate", unit, selected_origins, selected_rays)
    assert np.isfinite(actual).all()
    np.testing.assert_array_equal(actual[:, [0, 2], :3], 0)
    np.testing.assert_array_equal(actual[:, [1, 3, 4], :3], 1)
    print(f"PASS {len(actual)} transparent-air RTE and prefix/tail cases")

    constant = parameters(rayleighHeight=1e20, mieHeight=1e20, mieExtinction=0)
    actual = probe.run("integrate", constant, selected_origins, selected_rays)
    interval = probe.run("intervals", constant, selected_origins, selected_rays)[:, 0, :2]
    length = np.maximum(0, np.minimum(interval[:, 1], selected_origins[:, 3]) - interval[:, 0])
    sigma = np.asarray(constant["rayleighExtinction"]) * constant["worldToKilometers"]
    transmission = np.exp(-sigma[None, :] * length[:, None])
    phase = 3 / (16 * np.pi) * (1 + selected_rays[:, 1] ** 2)
    radiance = (1 - transmission) * phase[:, None] * [1, 2, 3]
    np.testing.assert_allclose(actual[:, 0, :3], radiance, rtol=3e-5, atol=2e-6)
    np.testing.assert_allclose(actual[:, 1, :3], transmission, rtol=3e-5, atol=2e-6)
    np.testing.assert_allclose(actual[:, 2, :3], actual[:, 0, :3], rtol=3e-5, atol=2e-6)
    np.testing.assert_allclose(actual[:, 3, :3], actual[:, 1, :3], rtol=3e-5, atol=2e-6)
    np.testing.assert_allclose(actual[:, 4, :3], actual[:, 1, :3], rtol=3e-5, atol=2e-6)
    print(f"PASS {len(actual)} constant-coefficient analytic RTE and fixed-prefix integrals")

    absorber = np.array([2e-6, 4e-6, 8e-6])
    actual = probe.run("integrate", constant, selected_origins, selected_rays, absorber)
    prefix = np.minimum(interval[:, 0], selected_origins[:, 3])
    combined_sigma = sigma + absorber
    combined_transmission = np.exp(-sigma[None, :] * length[:, None]
                                  - absorber[None, :] * selected_origins[:, 3, None])
    combined_radiance = (np.exp(-absorber[None, :] * prefix[:, None])
                        * -np.expm1(-combined_sigma[None, :] * length[:, None])
                        * (sigma / combined_sigma)[None, :] * phase[:, None] * [1, 2, 3])
    np.testing.assert_allclose(actual[:, 0, :3], combined_radiance, rtol=4e-5, atol=2e-6)
    np.testing.assert_allclose(actual[:, 1, :3], combined_transmission, rtol=4e-5, atol=2e-6)
    np.testing.assert_allclose(actual[:, 2, :3], actual[:, 0, :3], rtol=4e-5, atol=2e-6)
    np.testing.assert_allclose(actual[:, 3, :3], actual[:, 1, :3], rtol=4e-5, atol=2e-6)
    print(f"PASS {len(actual)} air-source and independent absorbing-medium RTE cases")

    exponential = parameters()
    probe.bake_transmission(exponential)
    # A vertical ray to space at sea level has a known per-channel column density.
    vertical = probe.run("integrate", exponential, [[2000, 63, -3000, 200000]], [[0, 1, 0, .5]])
    thickness = exponential["atmosphereHeight"] - exponential["lowerDepth"]
    rayleigh, mie = exponential["rayleighHeight"], exponential["mieHeight"]
    tau = (np.array(exponential["rayleighExtinction"]) * rayleigh * -np.expm1(-thickness / rayleigh)
           + exponential["mieExtinction"] * mie * -np.expm1(-thickness / mie))
    np.testing.assert_allclose(vertical[0, 4, :3], np.exp(-tau), atol=4e-4)
    # Compare the full RGB transmission at the player's height with ray quadrature
    # at both noon and dusk, including a ray that crosses its closest approach.
    angles = np.radians([85., 30., 5., 1., 0., -1.])
    directions = np.column_stack((np.cos(angles), np.sin(angles), np.zeros_like(angles), np.full_like(angles, .5)))
    origins = np.tile([2000., 7000., -3000., 2e6], (len(angles), 1))
    actual = probe.run("integrate", exponential, origins, directions)[:, 4, :3]
    heights = (origins[:, 1] - 63.) * exponential["worldToKilometers"]
    r = exponential["innerRadius"] + exponential["lowerDepth"] + heights
    distance = np.array([shell_reference(exponential, o[:3], d[:3])[1]
                         for o, d in zip(origins, directions)]) * exponential["worldToKilometers"]
    nodes, weights = np.polynomial.legendre.leggauss(128)
    t = .5 * distance[:, None] * (1. + nodes)
    alt = np.sqrt(r[:, None]**2 + t * (2 * r[:, None] * np.sin(angles[:, None]) + t))
    alt -= exponential["innerRadius"] + exponential["lowerDepth"]
    rayleigh = (np.exp(-alt / exponential["rayleighHeight"]) @ weights) * .5 * distance
    mie = (np.exp(-alt / exponential["mieHeight"]) @ weights) * .5 * distance
    expected = np.exp(-rayleigh[:, None] * exponential["rayleighExtinction"]
                      - mie[:, None] * exponential["mieExtinction"])
    np.testing.assert_allclose(actual, expected, atol=.002)
    print("PASS sea-level and high-altitude RGB solar transmission against quadrature")
    actual = probe.run("integrate", exponential, selected_origins, selected_rays)
    np.testing.assert_allclose(actual[:, 2, :3], actual[:, 0, :3], rtol=5e-5, atol=3e-6)
    np.testing.assert_allclose(actual[:, 3, :3], actual[:, 1, :3], rtol=5e-5, atol=3e-6)
    np.testing.assert_allclose(actual[:, 4, :3], actual[:, 1, :3], rtol=5e-5, atol=3e-6)
    print(f"PASS {len(actual)} exponential-density prefix/tail and standalone-transmission matches")

    rays = [[1, 0, 0, .001], [1, 0, 0, .5]]
    origins = [[2000, 63, -3000, 200000]] * 2
    conservative = parameters(mieAlbedo=1.)
    for light in conservative["lights"]:
        light["irradiance"] = [0., 0., 0.]
    probe.bake_transmission(conservative)
    exterior = np.array([.6, .8, 1.])
    dark = probe.run("integrate", conservative, origins, rays)
    lit = probe.run("integrate", conservative, origins, rays, diffuse_boundary=exterior)
    for source, transmission in [(0, 1), (2, 3)]:
        np.testing.assert_allclose(dark[:, source, :3], 0., atol=2e-6)
        observed = lit[:, source, :3] + lit[:, transmission, :3] * exterior
        np.testing.assert_allclose(observed, np.broadcast_to(exterior, observed.shape), atol=3e-5)
        np.testing.assert_allclose(lit[:, transmission, :3], dark[:, 4, :3], atol=3e-5)
    print("PASS diffuse exterior illumination preserves equilibrium and RGB optical depth across segment splits")


if __name__ == "__main__":
    main()
