# SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
# SPDX-License-Identifier: MIT
"""Vulkan readback tests for production water kernels and shared particle helpers.

Install numpy and slangpy in the test environment, then run this file directly.
"""
import argparse
from pathlib import Path
import re
from subprocess import check_output

import numpy as np
import slangpy as spy


HERE = Path(__file__).resolve().parent
SHADERS = HERE.parents[2] / "src/dxvk/shaders"
WATER = "rtx/pass/water_sim/"
OPTIONS = {"include_paths": [str(HERE), str(SHADERS)]}


def check_bindings(directory, spirv_dis):
    clipmap_bindings = {"Previous": 0, "Current": 1, "Impulses": 2}
    contracts = {
        "water_sim": (clipmap_bindings, "WaterSimStep", [0, 8, 16, 20, 24, 28, 32, 36]),
        "water_impact_effects": (clipmap_bindings, "WaterImpactStep", [0, 8, 16, 20, 24, 28, 32, 36]),
        "water_wind_rows": ({"Input": 0, "Output": 1}, "WaterDetailStep", [0, 4]),
        "water_wind_columns": ({"Input": 0, "Output": 1}, None, []),
        "water_droplet_rings": ({"Output": 1}, "WaterDetailStep", [0, 4]),
    }
    for name, (expected, push, offsets) in contracts.items():
        code = check_output([str(spirv_dis), str(directory / (name + ".spv"))], text=True)
        bindings = {resource: int(index) for resource, index in re.findall(r"OpDecorate %(\w+) Binding (\d+)", code)}
        assert bindings == expected, (name, bindings, expected)
        if push:
            actual = [int(offset) for offset in re.findall(rf"OpMemberDecorate %{push}_\w+ \d+ Offset (\d+)", code)]
            assert actual == offsets and re.search(r"OpVariable %\S+ PushConstant", code), (name, actual, offsets)
        else:
            assert not re.search(r"OpVariable %\S+ PushConstant", code), name

    gbuffer = "gbuffer_rayquery_psr_inline_nrc_wboit.spv"
    code = check_output([str(spirv_dis), str(directory / gbuffer)], text=True)
    slots = {resource: int(index) for resource, index in re.findall(r"OpDecorate %(Water\w+) Binding (\d+)", code)}
    header = (SHADERS / "rtx/pass/gbuffer/gbuffer_binding_indices.h").read_text(encoding="utf-8")
    images = (SHADERS / "rtx/pass/gbuffer/gbuffer_water_bindings.h").read_text(encoding="utf-8")
    native = (SHADERS.parent / "rtx_render/rtx_pathtracer_gbuffer.cpp").read_text(encoding="utf-8")
    assert "GBUFFER_WATER_IMAGES(GBUFFER_WATER_LAYOUT)" in native
    expected = {
        "WaterSimulation": ("WATER_SIMULATION", "TEXTURE2D"),
        "WaterWind": ("WATER_WIND", "TEXTURE2DARRAY"),
        "WaterRings": ("WATER_RINGS", "TEXTURE2D"),
        "WaterImpacts": ("WATER_IMPACTS", "TEXTURE2D"),
        "WaterTileSampler": ("WATER_TILE_SAMPLER", "SAMPLER"),
    }
    assert slots.keys() == expected.keys(), slots
    for name, (binding, descriptor) in expected.items():
        macro = "GBUFFER_BINDING_" + binding
        assert re.search(rf"#define\s+{macro}\s+{slots[name]}\b", header), (macro, slots[name])
        if descriptor == "SAMPLER":
            assert re.search(rf"\bSAMPLER\({macro}\)", native), macro
        else:
            assert re.search(rf"\bX\({macro},\s*{descriptor},\s*[^,]+,\s*{name},", images), (descriptor, macro, name)


def kernel(device, path, entry="main", defines=None):
    if not path.startswith(WATER):
        return device.create_compute_kernel(device.load_program(path, [entry]))
    session = device.create_slang_session({**OPTIONS, "defines": defines or {}})
    # SlangPy's shader cursor writes constant buffers. The production push layout is checked separately.
    source = (SHADERS / path).read_text(encoding="utf-8").replace("[[vk::push_constant]]", "")
    module = session.load_module_from_source("water_kernel", source)
    return device.create_compute_kernel(session.link_program([module], [module.entry_point(entry)]))


