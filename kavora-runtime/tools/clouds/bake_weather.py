# SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors
# SPDX-License-Identifier: MIT
"""Bake HanPi's two weather maps and radial Cu/TCu/Cb profiles from two Aqua scenes."""
import argparse
import io
import re
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

import numpy as np
from PIL import Image
from scipy.ndimage import gaussian_filter, map_coordinates
from scipy.stats import qmc


DATE = "2026-06-19"
BBOX = "107.82,-11.9,109.52,-10.23"
ELEVATED_BBOX = "103.37244,-5.36188,105.07244,-3.69188"
SIZE = 512
ROOT = Path(__file__).resolve().parents[2]
ASSET = ROOT / "src/dxvk/rtx_render/clouds/cloud_weather.bin"
GIBS = "https://gibs.earthdata.nasa.gov"


def fetch(cache, name, url):
    path = cache / name
    if not path.exists():
        with urllib.request.urlopen(url, timeout=60) as response:
            path.write_bytes(response.read())
    return path.read_bytes()


def observation(cache, name, bbox):
    layer = "MODIS_Aqua_" + name
    query = urllib.parse.urlencode(dict(SERVICE="WMS", REQUEST="GetMap", VERSION="1.1.1",
        SRS="EPSG:4326", BBOX=bbox, WIDTH=SIZE, HEIGHT=SIZE, TIME=DATE, FORMAT="image/png",
        TRANSPARENT="TRUE", LAYERS=layer, STYLES=""))
    data = fetch(cache, layer + ".png", GIBS + "/wms/epsg4326/best/wms.cgi?" + query)
    return np.asarray(Image.open(io.BytesIO(data)).convert("RGBA"))


def retrieve(cache, layer, palette, bbox):
    image = observation(cache, layer, bbox)
    xml = fetch(cache, palette + ".xml", GIBS + "/colormaps/v1.3/" + palette + ".xml")
    colors, values = [], []
    for entry in ET.fromstring(xml).iter("ColorMapEntry"):
        if entry.get("transparent") == "true":
            continue
        bounds = re.findall(r"-?\d+(?:\.\d+)?", entry.attrib["value"])
        colors.append(tuple(map(int, entry.attrib["rgb"].split(","))))
        values.append(np.mean(list(map(float, bounds))))
    # Science layers use indexed colour. Match full RGB, preserving the low bits
    # which disambiguate adjacent height bins. Missing retrievals stay masked.
    unique, inverse = np.unique(image[..., :3].reshape(-1, 3), axis=0, return_inverse=True)
    distance = ((unique[:, None].astype(np.int32) - np.asarray(colors)) ** 2).sum(axis=2)
    indices = distance.argmin(axis=1)
    valid = (image[..., 3] > 0) & (distance.min(axis=1)[inverse].reshape(image.shape[:2]) == 0)
    return np.where(valid, np.asarray(values)[indices][inverse].reshape(image.shape[:2]), np.nan)


def ramp(x, a, b):
    t = np.clip((x - a) / (b - a), 0, 1)
    return t * t * (3 - 2 * t)


def guided_retrieval(samples, guide, sigma):
    valid = np.isfinite(samples).astype(float)
    values = np.nan_to_num(samples)
    mass = np.maximum(gaussian_filter(valid, sigma), 1e-9)
    mean = gaussian_filter(values, sigma) / mass
    mean_guide = gaussian_filter(valid * guide, sigma) / mass
    variance = gaussian_filter(valid * guide * guide, sigma) / mass - mean_guide ** 2
    covariance = gaussian_filter(values * guide, sigma) / mass - mean * mean_guide
    slope = covariance / (np.maximum(variance, 0) + 0.03 ** 2)
    # Use the higher-resolution reflectance only to guide interpolation over
    # a retrieval footprint. Do not extrapolate layers across missing swaths.
    return np.where(mass > 0.25, mean + slope * (guide - mean_guide), np.nan)


def periodic(image):
    # Stitch only the outer overlap. Opposite ends converge to the same value
    # and slope; the interior keeps the registration of all MODIS products.
    for axis in (0, 1):
        a = image.swapaxes(0, axis).copy()
        margin = SIZE // 20
        first, last = a[:margin].copy(), a[-margin:][::-1].copy()
        mix = (0.5 * (1 - ramp(np.arange(margin), 0, margin - 1)))[:, None, None]
        a[:margin] = first + mix * (last - first)
        a[-margin:] = (last + mix * (first - last))[::-1]
        image = a.swapaxes(0, axis)
    return image


