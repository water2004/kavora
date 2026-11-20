# SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
# SPDX-License-Identifier: MIT
"""Verify intense RGB sources survive the FP16 camera bloom pyramid."""
from pathlib import Path
import re

import numpy as np
import slangpy as spy


def main():
    shaders = Path(__file__).resolve().parents[3] / "src/dxvk/shaders"
    device = spy.Device(type=spy.DeviceType.vulkan)
    session = device.create_slang_session({"include_paths": [str(shaders)], "defines": {"__SLANG__": "1"},
                                         "disable_warnings": ["30081"]})

    def kernel(name):
        source = (shaders / f"rtx/pass/bloom/bloom_{name}.comp.slang").read_text(encoding="utf-8")
        source = re.sub(r"layout\([^)]*\)\s*", "", source)
        source = re.sub(r"\bSampler2D(?:<float4>)? ", "Sampler2D<float4>.Handle ", source)
        module = session.load_module_from_source("test_bloom_" + name, source)
        return device.create_compute_kernel(session.link_program([module], [module.entry_point("main")]))

    down, up, composite = map(kernel, ("downsample", "upsample", "composite"))
    sampler = device.create_sampler(address_u=spy.TextureAddressingMode.clamp_to_edge,
                                    address_v=spy.TextureAddressingMode.clamp_to_edge)
    usage = spy.TextureUsage.shader_resource | spy.TextureUsage.unordered_access
    for depth in (1, 3, 8):
        size = 1 << depth
        color = np.array([60000, 52000, 40000, 1], dtype=np.float16)
        original = np.tile(color, (size, size, 1))
        levels = [device.create_texture(type=spy.TextureType.texture_2d, format=spy.Format.rgba16_float,
                  width=size >> i, height=size >> i, usage=usage, data=original if i == 0 else None)
                  for i in range(depth + 1)]
        views = [texture.create_view(sampler=sampler) for texture in levels]
        for i in range(1, depth + 1):
            n = size >> i
            down.dispatch(thread_count=[n, n, 1], vars={
                "InColorBuffer": views[i - 1].descriptor_handle_combined, "OutColorBuffer": levels[i],
                "cb": {"inputSizeInverse": [0.5 / n] * 2, "downsampledOutputSize": [n] * 2,
                       "downsampledOutputSizeInverse": [1 / n] * 2, "threshold": 250 if i == 1 else -1,
                       "levelWeight": 1 / depth}})
        for i in range(depth, 1, -1):
            n = size >> (i - 1)
            up.dispatch(thread_count=[n, n, 1], vars={
                "InColorBuffer": views[i].descriptor_handle_combined, "OutColorBuffer": levels[i - 1],
                "cb": {"inputSizeInverse": [2 / n] * 2, "upsampledOutputSize": [n] * 2,
                       "upsampledOutputSizeInverse": [1 / n] * 2}})
        # A uniform field must survive every blur. Inspect the pyramid itself:
        # the final composite discards non-finite samples and can hide overflow.
        np.testing.assert_allclose(levels[1].to_numpy()[..., :3].astype(float),
                                   original[::2, ::2, :3].astype(float), rtol=.003)
        composite.dispatch(thread_count=[size, size, 1], vars={
            "InOutColorBuffer": levels[0], "InBloomBuffer": views[1].descriptor_handle_combined,
            "cb": {"imageSize": [size] * 2, "imageSizeInverse": [1 / size] * 2,
                   "scatterFraction": .025, "threshold": 250}})
        np.testing.assert_allclose(levels[0].to_numpy().astype(float), original.astype(float), rtol=.003)
    print("PASS FP16 bloom conserves bright RGB radiance at depths 1, 3 and 8")


if __name__ == "__main__":
    main()