def check_wind(device):
    size = 64
    spectrum = np.zeros((2, size, size, 4), dtype=np.float32)
    for layer, (x, y) in enumerate(((1, 2), (3, 1))):
        spectrum[layer, y, x, :2] = [0.028, -0.011]
        spectrum[layer, y, x, 2] = spectrum[layer, -y, -x, 2] = 1.8 + layer
    seed = device.create_buffer(data=spectrum, struct_size=16, usage=spy.BufferUsage.shader_resource)
    scratch = device.create_buffer(element_count=2 * size * size, struct_size=16,
                                   usage=spy.BufferUsage.shader_resource | spy.BufferUsage.unordered_access)
    output = device.create_texture(type=spy.TextureType.texture_2d_array,
                                   format=spy.Format.rgba16_float, width=size, height=size, array_length=2,
                                   usage=spy.TextureUsage.unordered_access)
    rows = kernel(device, WATER + "water_wind_fft.comp.slang")
    columns = kernel(device, WATER + "water_wind_fft.comp.slang", defines={"WATER_FFT_COLUMNS": "1"})
    for time in (0.0, 0.75):
        amplitude = 1.0 if time == 0.0 else 0.5
        rows.dispatch(thread_count=[size * size, 2, 1], vars={"Step": {"time": time, "amplitude": amplitude},
                                                           "Input": seed, "Output": scratch})
        columns.dispatch(thread_count=[size * size, 2, 1], vars={"Input": scratch, "Output": output})
        for layer, tile in enumerate((32.0, 7.0)):
            data = spectrum[layer]
            h0 = data[..., 0] + 1j * data[..., 1]
            opposite = (-np.arange(size)) % size
            phase = np.exp(1j * data[..., 2] * time)
            height = h0 * phase + np.conj(h0[np.ix_(opposite, opposite)] * phase)
            k = np.fft.fftfreq(size) * size * (2 * np.pi / tile)
            expected = np.stack([np.fft.ifft2(field).real * size**2 for field in
                                 (height, 1j * k[None, :] * height, 1j * k[:, None] * height)], axis=-1)
            np.testing.assert_allclose(output.to_numpy(layer=layer)[..., :3], expected * amplitude, atol=3e-4, rtol=8e-4)


def check_rings_and_impacts(device):
    rings = device.create_texture(format=spy.Format.rg16_float, width=128, height=128,
                                  usage=spy.TextureUsage.unordered_access)
    ring_kernel = kernel(device, WATER + "water_droplet_rings.comp.slang")
    ring_kernel.dispatch(thread_count=[128, 128, 1], vars={"Step": {"time": 1.0, "amplitude": 1.0}, "Output": rings})
    first = rings.to_numpy()
    ring_kernel.dispatch(thread_count=[128, 128, 1], vars={"Step": {"time": 1.08, "amplitude": 1.0}, "Output": rings})
    second = rings.to_numpy()
    assert np.isfinite(second).all() and np.count_nonzero(np.abs(second - first) > 0.01) > 1000
    ring_kernel.dispatch(thread_count=[128, 128, 1], vars={"Step": {"time": 1.08, "amplitude": 0.5}, "Output": rings})
    np.testing.assert_allclose(rings.to_numpy(), second * 0.5, atol=1e-4, rtol=1e-3)

    usage = spy.TextureUsage.unordered_access | spy.TextureUsage.shader_resource
    a, b = [device.create_texture(format=spy.Format.rg16_float, width=256, height=256, usage=usage) for _ in range(2)]
    impulse = device.create_buffer(data=np.array([0.25, 0.25, 1.0, -1.0], dtype=np.float32),
                                   struct_size=16, usage=spy.BufferUsage.shader_resource)
    impacts = kernel(device, WATER + "water_impact_effects.comp.slang")
    args = {"shift": [0, 0], "origin": [0.0, 0.0], "cellSize": 0.5, "delta": 0.0,
            "firstImpulse": 0, "impulseCount": 1, "effects": 20, "reset": 1}
    impacts.dispatch(thread_count=[256, 256, 1], vars={"Step": args, "Previous": a, "Current": b, "Impulses": impulse})
    np.testing.assert_array_equal(b.to_numpy()[0, 0], [1.0, 1.0])
    args.update(reset=0, firstImpulse=1, delta=0.5)
    impacts.dispatch(thread_count=[256, 256, 1], vars={"Step": args, "Previous": b, "Current": a, "Impulses": impulse})
    np.testing.assert_allclose(a.to_numpy()[0, 0], [0.74, 0.60], atol=0.001)
    args.update(delta=1.5)
    impacts.dispatch(thread_count=[256, 256, 1], vars={"Step": args, "Previous": a, "Current": b, "Impulses": impulse})
    np.testing.assert_array_equal(b.to_numpy()[0, 0], [0.0, 0.0])
    args.update(reset=1, firstImpulse=0, delta=0.0, effects=4)
    impacts.dispatch(thread_count=[256, 256, 1], vars={"Step": args, "Previous": a, "Current": b, "Impulses": impulse})
    np.testing.assert_array_equal(b.to_numpy()[0, 0], [0.0, 1.0])