def profiles(height, weight, kind, radius):
    # The three channels are conditional vertical occupancy. Each observed
    # top contributes below its height. A smoothed histogram handles sparse
    # annuli without inventing a separate profile for their missing columns.
    valid = (weight > 0) & np.isfinite(height)
    z = (np.arange(SIZE) + 0.5) / SIZE
    result = []
    for center in (0.0, 0.5, 1.0):
        weights = weight[valid] * np.exp(-0.5 * ((kind[valid] - center) / 0.17) ** 2)
        ranked = height[valid].argsort()
        ceiling = np.interp(0.97 * weights.sum(), np.cumsum(weights[ranked]), height[valid][ranked])
        tops = np.clip(height[valid] / max(ceiling, 1) * (0.40 + 0.55 * center), 0.04, 0.99)
        hist = np.histogram2d(radius[valid], tops, bins=SIZE, range=((0, 1), (0, 1)), weights=weights)[0]
        hist = gaussian_filter(hist, (SIZE / 12, SIZE / 50))
        hist += hist.sum(axis=0, keepdims=True) / SIZE * 0.03
        # Superpose individual plumes. An adiabatic liquid-water gradient
        # at fixed droplet number gives extinction proportional to LWC^(2/3).
        # The distribution of measured tops determines the loss to entrainment.
        moment = hist / z ** (2 / 3)
        extinction = z ** (2 / 3) * np.cumsum(moment[:, ::-1], axis=1)[:, ::-1]
        extinction *= ramp(z, 0, 0.04) * (1 - ramp(z, 0.96, 1.0))
        extinction /= np.maximum(extinction.max(axis=1, keepdims=True), 1e-12)
        result.append(extinction)
    # The cache can stop vertical integration above the last occupied texel.
    # Use a conservative bound over all radii and every channel mixed so far.
    support = np.zeros_like(result[0])
    for i, columns in enumerate(np.array_split(np.arange(SIZE), 3)):
        last = np.nonzero(np.max(result[:i + 1], axis=(0, 1)) >= 0.5 / 255)[0][-1]
        support[:, columns] = min(1.0, np.ceil((last + 2) * 255 / SIZE) / 255)
    return np.stack([*result, support], axis=-1)


def deck(cache, bbox):
    cache.mkdir(parents=True, exist_ok=True)
    rgb = observation(cache, "CorrectedReflectance_TrueColor", bbox)[..., :3] / 255.0
    height = retrieve(cache, "Cloud_Top_Height_Day", "MODIS_VIIRS_Cloud_Top_Height", bbox)
    tau = retrieve(cache, "Cloud_Optical_Thickness", "MODIS_VIIRS_Cloud_Optical_Thickness", bbox)
    pressure = retrieve(cache, "Cloud_Top_Pressure_Day", "MODIS_Cloud_Top_Pressure", bbox)
    fraction = retrieve(cache, "Cloud_Fraction_Day", "MODIS_Cloud_Fraction", bbox) / 100
    # The red band has a dark ocean background. Use this observation's ocean
    # and optically thick cloud endmembers to recover the sub-footprint edges.
    red = rgb[..., 0]
    # Five-kilometre cloud-top and fraction footprints, one kilometre for tau.
    km = SIZE / 185.0
    height = np.maximum(0, guided_retrieval(height, red, 2.5 * km))
    pressure = guided_retrieval(pressure, red, 2.5 * km)
    fraction = np.clip(guided_retrieval(fraction, red, 2.5 * km), 0, 1)
    tau = np.maximum(0, guided_retrieval(tau, red, 0.5 * km))
    ocean = np.quantile(red, 0.03)
    thick = np.median(red[tau >= 23])
    cover = ramp(red, ocean, thick) * np.nan_to_num(fraction)
    # ISCCP pressure and optical-depth bins distinguish shallow decks,
    # elevated sheets and optically thick deep convection continuously.
    middle = 1 - ramp(pressure, 440, 680)
    upper = 1 - ramp(pressure, 350, 530)
    middle = np.nan_to_num(middle)
    upper = np.nan_to_num(upper)
    depth = np.nan_to_num(tau)
    deep = upper * ramp(depth, 18, 28)
    elevated = middle * (1 - deep)
    kind = 0.5 * ramp(np.nan_to_num(height), 1200, 3500) + deep * 0.5
    # Cell-scale texture in the measured band separates smooth sheet from
    # clustered cells without moving the boundaries of either cloud deck.
    local = gaussian_filter(cover, 4)
    contrast = np.sqrt(np.maximum(0, gaussian_filter(cover ** 2, 4) - local ** 2))
    cells = ramp(contrast, np.quantile(contrast, 0.25), np.quantile(contrast, 0.8))
    sc = (1 - middle) * ramp(depth, 3.6, 23) * (1 - cells)
    low = cover * (1 - elevated)
    high = cover * elevated
    # Cell centres from resolved reflectance, normalized against their own
    # neighbourhood. These give the two decks independent cellular structure.
    cell = np.clip(0.5 + (cover - local) / np.maximum(2 * contrast, 0.1), 0, 1)
    softness_control = depth / (depth + 3.6)
    return (np.stack([low, kind, sc, cell], axis=-1),
            np.stack([high, cells, softness_control, cell], axis=-1), height)


