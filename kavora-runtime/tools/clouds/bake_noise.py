# SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors
# SPDX-License-Identifier: MIT
"""Bake the author-preset base and four-band erosion volumes, including filtered mips."""
import argparse
import hashlib
from pathlib import Path
import urllib.request
import numpy as np
import slangpy as spy

HERE = Path(__file__).resolve().parent
DXVK = HERE.parents[1] / "src/dxvk"
REVISION = "4126f0f3190ff29270b286b5713f2e911547db2e"
SOURCE_HASH = "91a464875ed825f70dc484927faf7e27ccec4a1ca7d71715568c867f8a15754a"
BASE_PRESET = dict(Resolution=64, BaseFrequency=4.0, BaseOctaves=8, BasePersistence=0.678,
                   BaseWorleyScale=0.5, BaseCoverage=0.66, DetailFrequency=8.0, DetailOctaves=6,
                   DetailPersistence=1.0, WorleyCellCount=1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, default=DXVK / "rtx_render/clouds")
    args = parser.parse_args()
    args.cache.mkdir(parents=True, exist_ok=True)
    source = args.cache / "NoiseGenerator.compute"
    if not source.exists():
        url = f"https://raw.githubusercontent.com/AshenOneArt/3DTextureExportTools/{REVISION}/VolumeCloud/Shaders/{source.name}"
        with urllib.request.urlopen(url, timeout=60) as response:
            content = response.read()
    else:
        content = source.read_bytes()
    if hashlib.sha256(content).hexdigest() != SOURCE_HASH:
        raise ValueError(f"Unexpected upstream noise shader at {source}")
    if not source.exists():
        source.write_bytes(content)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    device = spy.Device(type=spy.DeviceType.vulkan, compiler_options={
        "include_paths": [str(HERE), str(args.cache.resolve())]})
    for entry in ("base", "erosion"):
        base = entry == "base"
        kernel = device.create_compute_kernel(device.load_program("bake_noise.slang",
            ["GenerateCloudNoise" if base else "erosion"]))
        texture = device.create_texture(type=spy.TextureType.texture_3d, format=spy.Format.rgba32_float,
            width=64, height=64, depth=64, usage=spy.TextureUsage.unordered_access)
        kernel.dispatch(thread_count=[64, 64, 64], vars={"Result": texture} | (BASE_PRESET if base else {}))
        data = texture.to_numpy()
        if base:
            data = data[..., :2]
            data[..., 1] = 0  # The second channel stores sub-voxel variance in the mips.
        else:
            # An isotropic low-frequency field must retain longer correlations
            # than its high band. Filaments must have a preferred orientation.
            gradients = np.stack([np.abs(np.diff(data, axis=a)).mean(axis=(0, 1, 2)) for a in range(3)])
            print("mean absolute neighbour differences (z,y,x) per RGBA channel", gradients)
            assert np.all(gradients[:, 1] > gradients[:, 0]) and np.all(gradients[:, 3] > gradients[:, 2])
            assert gradients[0, 0] > 1.3 * gradients[2, 0]
            assert np.max(np.abs(np.corrcoef(data.reshape(-1, 4).T) - np.eye(4))) < 0.8
        with (args.output_dir / f"cloud_{entry}.bin").open("wb") as out:
            while True:
                out.write(data.astype("<f2").tobytes() if base else
                          np.rint(np.clip(data, 0, 1) * 255).astype("u1").tobytes())
                n = data.shape[0]
                if n == 1:
                    break
                children = data.reshape(n // 2, 2, n // 2, 2, n // 2, 2, data.shape[-1])
                data = children.mean(axis=(1, 3, 5))
                if base:
                    data[..., 1] += children[..., 0].var(axis=(1, 3, 5))


if __name__ == "__main__":
    main()
