#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Pack Revelation's source texels into Kavora's two volume views and 2D array."""

import argparse
from pathlib import Path
import subprocess

from PIL import Image

REVISION = "d5911393856d9dd97375b46a1a088e6b8fddd755"
OUTPUT = Path(__file__).resolve().parents[2] / "src/dxvk/rtx_render/clouds"


def plane(path: Path, size: int, mode: str) -> bytes:
    if path.suffix == ".bin":
        image = Image.frombytes(mode, (size, size), path.read_bytes())
        if mode == "LA":
            red, green = image.split()
            unused = Image.new("L", image.size, 0)
            image = Image.merge("RGBA", (red, green, unused, unused))
        return image.convert("RGBA").tobytes()
    with Image.open(path) as image:
        if image.size != (size, size):
            raise ValueError(f"Unexpected texture extent: {path}")
        return image.convert("RGBA").tobytes()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("revelation", type=Path)
    args = parser.parse_args()
    revision = subprocess.check_output(
        ["git", "-C", str(args.revelation), "rev-parse", "HEAD"], text=True
    ).strip()
    if revision != REVISION:
        raise ValueError(f"Expected Revelation revision {REVISION}, found {revision}")
    textures = args.revelation / "shaders/texture"
    cloud = textures / "cloud"
    # Packing order matches prepareCloudNoise. Each image keeps its native extent.
    maps = (
        (cloud / "HaringPro/CloudMapLow.bin", 1024, "LA"),
        (textures / "Noise2D.png", 256, "RGBA"),
        (cloud / "HaringPro/vertical_gradient_lut_32x32.bin", 32, "L"),
        (cloud / "HaringPro/CloudMapHigh.bin", 512, "RGBA"),
        (cloud / "CurlNoise.png", 512, "RGB"),
        (cloud / "CirrusLutRev.png", 1024, "RGBA"),
    )
    (OUTPUT / "revelation_maps.bin").write_bytes(b"".join(plane(*image) for image in maps))
    (OUTPUT / "revelation_body.bin").write_bytes((cloud / "AlligatorFbm_128.bin").read_bytes())
    curl = (cloud / "SimplexGrad_64.bin").read_bytes()
    rgba = bytearray(b"\xff" * (64**3 * 4))
    for channel in range(3):
        rgba[channel::4] = curl[channel::3]
    (OUTPUT / "revelation_curl.bin").write_bytes(rgba)


if __name__ == "__main__":
    main()
