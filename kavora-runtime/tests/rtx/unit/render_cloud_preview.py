# SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
# SPDX-License-Identifier: MIT
"""Render actual production Slang cloud density without starting Minecraft.

Requires numpy, Pillow and slangpy. A software Vulkan ICD is sufficient.

Perspective opacity uses cloudTransmittance directly. Top-down optical depth
integrates cloudExtinction without the camera-distance fade. Optional radiance
uses production cloudIncidentRadiance under a constant diagnostic environment.
An optional atmosphere fixture supplies the production sky texture and Java
light profile. Clouds use the production hemisphere quadrature for ambient
illumination. --air also uses that fixture's optical tables in the production
joint air/cloud integrator. Neither mode includes terrain, celestial disks or
the game's temporal reconstruction.

--shader-root selects code, not that revision's native option defaults; pass the
same explicit CLI parameters when comparing shader revisions.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import time

import numpy as np
from PIL import Image, ImageDraw
import slangpy as spy


HERE = Path(__file__).resolve().parent
DEFAULT_REPO = HERE.parents[3]
VIEWS = {
    'top': (0.0, -90.0), 'zenith': (0.0, 90.0),
    '45': (0.0, 45.0), 'horizon': (0.0, 10.0), 'zenith90': (90.0, 90.0),
}


def triple(value):
    result = [float(v) for v in value.split(',')]
    if len(result) != 3 or not np.isfinite(result).all():
        raise argparse.ArgumentTypeError('expected three finite values: x,y,z')
    return result


def basis(yaw, pitch):
    yaw, pitch = np.radians([yaw, pitch])
    forward = np.array([np.sin(yaw) * np.cos(pitch), np.sin(pitch), np.cos(yaw) * np.cos(pitch)])
    right = np.array([np.cos(yaw), 0, -np.sin(yaw)])
    return forward.tolist(), right.tolist(), np.cross(forward, right).tolist()


def texture_cache(device, support, cache_dir):
    hashes = {name: hashlib.sha256((support.CLOUD_ASSETS / file).read_bytes()).hexdigest()
              for name, file in support.TEXTURE_FILES.items()}
    fingerprint = hashlib.sha256(json.dumps(hashes, sort_keys=True).encode()).hexdigest()
    cache = cache_dir / f'textures-{fingerprint[:20]}.npz'
    textures = support.cloud_textures(device)
    if not cache.exists():
        cache.parent.mkdir(parents=True, exist_ok=True)
        levels = {}
        for name, view in textures.items():
            for mip in range(view.texture.mip_count):
                levels[f'{name}_{mip}'] = (np.stack([view.texture.to_numpy(layer, mip) for layer in range(3)])
                                           if name == 'Weather' else view.texture.to_numpy(mip=mip))
        np.savez(cache, **levels)
    return textures, str(cache), {'fingerprint': fingerprint, 'sourceHashes': hashes}


def save_image(path, rgb):
    Image.fromarray(np.rint(np.clip(rgb, 0, 1) * 255).astype(np.uint8)).save(path)


class PreviewDisplay:
    def __init__(self, device, tone_map):
        self.device = device
        self.kernel = None
        if tone_map == 'aces':
            self.kernel = device.create_compute_kernel(device.load_program('cloud_preview.comp.slang', ['mainDisplay']))

    def encode(self, linear_rgb, exposure):
        rgb = linear_rgb * exposure
        if self.kernel is not None:
            pixels = np.ones((linear_rgb.shape[0] * linear_rgb.shape[1], 4), np.float32)
            pixels[:, :3] = linear_rgb.reshape(-1, 3)
            output = self.device.create_buffer(element_count=len(pixels), struct_size=16,
                                                usage=spy.BufferUsage.unordered_access)
            self.kernel.dispatch(thread_count=[len(pixels), 1, 1], vars={
                'DisplayInput': self.device.create_buffer(data=pixels, usage=spy.BufferUsage.shader_resource),
                'DisplayOutput': output, 'DisplayCount': len(pixels), 'DisplayExposure': exposure})
            rgb = output.to_numpy().view(np.float32).reshape(*linear_rgb.shape[:2], 4)[:, :, :3]
        if not np.isfinite(rgb).all():
            raise RuntimeError('Nonfinite display output')
        return np.where(rgb <= 0.0031308, rgb * 12.92, 1.055 * np.maximum(rgb, 0) ** (1 / 2.4) - 0.055)


def display_metadata(manifest, args, shaders):
    manifest['toneMap'] = args.tone_map
    manifest['displayExposure'] = args.exposure
    manifest['displayTransfer'] = ('production ACESFitted at fixed exposure then sRGB; no local exposure fusion'
                                   if args.tone_map == 'aces' else 'fixed linear exposure then sRGB; clipped for PNG')
    if args.tone_map == 'aces':
        curve = shaders / 'rtx/pass/tonemap/ACES.hlsl'
        manifest['toneMapSourceHash'] = hashlib.sha256(curve.read_bytes()).hexdigest()
        manifest['displayShaderHash'] = hashlib.sha256((HERE / 'cloud_preview.comp.slang').read_bytes()).hexdigest()
    else:
        manifest.pop('toneMapSourceHash', None)
        manifest.pop('displayShaderHash', None)


def redisplay(args, shaders):
    path = args.output / 'manifest.json'
    manifest = json.loads(path.read_text())
    perspective_views = [view for view in manifest['views'] if view != 'top']
    if not perspective_views or not manifest.get('radiance', all(
            (args.output / f'{view}-radiance.png').is_file() for view in perspective_views)):
        raise ValueError('redisplay requires a capture rendered with --radiance')
    args.exposure = manifest['displayExposure'] if args.exposure is None else args.exposure
    args.tone_map = args.tone_map or manifest.get('toneMap', 'clip')
    if not np.isfinite(args.exposure) or args.exposure <= 0:
        raise ValueError('exposure must be positive and finite')
    device = spy.Device(type=spy.DeviceType.vulkan, compiler_options={
        'include_paths': [str(shaders), str(HERE)], 'defines': {'__SLANG__': '1'}})
    display = PreviewDisplay(device, args.tone_map)
    manifest.setdefault('displayHistory', []).append({key: manifest[key] for key in
        ('displayExposure', 'displayTransfer', 'toneMap', 'toneMapSourceHash') if key in manifest})
    for view in manifest['views']:
        if view == 'top':
            continue
        with np.load(args.output / f'{view}.npz') as raw:
            cloud = raw['result']
            sky = raw['sky'] if 'sky' in raw else np.array(manifest['diagnosticSkyLinearRgb'])
            linear_rgb = cloud[:, :, :3] + cloud[:, :, 3:] * sky
        # Retain the unclamped HDR data and a simple clipped diagnostic alongside the display curve.
        save_image(args.output / f'{view}-linear.png', PreviewDisplay(device, 'clip').encode(linear_rgb, args.exposure))
        save_image(args.output / f'{view}-radiance.png', display.encode(linear_rgb, args.exposure))
    display_metadata(manifest, args, shaders)
    path.write_text(json.dumps(manifest, indent=2))
    contact_sheet(args.output, manifest['label'], list(manifest['views']), *manifest['resolution'], True,
                  manifest['camera'], manifest['verticalFovDegrees'], 'atmosphereFixture' in manifest,
                  manifest['lightingMode'], args.exposure, args.tone_map, manifest.get('finiteDistanceAir', False))
    print('Redisplayed existing HDR data:', args.output, flush=True)


def compare_texture_channels(reference, candidate):
    report = {}
    with np.load(reference) as before, np.load(candidate) as after:
        for name, channels in (('Base', [0, 1]), ('Weather', [0, 1, 2]), ('Detail', [0, 1, 2, 3])):
            exact = True
            maximum = 0.0
            changed = 0
            count = 0
            for key in sorted(key for key in before.files if key.startswith(name + '_')):
                a, b = before[key][..., channels], after[key][..., channels]
                if a.shape != b.shape or a.dtype != b.dtype:
                    report[name] = {'compatible': False, 'referenceShape': list(a.shape), 'candidateShape': list(b.shape)}
                    break
                exact &= np.array_equal(a, b)
                changed += int(np.count_nonzero(a != b))
                count += a.size
                maximum = max(maximum, float(np.max(np.abs(a.astype(np.float32) - b.astype(np.float32)))))
            else:
                report[name] = {'channels': channels, 'exactEqual': bool(exact), 'changedValues': changed,
                                'valueCount': count, 'maximumStoredValueDifference': maximum}
    return report


def contact_sheet(output, label, views, width, height, radiance, origin, vfov, atmosphere, lighting, exposure, tone_map, air):
    panels = []
    for view in views:
        panels.append((f'{view}: production cloud opacity (white = 1-T)', output / f'{view}-opacity.png'))
        if view == 'top':
            panels.append(('top: vertical optical depth, fixed display range 0..1', output / 'top-tau.png'))
        elif radiance:
            background = 'production sky' if atmosphere else 'diagnostic sky'
            transport = 'production joint air/cloud scattering' if air else f'production scattering + {background}'
            panels.append((f'{view}: {transport}', output / f'{view}-radiance.png'))
    columns = 2
    title_height, caption_height = 84, 34
    rows = (len(panels) + columns - 1) // columns
    sheet = Image.new('RGB', (columns * width, title_height + rows * (height + caption_height)), '#191c24')
    draw = ImageDraw.Draw(sheet)
    draw.text((12, 9), label, fill='white')
    sky = 'production atmosphere inputs' if atmosphere else 'constant diagnostic environment'
    draw.text((12, 29), f'Production Slang; {sky}; lighting={lighting}; exposure={exposure:g}; display={tone_map}', fill='#bec6d4')
    transport = 'joint air/cloud transport' if air else 'cloud-only transport'
    draw.text((12, 49), f'VFOV{vfov:g}; camera{tuple(origin)}; {transport}; no terrain/temporal reconstruction', fill='#bec6d4')
    for index, (caption, path) in enumerate(panels):
        x, y = (index % columns) * width, title_height + (index // columns) * (height + caption_height)
        draw.text((x + 7, y + 8), caption, fill='white')
        sheet.paste(Image.open(path).convert('RGB'), (x, y + caption_height))
    sheet.save(output / 'contact-sheet.png')


def air_inputs(device, fixture_path, fixture, origin, pixel_count):
    if 'opticalModel' not in fixture:
        raise ValueError('Regenerate this atmosphere fixture to export its production optical model')
    with np.load(fixture_path / 'atmosphere.npz') as data:
        arrays = [data['lut'].astype(np.float32), data['scattering'].astype(np.float32)]
    sampler = device.create_sampler(min_filter=spy.TextureFilteringMode.linear,
                                    mag_filter=spy.TextureFilteringMode.linear,
                                    address_u=spy.TextureAddressingMode.clamp_to_edge,
                                    address_v=spy.TextureAddressingMode.clamp_to_edge)
    views = []
    for array in arrays:
        if not np.isfinite(array).all():
            raise ValueError('The atmospheric tables must contain finite data')
        texture = device.create_texture(type=spy.TextureType.texture_2d, format=spy.Format.rgba32_float,
                                        width=array.shape[1], height=array.shape[0], mip_count=1,
                                        usage=spy.TextureUsage.shader_resource)
        texture.copy_from_numpy(array)
        views.append(texture.create_view())
    chroma = device.create_texture(type=spy.TextureType.texture_3d, format=spy.Format.rg16_float,
                                   width=1, height=1, depth=1, mip_count=1,
                                   usage=spy.TextureUsage.shader_resource)
    chroma.copy_from_numpy(np.zeros((1, 1, 1, 2), np.float16))
    chroma_view = chroma.create_view(sampler=sampler)
    transmission = device.create_buffer(element_count=pixel_count, struct_size=16,
                                         usage=spy.BufferUsage.unordered_access)
    air = fixture['opticalModel'] | {
        'enable': 1, 'up': [0, 1, 0], 'worldToKilometers': 0.001,
        'groundReference': [origin[0], fixture['seaLevel'], origin[2]],
        'mieAnisotropy': fixture['mieAnisotropy'],
        'transmittanceTextureIndex': 0, 'scatteringTextureIndex': 0,
        'transmittanceExtent': [arrays[0].shape[1], arrays[0].shape[0]],
        'scatteringSize': arrays[1].shape[0],
        'lights': [
            {'directionToLight': fixture['sunDirection'], 'angularRadius': 0.004625123,
             'irradiance': [fixture['sunSourceRadiance'] * np.pi] * 3},
            {'directionToLight': (-np.array(fixture['sunDirection'])).tolist(),
             'angularRadius': 0.004625123, 'irradiance': [0, 0, 0]}]}
    bindings = {'Air': air, 'AirTransmission': views[0].descriptor_handle_ro,
                'AirScattering': views[1].descriptor_handle_ro, 'AirSampler': sampler.descriptor_handle,
                'FogChroma': chroma_view.descriptor_handle_combined, 'TransmissionResults': transmission}
    return air, bindings, (views, sampler, chroma_view, transmission)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, default=DEFAULT_REPO)
    parser.add_argument('--shader-root', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--label', default='Production cloud inspection')
    parser.add_argument('--cache-dir', type=Path, help='Generated texture cache; defaults beside output')
    parser.add_argument('--compare-textures', type=Path, help='Reference texture cache for exact low-field channel comparisons')
    parser.add_argument('--width', type=int, default=384)
    parser.add_argument('--height', type=int, default=256)
    parser.add_argument('--vfov', type=float, default=75)
    parser.add_argument('--origin', type=triple, default=[0, 139, 0])
    parser.add_argument('--wind', type=triple, default=[0, 0, 0])
    parser.add_argument('--views', default='top,zenith,45,horizon,zenith90')
    parser.add_argument('--yaw', type=float, default=0, help='Rotate all perspective views around world up, in degrees')
    parser.add_argument('--layers', choices=['high', 'low', 'both'], default='high')
    parser.add_argument('--low-base', type=float, default=800.0, help='Low cloud base in meters')
    parser.add_argument('--low-thickness', type=float, default=1500.0, help='Maximum low cloud thickness in meters')
    parser.add_argument('--low-shape-scale', type=float, default=1024.0, help='Low cloud base texture period in meters')
    parser.add_argument('--low-extinction', type=float, default=0.03, help='Low cloud extinction per meter at unit density')
    parser.add_argument('--low-coverage', type=float, default=0.45, help='Low cloud regional coverage')
    parser.add_argument('--low-max-distance', type=float, default=96000.0, help='Low cloud view range in meters')
    parser.add_argument('--high-base', type=float, default=6375.0, help='High cloud base in meters')
    parser.add_argument('--high-thickness', type=float, default=50.0, help='High cloud thickness in meters')
    parser.add_argument('--high-extinction', type=float, default=0.024, help='High cloud extinction per meter')
    parser.add_argument('--high-max-distance', type=float, default=96000.0, help='High cloud view range in meters')
    parser.add_argument('--coverage', type=float, default=0.35, help='High layer coverage')
    parser.add_argument('--weather', type=float, default=0)
    parser.add_argument('--thunder', type=float, default=0)
    parser.add_argument('--ground-albedo', type=float, default=0.18,
                        help='Mean terrain reflectance used by production cloud illumination')
    parser.add_argument('--steps', type=int, default=384,
                        help='Low cloud view integration budget')
    parser.add_argument('--high-steps', type=int, default=115,
                        help='High cloud view integration budget')
    parser.add_argument('--light-steps', type=int, default=16,
                        help='Light integration budget; values above native Ultra are for convergence inspection')
    parser.add_argument('--sun-elevation', type=float,
                        help='Diagnostic sun elevation in degrees; no atmospheric coloring')
    parser.add_argument('--sun-azimuth', type=float,
                        help='Diagnostic sun azimuth in degrees; defaults to 30')
    parser.add_argument('--sun-irradiance-rgb', type=triple,
                        help='Linear RGB source irradiance; defaults to pi,pi,pi')
    parser.add_argument('--ambient-rgb', type=triple,
                        help='Linear RGB ambient illumination; defaults to 0.04,0.06,0.10')
    parser.add_argument('--atmosphere-fixture', type=Path,
                        help='Directory containing production atmosphere.npz and its manifest.json')
    parser.add_argument('--air', action='store_true',
                        help='Integrate production finite-distance air, clouds and their shared ray grid')
    parser.add_argument('--lighting', choices=['all', 'direct', 'ambient'], default='all',
                        help='Isolate cloud lighting components while retaining the same sky background')
    parser.add_argument('--exposure', type=float,
                        help='Fixed linear exposure for PNG display only; raw HDR is preserved')
    parser.add_argument('--tone-map', choices=['clip', 'aces'],
                        help='Display curve; aces executes the shipped ACES fit without local exposure fusion')
    parser.add_argument('--redisplay', action='store_true',
                        help='Redisplay existing NPZ results without recomputing clouds; preserve raw source provenance')
    parser.add_argument('--top-span', type=float, default=32000)
    parser.add_argument('--radiance', action='store_true')
    parser.add_argument('--warmup', action='store_true', help='Warm Vulkan pipelines before measured dispatches')
    parser.add_argument('--repeat', type=int, default=1, help='Measured identical-frame dispatches per view and lighting volume')
    args = parser.parse_args()
    shaders = args.shader_root or args.repo / 'kavora-runtime/src/dxvk/shaders'
    if args.redisplay:
        redisplay(args, shaders)
        return
    args.exposure = 1.0 if args.exposure is None else args.exposure
    args.tone_map = args.tone_map or 'clip'
    if args.width <= 0 or args.height <= 0 or not np.isfinite(args.top_span) or args.top_span <= 0:
        parser.error('width, height and top-span must be positive')
    if args.repeat < 1:
        parser.error('repeat must be positive')
    if not 0 < args.vfov < 180:
        parser.error('vfov must be between 0 and 180 degrees')
    if not np.isfinite(args.yaw):
        parser.error('yaw must be finite')
    if not 1 <= args.steps <= 1024 or not 1 <= args.high_steps <= 1024 or not 1 <= args.light_steps <= 256:
        parser.error('steps and high-steps must be in [1,1024] and light-steps in [1,256]')
    if any(not np.isfinite(value) or not 0 <= value <= 1 for value in
           (args.weather, args.thunder, args.ground_albedo)):
        parser.error('weather, thunder and ground-albedo must be in [0,1]')
    if (not np.isfinite([args.low_base, args.low_thickness, args.low_shape_scale,
                         args.low_extinction, args.low_coverage, args.low_max_distance]).all()
            or args.low_base < 0 or min(args.low_thickness, args.low_shape_scale) <= 0
            or args.low_extinction < 0 or args.low_max_distance <= 0 or not 0 <= args.low_coverage <= 1):
        parser.error('low cloud base/extinction must be nonnegative, thickness/scale/range positive and coverage in [0,1]')
    if args.sun_elevation is not None and not -90 <= args.sun_elevation <= 90:
        parser.error('sun-elevation must be between -90 and 90 degrees')
    if args.sun_azimuth is not None and not np.isfinite(args.sun_azimuth):
        parser.error('sun-azimuth must be finite')
    if not np.isfinite(args.exposure) or args.exposure <= 0:
        parser.error('exposure must be positive and finite')
    if args.atmosphere_fixture and any(value is not None for value in (
            args.sun_elevation, args.sun_azimuth, args.sun_irradiance_rgb, args.ambient_rgb)):
        parser.error('atmosphere-fixture supplies the sun and ambient inputs; use --lighting to isolate components')
    args.sun_irradiance_rgb = args.sun_irradiance_rgb or [np.pi, np.pi, np.pi]
    args.ambient_rgb = args.ambient_rgb or [0.04, 0.06, 0.10]
    if min(*args.sun_irradiance_rgb, *args.ambient_rgb) < 0:
        parser.error('sun irradiance and ambient illumination must be nonnegative')
    if (not np.isfinite([args.high_base, args.high_thickness, args.high_extinction, args.high_max_distance]).all()
            or args.high_base < 0 or args.high_thickness <= 0 or args.high_extinction < 0
            or args.high_max_distance <= 0):
        parser.error('high cloud base/extinction must be nonnegative and thickness/range must be positive')
    views = args.views.split(',')
    unknown_views = sorted(set(views) - VIEWS.keys())
    if unknown_views:
        parser.error('unknown views: ' + ', '.join(unknown_views))
    support_path = args.repo / 'kavora-runtime/tests/rtx/unit/test_cloud_transport.py'
    spec = importlib.util.spec_from_file_location('cloud_test_support', support_path)
    support = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(support)
    support.SHADERS = shaders
    support.OPTIONS = {'include_paths': [str(shaders), str(HERE)], 'defines': {'__SLANG__': '1'}}
    if re.search(r'\bpublic\s+float\s+spanAt\s*\(', (shaders / 'rtx/algorithm/clouds.slang').read_text()):
        support.OPTIONS['defines']['CLOUD_PREVIEW_HAS_CELL_SPAN'] = '1'
    if re.search(r'cloudHeightFootprint\([^)]*\buint\s+layer', (shaders / 'rtx/algorithm/clouds.slang').read_text(), re.S):
        support.OPTIONS['defines']['CLOUD_PREVIEW_HAS_LAYER_HEIGHT_FOOTPRINT'] = '1'
    if 'cloudDensityFootprint' in (shaders / 'rtx/algorithm/clouds.slang').read_text():
        support.OPTIONS['defines']['CLOUD_PREVIEW_HAS_DENSITY_FOOTPRINT'] = '1'
    if re.search(r'cloudViewExtinction\([^)]*\buint\s+layer', (shaders / 'rtx/algorithm/clouds.slang').read_text(), re.S):
        support.OPTIONS['defines']['CLOUD_PREVIEW_HAS_VIEW_EXTINCTION_LAYER'] = '1'
    if 'createCloudRayGrid' in (shaders / 'rtx/algorithm/clouds.slang').read_text():
        support.OPTIONS['defines']['CLOUD_PREVIEW_HAS_ADAPTIVE_GRID'] = '1'
    if 'createCloudViewGrid' in (shaders / 'rtx/algorithm/clouds.slang').read_text():
        support.OPTIONS['defines']['CLOUD_PREVIEW_HAS_VIEW_GRID'] = '1'
    if 'cloudViewSampleOffset' in (shaders / 'rtx/algorithm/clouds.slang').read_text():
        support.OPTIONS['defines']['CLOUD_PREVIEW_HAS_SAMPLE_OFFSET'] = '1'
    has_lighting_cache = (shaders / 'rtx/algorithm/cloud_lighting_cache.slang').exists()
    if has_lighting_cache:
        support.OPTIONS['defines']['CLOUD_PREVIEW_HAS_LIGHTING_CACHE'] = '1'
    cloud_passes = shaders / 'rtx/pass/clouds'
    has_local_light = (cloud_passes / 'generate_cloud_local_light.comp.slang').is_file()
    if has_local_light:
        support.OPTIONS['defines']['CLOUD_PREVIEW_HAS_LOCAL_LIGHT'] = '1'
    if (cloud_passes / 'cloud_lighting_bake.slang').is_file():
        support.OPTIONS['defines']['CLOUD_PREVIEW_HAS_LIGHTING_BAKE'] = '1'
    direct_shader = cloud_passes / 'generate_cloud_direct_light.comp.slang'
    if direct_shader.is_file():
        support.OPTIONS['defines']['CLOUD_PREVIEW_HAS_DIRECT_LIGHT'] = '1'
    if 'uint layer, float sigma, float3 ambientRadiance' in (shaders / 'rtx/algorithm/clouds.slang').read_text():
        support.OPTIONS['defines']['CLOUD_PREVIEW_HAS_SAMPLE_EXTINCTION'] = '1'
    if 'CloudNeuralAngle neuralAngles' in (shaders / 'rtx/algorithm/clouds.slang').read_text():
        support.OPTIONS['defines']['CLOUD_PREVIEW_HAS_NEURAL_ANGLE'] = '1'
    if 'cloudNeuralSky(' in (shaders / 'rtx/algorithm/clouds.slang').read_text():
        support.OPTIONS['defines']['CLOUD_PREVIEW_HAS_NEURAL_SKY'] = '1'
    atmosphere = None
    if args.air and (not args.atmosphere_fixture or not args.radiance):
        parser.error('--air requires --atmosphere-fixture and --radiance')
    if args.atmosphere_fixture:
        fixture_path = args.atmosphere_fixture / 'manifest.json'
        atmosphere = json.loads(fixture_path.read_text())
        if not np.isclose(atmosphere['worldHeight'], args.origin[1], rtol=0, atol=1e-4):
            parser.error('atmosphere fixture observer height must match the camera world height')
        support.OPTIONS['defines']['CLOUD_PREVIEW_ATMOSPHERE'] = '1'
        if 'kCloudAmbientSampleCount' in (shaders / 'rtx/algorithm/cloud_sky.slang').read_text():
            support.OPTIONS['defines']['CLOUD_PREVIEW_HAS_AMBIENT_SAMPLE_COUNT'] = '1'
    if args.air:
        support.OPTIONS['defines']['CLOUD_PREVIEW_AIR'] = '1'
    device = spy.Device(type=spy.DeviceType.vulkan, compiler_options=support.OPTIONS)
    display = PreviewDisplay(device, args.tone_map)
    cache_dir = args.cache_dir or args.output.parent / 'cloud-preview-cache'
    textures, cache, texture_sources = texture_cache(device, support, cache_dir)
    program = device.load_program('cloud_preview.comp.slang', ['main'])
    kernel = device.create_compute_kernel(program)
    output = device.create_buffer(element_count=args.width * args.height, struct_size=16,
                                  usage=spy.BufferUsage.unordered_access)
    environment = None
    atmosphere_view = None
    if atmosphere:
        with np.load(args.atmosphere_fixture / 'atmosphere.npz') as fixture:
            sky = fixture['sky']
            if sky.ndim != 3 or sky.shape[-1] != 4 or not np.isfinite(sky).all():
                raise ValueError('atmosphere sky must contain finite RGBA pixels')
            texture = device.create_texture(type=spy.TextureType.texture_2d, format=spy.Format.rgba16_float,
                                            width=sky.shape[1], height=sky.shape[0], mip_count=1,
                                            usage=spy.TextureUsage.shader_resource)
            texture.copy_from_numpy(sky.astype(np.float16))
        sampler = device.create_sampler(min_filter=spy.TextureFilteringMode.linear,
                                        mag_filter=spy.TextureFilteringMode.linear,
                                        address_u=spy.TextureAddressingMode.wrap,
                                        address_v=spy.TextureAddressingMode.clamp_to_edge)
        atmosphere_view = texture.create_view(sampler=sampler)
        environment = device.create_buffer(element_count=args.width * args.height + 1, struct_size=16,
                                           usage=spy.BufferUsage.unordered_access)
    sun = [0.3, 0.8, np.sqrt(1 - 0.3 ** 2 - 0.8 ** 2)]
    if args.sun_elevation is not None or args.sun_azimuth is not None:
        elevation = np.radians(args.sun_elevation) if args.sun_elevation is not None else np.arcsin(0.8)
        azimuth = np.radians(args.sun_azimuth if args.sun_azimuth is not None else 30.0)
        sun = [np.sin(azimuth) * np.cos(elevation), np.sin(elevation), np.cos(azimuth) * np.cos(elevation)]
    light = {'directionToLight': sun, 'irradiance': args.sun_irradiance_rgb, 'hasRadianceProfile': 0,
             'angularRadius': 0.0}
    if atmosphere:
        profile = np.array(atmosphere['javaLighting']['cloudIrradianceProfile'], np.float32).reshape(4, 4)
        if not np.isfinite(profile).all() or np.any(profile[:, :3] < 0) or np.any(np.diff(profile[:, 3]) <= 0):
            raise ValueError('atmosphere light profile must be finite, nonnegative and sorted by height')
        sun = atmosphere['sunDirection']
        has_ground_boundary = 'groundAlbedo' in (shaders / 'rtx/pass/cloud_args.h').read_text()
        surface = atmosphere['javaLighting'].get('surfaceIrradiance')
        if has_ground_boundary and surface is None:
            parser.error('the terrain boundary needs an atmosphere fixture with Java surfaceIrradiance')
        if surface is None:
            surface = profile[:, :3].max(axis=0).tolist()
        if np.shape(surface) != (3,) or not np.isfinite(surface).all() or min(surface) < 0:
            raise ValueError('surface irradiance must contain three finite nonnegative components')
        light = {'directionToLight': sun, 'irradiance': surface,
                 'hasRadianceProfile': 1, 'radianceProfile': profile.tolist(), 'angularRadius': 0.004625123}
    if args.lighting == 'ambient':
        light['irradiance'] = [0, 0, 0]
        if 'radianceProfile' in light:
            light['radianceProfile'] = [[0, 0, 0, row[3]] for row in light['radianceProfile']]
    dark = {'directionToLight': [0, -1, 0], 'irradiance': [0, 0, 0], 'hasRadianceProfile': 0,
            'angularRadius': 0.0}
    clouds = support.cloud_parameters(
        baseHeight=args.low_base, thickness=args.low_thickness, shapeScale=args.low_shape_scale,
        extinction=args.low_extinction, coverage=args.low_coverage, maxDistance=args.low_max_distance,
        highBaseHeight=args.high_base, highThickness=args.high_thickness, highExtinction=args.high_extinction,
        highCoverage=args.coverage, highMaxDistance=args.high_max_distance,
        groundReference=[args.origin[0], 0, args.origin[2]], windOffset=args.wind,
        viewAngularFootprint=2 * np.tan(np.radians(args.vfov) * 0.5) / args.height,
        weather=args.weather, thunder=args.thunder, groundAlbedo=args.ground_albedo,
        steps=args.steps, highSteps=args.high_steps, lightSteps=args.light_steps, lights=[light, dark])
    cloud_layout = (shaders / 'rtx/pass/cloud_args.h').read_text()
    if 'activeCloudLights' in cloud_layout:
        clouds['activeCloudLights'] = int(any(light['irradiance']) or any(
            any(row[:3]) for row in light.get('radianceProfile', [])))
    if 'groundAlbedo' not in cloud_layout:
        clouds.pop('groundAlbedo', None)
    if 'lightingNearDistance' not in cloud_layout:
        clouds.pop('lightingNearDistance', None)
    if 'highSteps' not in cloud_layout:
        clouds.pop('highSteps', None)
    if 'angularRadius' not in cloud_layout:
        for source in clouds['lights']:
            source.pop('angularRadius', None)
    if args.layers == 'high': clouds['extinction'] = 0
    if args.layers == 'low': clouds['highExtinction'] = 0
    air_bindings = {}
    if args.air:
        air, air_bindings, air_resources = air_inputs(device, args.atmosphere_fixture, atmosphere,
                                                     args.origin, args.width * args.height)
        clouds['planetRadius'] = (air['innerRadius'] + air['lowerDepth']) / air['worldToKilometers'] - atmosphere['seaLevel']
    if atmosphere:
        ambient_program = device.load_program('cloud_preview.comp.slang', ['mainAmbient'])
        ambient_kernel = device.create_compute_kernel(ambient_program)
        ambient_kernel.dispatch(thread_count=[256, 1, 1], vars={
            'Clouds': clouds, 'EnvironmentResults': environment, 'Size': [args.width, args.height],
            'Atmosphere': atmosphere_view.descriptor_handle_combined,
            'AtmosphereVerticalScale': atmosphere['verticalScale'],
            'AmbientScale': float(args.lighting != 'direct')})
    lighting_bindings = {}
    if has_lighting_cache:
        from cloud_lighting_preview import build_lighting_cache
        lighting_bindings, lighting_resources, lighting_metadata = build_lighting_cache(
            device, clouds, textures, shaders, args.radiance and any(view != 'top' for view in views),
            args.warmup, args.repeat, include_local=has_local_light,
            include_neural='CLOUD_PREVIEW_HAS_NEURAL_SKY' in support.OPTIONS['defines'])
    args.output.mkdir(parents=True, exist_ok=True)
    manifest = {'label': args.label, 'shaderRoot': str(shaders), 'textureCache': cache,
                'textureSources': texture_sources,
                'previewShaderHash': hashlib.sha256((HERE / 'cloud_preview.comp.slang').read_bytes()).hexdigest(),
                'previewIntegralHash': hashlib.sha256((HERE / 'cloud_preview_integral.slang').read_bytes()).hexdigest(),
                'previewDefines': support.OPTIONS['defines'],
                'camera': args.origin, 'verticalFovDegrees': args.vfov, 'resolution': [args.width, args.height],
                'layers': args.layers, 'radiance': bool(args.radiance),
                'topSpanMeters': args.top_span, 'topHeightMeters': 10000.0,
                'clouds': clouds, 'views': {},
                'diagnosticAmbient': args.ambient_rgb if args.lighting != 'direct' else [0, 0, 0],
                'diagnosticSunElevationDegrees': float(np.degrees(np.arcsin(sun[1]))),
                'diagnosticSunAzimuthDegrees': float(np.degrees(np.arctan2(sun[0], sun[2]))),
                'diagnosticSkyLinearRgb': [0.12, 0.26, 0.48],
                'lightingMode': args.lighting, 'displayExposure': args.exposure,
                'displayTransfer': 'fixed linear exposure then sRGB; clipped for PNG; no auto exposure',
                'coordinateConvention': 'Y up; yaw0 faces +Z; elevation positive up',
                'sourceHashes': {str(path.relative_to(shaders)): hashlib.sha256(path.read_bytes()).hexdigest()
                                 for path in sorted({*(shaders / 'rtx/algorithm').glob('cloud*.slang'),
                                                     *(shaders / 'rtx/utility').glob('cloud*.slang*'),
                                                     shaders / 'rtx/pass/cloud_parameters.slang',
                                                     shaders / 'rtx/utility/lat_long_projection.slang'})}}
    display_metadata(manifest, args, shaders)
    if has_lighting_cache:
        manifest['cloudLightingCache'] = lighting_metadata
        for relative in ('rtx/pass/clouds/cloud_lighting_cache.h',
                         'rtx/pass/clouds/generate_cloud_lighting.comp.slang'):
            manifest['sourceHashes'][relative] = hashlib.sha256((shaders / relative).read_bytes()).hexdigest()
        if direct_shader.is_file():
            manifest['sourceHashes'][direct_shader.relative_to(shaders).as_posix()] = hashlib.sha256(direct_shader.read_bytes()).hexdigest()
        manifest['lightingHarnessHashes'] = {
            name: hashlib.sha256((HERE / name).read_bytes()).hexdigest()
            for name in ('cloud_lighting_preview.py', 'cloud_lighting_preview.comp.slang')}
    manifest['finiteDistanceAir'] = bool(args.air)
    if args.air:
        manifest['air'] = air
        manifest['airScope'] = ('Production air optical model and LUTs in the shared air/cloud segment integrator; '
                                'black space boundary, no sun disk, terrain, scene fog or temporal reconstruction')
        for relative in ('rtx/algorithm/air_medium.slang', 'rtx/algorithm/atmosphere.slang',
                         'rtx/pass/air_args.h', 'rtx/pass/volume_args.h'):
            manifest['sourceHashes'][relative] = hashlib.sha256((shaders / relative).read_bytes()).hexdigest()
    if atmosphere:
        manifest['atmosphereFixture'] = {
            'path': str(args.atmosphere_fixture), 'manifest': atmosphere,
            'dataHash': hashlib.sha256((args.atmosphere_fixture / 'atmosphere.npz').read_bytes()).hexdigest()}
        for name in ('diagnosticAmbient', 'diagnosticSkyLinearRgb'):
            del manifest[name]
    if args.compare_textures:
        manifest['lowFieldTextureComparison'] = compare_texture_channels(args.compare_textures, cache)
        print('Low-field assets:', json.dumps(manifest['lowFieldTextureComparison']), flush=True)
    for view in views:
        yaw, pitch = VIEWS[view]
        yaw += args.yaw
        forward, right, up = basis(yaw, pitch)
        origin = [args.origin[0], 10000, args.origin[2]] if view == 'top' else args.origin
        illumination = {'Ambient': args.ambient_rgb if args.lighting != 'direct' else [0, 0, 0]}
        if atmosphere:
            illumination = {'Atmosphere': atmosphere_view.descriptor_handle_combined,
                            'EnvironmentResults': environment,
                            'AtmosphereVerticalScale': atmosphere['verticalScale']}
        view_bindings = {
            'Clouds': clouds, 'Results': output, 'Size': [args.width, args.height],
            'Origin': origin, 'Forward': forward, 'Right': right, 'CameraUp': up,
            'TanHalfFov': np.tan(np.radians(args.vfov) * 0.5), 'TopSpan': args.top_span,
            'Projection': int(view == 'top'), **illumination,
            **air_bindings,
            **lighting_bindings,
            'Radiance': int(args.radiance and view != 'top'),
            **{name: value.descriptor_handle_combined for name, value in textures.items()},
        }
        if args.warmup:
            kernel.dispatch(thread_count=[args.width, args.height, 1], vars=view_bindings)
            device.wait_for_idle()
        started = time.monotonic()
        render_samples = []
        for _ in range(args.repeat):
            sample_started = time.monotonic()
            kernel.dispatch(thread_count=[args.width, args.height, 1], vars=view_bindings)
            data = output.to_numpy().view(np.float32).reshape(args.height, args.width, 4)
            render_samples.append(time.monotonic() - sample_started)
        render_seconds = float(np.median(render_samples))
        if not np.isfinite(data).all(): raise RuntimeError(f'Nonfinite production output in {view}')
        opacity = 1 - data[:, :, 3]
        background = np.array([0.12, 0.26, 0.48])
        raw = {'result': data}
        if atmosphere:
            environment_data = environment.to_numpy().view(np.float32).reshape(-1, 4)
            if not np.isfinite(environment_data).all():
                raise RuntimeError(f'Nonfinite atmosphere sampling in {view}')
            background = environment_data[:-1, :3].reshape(args.height, args.width, 3)
            raw.update(sky=background, ambient=environment_data[-1, :3])
            manifest['sampledCloudAmbient'] = environment_data[-1, :3].tolist()
        if args.air and view != 'top':
            air_transmission = air_resources[-1].to_numpy().view(np.float32).reshape(args.height, args.width, 4)
            if not np.isfinite(air_transmission).all():
                raise RuntimeError(f'Nonfinite air transmission in {view}')
            # The shared integrator already contains the sky's air emission.
            # This fixture's outer space boundary has no celestial disks.
            raw['environmentSky'] = background
            background = np.zeros_like(background)
            raw.update(sky=background, airTransmittance=air_transmission[:, :, :3])
        np.savez(args.output / f'{view}.npz', **raw)
        save_image(args.output / f'{view}-opacity.png', np.repeat(opacity[:, :, None], 3, axis=2))
        if view == 'top':
            save_image(args.output / 'top-tau.png', np.repeat(data[:, :, :1], 3, axis=2))
        elif args.radiance:
            linear_rgb = data[:, :, :3] + data[:, :, 3:] * background
            if args.tone_map == 'aces':
                save_image(args.output / f'{view}-linear.png', PreviewDisplay(device, 'clip').encode(linear_rgb, args.exposure))
            save_image(args.output / f'{view}-radiance.png', display.encode(linear_rgb, args.exposure))
        statistics = {'yawDegrees': yaw, 'elevationDegrees': pitch, 'seconds': time.monotonic() - started,
                      'dispatchReadbackSeconds': render_seconds,
                      'dispatchReadbackSamplesSeconds': render_samples, 'warmedUp': bool(args.warmup),
                      'meanOpacity': float(opacity.mean()), 'maxOpacity': float(opacity.max()),
                      'opacityAboveOnePercent': float(np.mean(opacity > 0.01))}
        manifest['views'][view] = statistics
        print(view, json.dumps(statistics), flush=True)
        (args.output / 'manifest.json').write_text(json.dumps(manifest, indent=2))
    contact_sheet(args.output, args.label, views, args.width, args.height, args.radiance, args.origin, args.vfov,
                  bool(atmosphere), args.lighting, args.exposure, args.tone_map, args.air)
    print('Saved', args.output / 'contact-sheet.png', flush=True)


if __name__ == '__main__':
    main()