def coverage_threshold(fraction, softness, volumes):
    # Measure the area filled by HanPi's softened threshold at the profile
    # peak. Its integral over the empirical signal distribution has a closed
    # form between two order statistics. Individual edges still use 3D erosion.
    base = np.fromfile(volumes / "cloud_base.bin", dtype="<f2", count=64 ** 3 * 2).reshape(64, 64, 64, 2)
    erosion = np.fromfile(volumes / "cloud_erosion.bin", dtype="u1", count=64 ** 3 * 4).reshape(64, 64, 64, 4)
    # A regular 64-cubed lattice aliases the 16:1 detail-to-body frequency
    # down to four detail samples per axis. Sample the actual two mip-0 fields
    # at common world positions using a deterministic low-discrepancy sequence.
    uv = qmc.Sobol(3, scramble=False).random_base2(18).T
    body = map_coordinates(base[..., 0].astype(float), 64 * uv - 0.5, order=1, mode="grid-wrap") ** 0.6
    billows = (0.65 * erosion[..., 2] + 0.35 * erosion[..., 3]) / 255
    detail = map_coordinates(billows, 64 * 16 * uv * np.array([[1], [-1], [1]]) - 0.5, order=1, mode="grid-wrap")
    # The shallow marine deck is Cu. Its dense body supplies the coverage
    # convention; the wispy extension and type mixing remain in the shader.
    erode = 0.18 * detail
    shape = np.sort(np.clip((body - erode) / (1 - erode), 0, 1))
    prefix = np.r_[0, np.cumsum(shape)]
    control = np.linspace(0, 1, 1025)
    threshold = 1 - control
    lo = np.searchsorted(shape, threshold)
    widths = np.linspace(0.001, 0.2, 17)
    table = []
    for width in widths:
        hi = np.searchsorted(shape, threshold + width)
        partial = (prefix[hi] - prefix[lo] - (hi - lo) * threshold) / width
        covered = np.clip((shape.size - hi + partial) / shape.size, 0, 1)
        distinct, first = np.unique(covered, return_index=True)
        table.append(np.interp(control, distinct, control[first]))
    indices = np.stack([np.interp(softness, widths, np.arange(len(widths))), fraction * (len(control) - 1)])
    return map_coordinates(np.asarray(table), indices, order=1, mode="nearest")


def bake(cache, volumes):
    low, _, height = deck(cache, BBOX)
    upper_low, high, upper_height = deck(cache / ELEVATED_BBOX, ELEVATED_BBOX)
    xy = (np.arange(SIZE) + 0.5) / SIZE - 0.5
    radius = np.minimum(1, 2 * np.hypot(xy[:, None], xy[None, :]))
    lut = profiles(np.stack([height, upper_height]),
            np.stack([low[..., 0], upper_low[..., 0]]), np.stack([low[..., 1], upper_low[..., 1]]),
            np.stack([radius, radius]))
    low[..., 0] = coverage_threshold(low[..., 0], 0.2 * (1 - high[..., 2]), volumes)
    maps = [periodic(low), periodic(high), lut]
    for name, a in zip(("low", "high", "profiles"), maps):
        if not np.isfinite(a).all() or np.ptp(a[..., 0]) < 0.15:
            raise ValueError(f"Missing or degenerate {name} data in observation")
        print(name, np.round(np.mean(a, axis=(0, 1)), 3))
    return np.stack(maps)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--volumes", type=Path, default=ASSET.parent)
    parser.add_argument("--output", type=Path, default=ASSET)
    args = parser.parse_args()
    args.cache.mkdir(exist_ok=True, parents=True)
    maps = bake(args.cache, args.volumes)
    with args.output.open("wb") as out:
        while True:
            out.write(np.rint(np.clip(maps, 0, 1) * 255).astype("u1").tobytes())
            _, h, w, c = maps.shape
            if w == 1:
                break
            maps = maps.reshape(3, h // 2, 2, w // 2, 2, c).mean(axis=(2, 4))


if __name__ == "__main__":
    main()
