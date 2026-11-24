# SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
# SPDX-License-Identifier: MIT
"""Read back the production final-present helper on CPU or Vulkan.

Requires NumPy and SlangPy. This checks the immutable source/target color
contract, including late fallback, without claiming Windows HDR presentation.
"""
import argparse
from pathlib import Path

import numpy as np
import slangpy as spy


HERE = Path(__file__).resolve().parent
SHADERS = HERE.parents[2] / "src/dxvk/shaders"
SOURCE_HDR = 512
SOURCE_LINEAR = 1024
TARGET_SRGB = 256


def srgb_encode(value):
    value = np.asarray(value, np.float64)
    magnitude = np.abs(value)
    return np.sign(value) * np.where(magnitude <= 0.0031308, 12.92 * magnitude,
                                     1.055 * magnitude ** (1 / 2.4) - 0.055)


def srgb_decode(value):
    value = np.asarray(value, np.float64)
    magnitude = np.abs(value)
    return np.sign(value) * np.where(magnitude <= 0.04045, magnitude / 12.92,
                                     ((magnitude + 0.055) / 1.055) ** 2.4)


def pq(nits):
    value = np.clip(np.asarray(nits, np.float64) / 10000, 0, 1) ** (2610 / 16384)
    return ((3424 / 4096 + (2413 / 128) * value) / (1 + (2392 / 128) * value)) ** (2523 / 32)


def dither(count, frame, hdr):
    # Reconstruct only the deterministic quantization noise, so physical-unit
    # assertions compare against the independent double-precision transfer above.
    values = []
    for pixel in range(count):
        noise = (pixel * 1664525 + frame * 747796405) & 0xffffffff
        noise = ((noise ^ (noise >> 16)) * 2246822519) & 0xffffffff
        noise = ((noise ^ (noise >> 13)) * 3266489917) & 0xffffffff
        noise ^= noise >> 16
        values.append(((noise & 65535) / 65535 - 0.5) / (1023 if hdr else 255))
    return np.asarray(values)[:, None]


class PresentProbe:
    def __init__(self, device):
        self.device = device
        program = device.load_program("display_present_contracts.comp.slang", ["presentContracts"])
        self.kernel = device.create_compute_kernel(program)

    def run(self, linear, transfer, source=(200, 1000), target=(200, 1000), frame=19, encoded=False):
        rgb = np.asarray(linear, np.float64).reshape(-1, 3)
        data = np.column_stack([rgb if encoded else srgb_encode(rgb), np.ones(len(rgb))]).astype(np.float32)
        output = self.device.create_buffer(element_count=len(data), struct_size=16,
                                          usage=spy.BufferUsage.unordered_access)
        self.kernel.dispatch(thread_count=[len(data), 1, 1], vars={
            "Inputs": self.device.create_buffer(data=data, usage=spy.BufferUsage.shader_resource),
            "Results": output, "Count": len(data), "display_transfer": transfer,
            "display_frame_index": frame, "source_paper_white_nits": source[0], "source_peak_nits": source[1],
            "target_paper_white_nits": target[0], "target_peak_nits": target[1],
        })
        return output.to_numpy().view(np.float32).reshape(-1, 4)[:, :3]


def expect_nits(probe, linear, nits, transfer=2 | SOURCE_HDR, source=(200, 1000), target=(200, 1000)):
    actual = probe.run(linear, transfer, source, target)
    expected = np.clip(pq(nits) + dither(len(actual), 19, True), 0, 1)
    np.testing.assert_allclose(actual, expected, atol=2e-5, rtol=0)
    return actual


def check_present(probe):
    white = np.ones((1, 3))
    expect_nits(probe, white, white * 200, transfer=2, source=(80, 80))
    sampled_srgb = np.full((1, 3), 0.18)
    np.testing.assert_allclose(probe.run(sampled_srgb, 2 | SOURCE_LINEAR, encoded=True),
                               probe.run(sampled_srgb, 2), atol=2e-5, rtol=0)
    ramp = np.repeat(np.asarray([0, 0.18, 1, 2, 5])[:, None], 3, axis=1)
    expect_nits(probe, ramp, ramp * 200)
    expect_nits(probe, white, white * 500, source=(500, 1000))
    expect_nits(probe, white, white * 500, source=(500, 500), target=(200, 500))
    colors = np.asarray([[1, 0, 0], [0, 2, 0], [0, 0, 5], [1, 2, 5]])
    matrix = np.asarray([[0.6274, 0.3293, 0.0433], [0.0691, 0.9195, 0.0114], [0.0164, 0.0880, 0.8956]])
    expect_nits(probe, colors, colors @ matrix.T * 200)
    print("PASS HDR10 units: first SDR-range frame, immutable source white, equal peak/white and RGB primaries")

    # Compare after removing the known noise. A late smaller display must retain
    # ordered highlight detail instead of clipping all old highlights together.
    late = probe.run(ramp, 2 | SOURCE_HDR, target=(200, 400)) - dither(len(ramp), 19, True)
    assert np.isfinite(late).all() and np.all(np.diff(late[:, 0]) > 0)
    assert np.max(late) <= pq(400) + 2e-5
    np.testing.assert_allclose(late[2], pq(200), atol=2e-5, rtol=0)
    ordinary = probe.run(ramp, 2 | SOURCE_HDR)
    np.testing.assert_array_equal(ordinary, probe.run(ramp, 2 | SOURCE_HDR))
    print("PASS late peak reduction: finite ordered highlights, preserved paper white and deterministic frame quantization")

    linear = np.repeat(np.asarray([0, 0.0031308, 0.18, 0.5, 0.75, 1])[:, None], 3, axis=1)
    sdr = probe.run(linear, 1)
    expected = np.clip(srgb_encode(linear) + dither(len(linear), 19, False), 0, 1)
    np.testing.assert_allclose(sdr, expected, atol=4e-7, rtol=0)
    hardware = probe.run(linear, 1 | TARGET_SRGB)
    np.testing.assert_allclose(srgb_encode(hardware), sdr, atol=4e-7, rtol=0)
    fallback = probe.run(ramp, 3 | SOURCE_HDR)
    assert np.isfinite(fallback).all() and np.all((fallback >= 0) & (fallback <= 1))
    assert np.all(np.diff(fallback[:, 0]) > 0)
    np.testing.assert_allclose(srgb_encode(probe.run(ramp, 3 | SOURCE_HDR | TARGET_SRGB)), fallback, atol=4e-7)
    print("PASS SDR and late fallback: exact transfer, ordered compressed highlights and hardware sRGB compensation")

    bad = np.asarray([[np.nan, np.inf, -np.inf], [-1, -1000, 0], [3e38, 3e38, 3e38]])
    for transfer in (1, 2 | SOURCE_HDR, 3 | SOURCE_HDR):
        result = probe.run(bad, transfer, encoded=True)
        assert np.isfinite(result).all() and np.all((result >= 0) & (result <= 1))
    print("PASS invalid-source containment: no nonfinite display output")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", choices=("cpu", "vulkan"), default="vulkan")
    args = parser.parse_args()
    device = spy.Device(type=getattr(spy.DeviceType, args.device), compiler_options={
        "include_paths": [str(HERE), str(SHADERS)], "defines": {"__SLANG__": "1"},
    })
    check_present(PresentProbe(device))


if __name__ == "__main__":
    main()
