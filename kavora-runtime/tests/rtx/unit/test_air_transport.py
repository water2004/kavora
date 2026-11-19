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

        from test_cloud_transport import weather_fixture
        weather = weather_fixture(self.device, np.zeros((3, 1, 1, 4))).create_view(sampler=sampler)
        self.keep.append(weather)
        trans = texture(np.ones((2, 2, 4)), 2)
        scatter = texture(np.zeros((2, 2, 4)), 2)
        volume = texture(np.zeros((1, 1, 1, 4)), 3)
        chroma = texture(np.zeros((1, 1, 1, 2)), 3)
        self.bindings = {"Transmission": trans.descriptor_handle_ro,
                         "Scattering": scatter.descriptor_handle_ro,
                         "TableSampler": sampler.descriptor_handle,
                         "Weather": weather.descriptor_handle_combined,
                         "Volume": volume.descriptor_handle_combined,
                         "CloudDepth": volume.descriptor_handle_combined,
                         "Chroma": chroma.descriptor_handle_combined}

    def run(self, entry, args, origins, directions, medium_extinction=(0, 0, 0)):
        origins = np.asarray(origins, np.float32).reshape(-1, 4)
        directions = np.asarray(directions, np.float32).reshape(-1, 4)
        if entry not in self.kernels:
            program = self.device.load_program("air_transport_contracts.comp.slang", [entry])
            self.kernels[entry] = self.device.create_compute_kernel(program)
        stride = 5 if entry == "integrate" else 1
        output = self.device.create_buffer(element_count=len(origins) * stride, struct_size=16,
                                          usage=spy.BufferUsage.unordered_access)
        bindings = {"Air": args, "Origins": self.device.create_buffer(data=origins, usage=spy.BufferUsage.shader_resource),
                    "Directions": self.device.create_buffer(data=directions, usage=spy.BufferUsage.shader_resource),
                    "Count": len(origins), "Results": output}
        if entry == "integrate":
            bindings.update(self.bindings)
            bindings["MediumExtinction"] = list(medium_extinction)
        self.kernels[entry].dispatch(thread_count=[len(origins), 1, 1], vars=bindings)
        return output.to_numpy().view(np.float32).reshape(len(origins), stride, 4)


def main():
    probe = Probe()
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
    actual = probe.run("integrate", exponential, selected_origins, selected_rays)
    np.testing.assert_allclose(actual[:, 2, :3], actual[:, 0, :3], rtol=5e-5, atol=3e-6)
    np.testing.assert_allclose(actual[:, 3, :3], actual[:, 1, :3], rtol=5e-5, atol=3e-6)
    np.testing.assert_allclose(actual[:, 4, :3], actual[:, 1, :3], rtol=5e-5, atol=3e-6)
    print(f"PASS {len(actual)} exponential-density prefix/tail and standalone-transmission matches")


if __name__ == "__main__":
    main()