def check_particles(device):
    path = "water_particle_contracts.comp.slang"
    values = np.zeros(130, dtype=np.float32)
    values[[0, 127, 128]] = [1.0, 3.0, 2.0]
    weights = device.create_buffer(data=values, struct_size=4, usage=spy.BufferUsage.shader_resource)
    distribution = device.create_buffer(element_count=132, struct_size=4,
                                        usage=spy.BufferUsage.shader_resource | spy.BufferUsage.unordered_access)
    kernel(device, path, "build").dispatch(thread_count=[256, 1, 1], vars={"weights": weights, "distribution": distribution})
    kernel(device, path, "finalize").dispatch(thread_count=[128, 1, 1], vars={"distribution": distribution})
    np.testing.assert_array_equal(distribution.to_numpy().view(np.float32), np.r_[np.ones(127), 4.0, 2.0, 2.0, 4.0, 6.0])
    samples = device.create_buffer(element_count=6000, struct_size=4, usage=spy.BufferUsage.unordered_access)
    policies = device.create_buffer(element_count=2, struct_size=16, usage=spy.BufferUsage.unordered_access)
    kernel(device, path, "sample").dispatch(thread_count=[6000, 1, 1], vars={"table": distribution, "samples": samples, "policies": policies})
    result = samples.to_numpy().view(np.uint32)
    np.testing.assert_array_equal(result, np.repeat(np.array([0, 127, 128], dtype=np.uint32), [1000, 3000, 2000]))
    np.testing.assert_allclose(policies.to_numpy().view(np.float32).reshape(2, 4),
                               [[20.0, 2.0, 0.375, 0.0], [0.0, 1.0, 0.0, 0.875]], atol=1e-6)

    confidence = device.create_texture(format=spy.Format.r32_float, width=3, height=1, usage=spy.TextureUsage.unordered_access,
                                       data=np.array([[0.25, 0.25, 0.5]], dtype=np.float32))
    position = device.create_texture(format=spy.Format.r32_uint, width=2, height=1, usage=spy.TextureUsage.unordered_access)
    selections = device.create_buffer(element_count=2, struct_size=4, usage=spy.BufferUsage.unordered_access)
    kernel(device, path, "history").dispatch(thread_count=[2, 1, 1], vars={"ReprojectionConfidence": confidence,
                                                                        "TemporalPosition": position, "selections": selections})
    np.testing.assert_array_equal(confidence.to_numpy().reshape(-1), [0.625, 0.0, 0.5])
    np.testing.assert_array_equal(position.to_numpy().reshape(-1), [2, 0xFFFFFFFF])
    np.testing.assert_array_equal(selections.to_numpy().view(np.uint32), [8, 7])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compiled-shaders", type=Path)
    parser.add_argument("--spirv-dis", type=Path, default=SHADERS.parents[2] / "external/spirv_tools/spirv-dis.exe")
    args = parser.parse_args()
    if args.compiled_shaders:
        check_bindings(args.compiled_shaders, args.spirv_dis)
        print("PASS: production SPIR-V water bindings and push-constant offsets")
    gpu = spy.Device(type=spy.DeviceType.vulkan, compiler_options=OPTIONS)
    check_wind(gpu)
    print("PASS: two FFT cascades and their slopes match NumPy at two phases")
    check_rings_and_impacts(gpu)
    print("PASS: animated rings, independent spray and sludge channels, and impact decay")
    check_particles(gpu)
    print("PASS: GPU prefix scan and 6,000 area samples, screen sizing, and temporal history policy")
