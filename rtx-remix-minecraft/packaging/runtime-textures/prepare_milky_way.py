# SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
# SPDX-License-Identifier: Apache-2.0

"""Prepare the attributed NASA SVS galaxy asset. Not part of the build."""

import argparse
import hashlib
from pathlib import Path
import struct
import subprocess
import tempfile

import numpy as np


SOURCE_SHA256 = "fad63b36aa632c691d521cd9d8b828de9d29b4c2784276eec44e54c6cb159a49"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--ffmpeg", default="ffmpeg")
    parser.add_argument("--texconv", required=True)
    parser.add_argument("--output", type=Path, default=Path(__file__).parent)
    args = parser.parse_args()
    if hashlib.sha256(args.source.read_bytes()).hexdigest() != SOURCE_SHA256:
        raise ValueError("Expected the original NASA milkyway_2020_4k_gal.exr")
    decoded = subprocess.run(
        [args.ffmpeg, "-v", "error", "-i", str(args.source), "-frames:v", "1",
         "-f", "rawvideo", "-pix_fmt", "gbrpf32le", "pipe:1"],
        stdout=subprocess.PIPE, check=True,
    ).stdout
    rgb = np.frombuffer(decoded, dtype="<f4").reshape(3, 2048, 4096)[[2, 0, 1]]
    rgb = rgb.transpose(1, 2, 0).reshape(1024, 2, 2048, 2, 3).mean(axis=(1, 3))
    if not np.isfinite(rgb).all() or (rgb < 0).any():
        raise ValueError("The source must contain finite, nonnegative linear radiance")
    # DX10 R32G32B32_FLOAT preserves linear data without an intermediate tone map.
    header = [124, 0x100F, 1024, 2048, 2048 * 12, 0, 1] + [0] * 11
    header += [32, 4, int.from_bytes(b"DX10", "little"), 0, 0, 0, 0, 0]
    header += [0x1000, 0, 0, 0, 0]
    args.output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="remix-galaxy-") as temporary:
        source = Path(temporary) / "milky_way.dds"
        source.write_bytes(b"DDS " + struct.pack("<31I", *header)
                           + struct.pack("<5I", 6, 3, 0, 1, 3)
                           + rgb.astype("<f4").tobytes())
        subprocess.run([args.texconv, "-nologo", "-y", "-nogpu", "-f", "BC6H_UF16",
                        "-m", "0", "-o", str(args.output.resolve()), str(source)], check=True)
    result = args.output / "milky_way.dds"
    print(f"{hashlib.sha256(result.read_bytes()).hexdigest()}  {result}")


if __name__ == "__main__":
    main()
