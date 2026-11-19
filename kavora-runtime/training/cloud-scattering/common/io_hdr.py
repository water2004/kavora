# SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
# SPDX-License-Identifier: Apache-2.0
"""Linear HDR reference images for training and validation."""

import numpy as np
import OpenEXR


def write_exr(path: str, rgb: np.ndarray) -> None:
    """Write a linear float32 ``(H, W, 3)`` image to an EXR file (ZIP)."""
    data = np.ascontiguousarray(rgb, dtype=np.float32)
    if data.ndim != 3 or data.shape[2] != 3:
        raise ValueError(f"expected (H, W, 3) image, got {data.shape}")
    header = {"compression": OpenEXR.ZIP_COMPRESSION, "type": OpenEXR.scanlineimage}
    OpenEXR.File(header, {"RGB": data}).write(path)


def read_exr(path: str) -> np.ndarray:
    """Read a linear ``(H, W, 3)`` float32 image."""
    part = OpenEXR.File(path).parts[0]
    if "RGB" in part.channels:
        pixels = part.channels["RGB"].pixels
    else:
        pixels = np.stack([part.channels[channel].pixels for channel in ("R", "G", "B")], axis=-1)
    return np.ascontiguousarray(pixels, dtype=np.float32)


def hdr_stats(img: np.ndarray) -> dict:
    x = np.asarray(img, dtype=np.float64)
    return {
        "mean": float(x.mean()),
        "max": float(x.max()),
        "p50": float(np.percentile(x, 50)),
        "p99": float(np.percentile(x, 99)),
        "frac_nonzero": float((x > 0).mean()),
    }
