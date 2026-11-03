/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Map;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Converts LabPBR _n/_s textures into Remix-compatible PBR channel data.
 *
 * <p>LabPBR spec: https://shaderlabs.org/wiki/LabPBR_Material_Standard
 */
public final class LabPBRConverter {

    /** Shared diffusion-profile range used by terrain and entity materials. */
    public static final float DIFFUSION_SSS_RADIUS_SCALE = 100.0f;
    public static final float DIFFUSION_SSS_MAX_SAMPLE_RADIUS = 32.0f;

    private LabPBRConverter() {}

    // --- Per-pixel conversions (int 0-255 domain) ---

    private static int smoothnessToRoughness(int s) {
        double lin = 1.0 - s / 255.0;
        return clamp255((int) (lin * lin * 255.0));
    }

    private static int f0ToMetallic(int g) { return g >= 230 ? 255 : 0; }

    private static int sssIntensity(int b) { return b >= 65 ? clamp255((b - 65) * 255 / 190) : 0; }

    // --- Atlas builders (operate on ABGR pixel arrays from NativeImage) ---

    /**
     * Builds an RGBA8 normal atlas from _n images blitted into the given region.
     * Reconstructs Z from X,Y per the LabPBR spec.
     */
    public static void blitNormal(byte[] dst, int atlasW, NativeImage n, int ox, int oy, int w, int h) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int abgr = NativeImagePixels.abgr(n, x % n.getWidth(), y % n.getHeight());
                int nr = abgr & 0xFF;
                int ng = (abgr >> 8) & 0xFF;
                double nx = (nr / 255.0) * 2.0 - 1.0;
                double ny = (ng / 255.0) * 2.0 - 1.0;
                double nz = Math.sqrt(Math.max(0, 1.0 - nx * nx - ny * ny));
                int idx = ((oy + y) * atlasW + (ox + x)) * 4;
                dst[idx]     = (byte) nr;
                dst[idx + 1] = (byte) ng;
                dst[idx + 2] = (byte) clamp255((int) (nz * 255.0));
                dst[idx + 3] = (byte) 255;
            }
        }
    }

    /** Multiplies RGBA albedo in-place by the AO stored in _n blue (0 = occluded, 255 = none). */
    public static void applyAO(byte[] albedoRGBA, int atlasW, NativeImage n, int ox, int oy, int w, int h) {
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int abgr = NativeImagePixels.abgr(n, x % n.getWidth(), y % n.getHeight());
                int ao = (abgr >> 16) & 0xFF;
                int idx = ((oy + y) * atlasW + (ox + x)) * 4;
                albedoRGBA[idx]     = (byte) ((albedoRGBA[idx]     & 0xFF) * ao / 255);
                albedoRGBA[idx + 1] = (byte) ((albedoRGBA[idx + 1] & 0xFF) * ao / 255);
                albedoRGBA[idx + 2] = (byte) ((albedoRGBA[idx + 2] & 0xFF) * ao / 255);
            }
    }

    /** Blits a single R8 channel from _n alpha (height). */
    public static void blitHeight(byte[] dst, int atlasW, NativeImage n, int ox, int oy, int w, int h) {
        blitR8(dst, atlasW, n, ox, oy, w, h, 24); // alpha shift
    }

    /** Blits roughness R8 from _s red channel (perceptual smoothness -> linear roughness). */
    public static void blitRoughness(byte[] dst, int atlasW, NativeImage s, int ox, int oy, int w, int h) {
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int abgr = NativeImagePixels.abgr(s, x % s.getWidth(), y % s.getHeight());
                dst[(oy + y) * atlasW + (ox + x)] = (byte) smoothnessToRoughness(abgr & 0xFF);
            }
    }

    /** Blits metallic R8 from _s green channel. */
    public static void blitMetallic(byte[] dst, int atlasW, NativeImage s, int ox, int oy, int w, int h) {
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int abgr = NativeImagePixels.abgr(s, x % s.getWidth(), y % s.getHeight());
                dst[(oy + y) * atlasW + (ox + x)] = (byte) f0ToMetallic((abgr >> 8) & 0xFF);
            }
    }

    /**
     * Blits SSS intensity as RGBA8 (R=G=B=intensity, A=255) from _s blue channel.
     * Uniform RGB is required because dxvk-remix samples the radius texture as RGB;
     * an R8 upload would produce (R,0,0), scattering only in the red channel.
     */
    public static void blitSSS(byte[] dst, int atlasW, NativeImage s, int ox, int oy, int w, int h) {
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int abgr = NativeImagePixels.abgr(s, x % s.getWidth(), y % s.getHeight());
                byte val = (byte) sssIntensity((abgr >> 16) & 0xFF);
                int idx = ((oy + y) * atlasW + (ox + x)) * 4;
                dst[idx] = val; dst[idx + 1] = val; dst[idx + 2] = val;
                dst[idx + 3] = (byte) 255;
            }
    }

    /** Blits R8 thin-surface thickness from the LabPBR specular blue channel. */
    public static void blitSssThickness(byte[] dst, int atlasW, NativeImage s, int ox, int oy, int w, int h) {
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int abgr = NativeImagePixels.abgr(s, x % s.getWidth(), y % s.getHeight());
                dst[(oy + y) * atlasW + ox + x] = (byte) sssIntensity((abgr >> 16) & 0xFF);
            }
    }

    /** Blits explicit SSS single-scattering albedo RGB from a {@code _sss} texture. */
    public static void blitSingleScatteringAlbedo(byte[] dst, int atlasW, NativeImage sss,
                                                  int ox, int oy, int w, int h) {
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int abgr = NativeImagePixels.abgr(sss, x % sss.getWidth(), y % sss.getHeight());
                int idx = ((oy + y) * atlasW + (ox + x)) * 4;
                dst[idx]     = (byte) (abgr & 0xFF);
                dst[idx + 1] = (byte) ((abgr >> 8) & 0xFF);
                dst[idx + 2] = (byte) ((abgr >> 16) & 0xFF);
                dst[idx + 3] = (byte) 255;
            }
    }

    /** Builds the fallback single-scattering albedo atlas from the current albedo atlas. */
    public static byte[] createSingleScatteringAlbedoFallback(byte[] albedoRGBA) {
        return Arrays.copyOf(albedoRGBA, albedoRGBA.length);
    }

    /**
     * Blits emissive RGBA8 from albedo modulated by emission factor.
     *
     * <p>Per-pixel: if _s alpha < 255, uses LabPBR emission (alpha/254).
     * Otherwise falls back to {@code fallbackFactor}. Block atlas callers pass
     * 0 so vanilla block luminance does not become surface emission.
     *
     * @param s              _s texture, or null when only using fallback
     * @param fallbackFactor emission intensity for pixels with alpha==255 (or when s is null)
     */
    public static void blitEmissive(byte[] dst, int atlasW, byte[] albedoRGBA,
                                     NativeImage s, int ox, int oy, int w, int h,
                                     float fallbackFactor) {
        blitEmissive(dst, atlasW, albedoRGBA, s, ox, oy, w, h, fallbackFactor, 1.0f);
    }

    /**
     * As above, with the authored factor scaled by {@code gain} and clamped to 1. The same
     * gain goes through {@link #applyEmissionGain} on the animated-frame path, so a sprite
     * reads the same from the atlas build and from its animation patches.
     */
    public static void blitEmissive(byte[] dst, int atlasW, byte[] albedoRGBA,
                                     NativeImage s, int ox, int oy, int w, int h,
                                     float fallbackFactor, float gain) {
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                float factor;
                if (s != null) {
                    int alpha = (NativeImagePixels.abgr(s, x % s.getWidth(), y % s.getHeight()) >> 24) & 0xFF;
                    factor = (alpha < 255) ? Math.min(1.0f, alpha / 254.0f * gain) : fallbackFactor;
                } else {
                    factor = fallbackFactor;
                }
                int ai = ((oy + y) * atlasW + (ox + x)) * 4;
                dst[ai]     = (byte) clamp255((int) ((albedoRGBA[ai] & 0xFF) * factor));
                dst[ai + 1] = (byte) clamp255((int) ((albedoRGBA[ai + 1] & 0xFF) * factor));
                dst[ai + 2] = (byte) clamp255((int) ((albedoRGBA[ai + 2] & 0xFF) * factor));
                dst[ai + 3] = (byte) (factor > 0 ? 255 : 0);
            }
    }

    /**
     * Pre-computes a flat AO byte table from a LabPBR {@code _n} image.
     * AO is stored in the blue channel (byte index 2 of the ABGR layout
     * returned by {@link NativeImagePixels#abgr}, i.e. shift 16).
     */
    public static byte[] buildAOTable(NativeImage n) {
        int w = n.getWidth(), h = n.getHeight();
        byte[] table = new byte[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                table[y * w + x] = (byte) ((NativeImagePixels.abgr(n, x, y) >> 16) & 0xFF);
        return table;
    }

    /**
     * Pre-computes a flat alpha byte table from a LabPBR {@code _s} image.
     * The emission mask lives in the alpha channel (shift 24).
     */
    public static byte[] buildSAlphaTable(NativeImage s) {
        int w = s.getWidth(), h = s.getHeight();
        byte[] table = new byte[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                table[y * w + x] = (byte) ((NativeImagePixels.abgr(s, x, y) >> 24) & 0xFF);
        return table;
    }

    /**
     * Writes emission for a sprite from a {@link BuiltinEmission.Mask} over its own UV space,
     * albedo scaled by the mask's factor, for sprites the pack authors no emission for.
     */
    public static void blitEmissiveMask(byte[] dst, int atlasW, byte[] albedoRGBA,
                                         int ox, int oy, int w, int h, BuiltinEmission.Mask mask) {
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                float factor = mask.factor((x + 0.5f) / w, (y + 0.5f) / h);
                int ai = ((oy + y) * atlasW + (ox + x)) * 4;
                dst[ai]     = (byte) clamp255((int) ((albedoRGBA[ai] & 0xFF) * factor));
                dst[ai + 1] = (byte) clamp255((int) ((albedoRGBA[ai + 1] & 0xFF) * factor));
                dst[ai + 2] = (byte) clamp255((int) ((albedoRGBA[ai + 2] & 0xFF) * factor));
                dst[ai + 3] = (byte) (factor > 0 ? 255 : 0);
            }
    }

    /**
     * Scales the emission an {@code _s} alpha table encodes by {@code gain} in place: alpha
     * 255 stays "no emission", every other value is multiplied and capped at 254 (factor 1).
     * The animated-frame counterpart of the gain in {@link #blitEmissive}.
     */
    public static byte[] applyEmissionGain(byte[] sAlphaTable, float gain) {
        if (sAlphaTable == null || gain == 1.0f) return sAlphaTable;
        for (int i = 0; i < sAlphaTable.length; i++) {
            int alpha = sAlphaTable[i] & 0xFF;
            if (alpha < 255) {
                sAlphaTable[i] = (byte) Math.min(254, Math.round(alpha * gain));
            }
        }
        return sAlphaTable;
    }

    public static byte[] buildSRoughnessTable(NativeImage s) {
        int w = s.getWidth(), h = s.getHeight();
        byte[] table = new byte[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                table[y * w + x] = (byte) smoothnessToRoughness(NativeImagePixels.abgr(s, x, y) & 0xFF);
        return table;
    }

    public static byte[] buildSMetallicTable(NativeImage s) {
        int w = s.getWidth(), h = s.getHeight();
        byte[] table = new byte[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                table[y * w + x] = (byte) f0ToMetallic((NativeImagePixels.abgr(s, x, y) >> 8) & 0xFF);
        return table;
    }

    public static byte[] buildSSssTable(NativeImage s) {
        int w = s.getWidth(), h = s.getHeight();
        byte[] table = new byte[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                table[y * w + x] = (byte) sssIntensity((NativeImagePixels.abgr(s, x, y) >> 16) & 0xFF);
        return table;
    }

    /** True when every animation-frame origin samples the same immutable channel as atlas initialization. */
    static boolean isFrameInvariant(byte[] table, int tableW, int tableH, int frameW, int frameH) {
        if (tableW < 1 || tableH < 1 || frameW < 1 || frameH < 1
                || (long) tableW * tableH != table.length) throw new IllegalArgumentException("Invalid animation table");
        int periodX = gcd(tableW, frameW), periodY = gcd(tableH, frameH);
        if (periodX == tableW && periodY == tableH) return true;
        for (int y = 0; y < tableH; y++) for (int x = 0; x < tableW; x++) {
            if (table[y * tableW + x] != table[(y % periodY) * tableW + x % periodX]) return false;
        }
        return true;
    }

    private static int gcd(int a, int b) {
        while (b != 0) { int remainder = a % b; a = b; b = remainder; }
        return a;
    }

    // --- Resource loading ---

    /**
     * Snapshot of available LabPBR variant resources.
     *
     * <p>Missing {@code _n}/{@code _s} textures are the common case. Querying
     * {@link ResourceManager#getResource(Identifier)} for every possible
     * variant can make each resource pack throw and catch a miss internally,
     * which shows up as a large startup exception burst in JFR. Bulk atlas
     * builders should scan once and load from this map instead.
     */
    public static final class VariantCache {
        private final Map<Identifier, Resource> resources;

        private VariantCache(Map<Identifier, Resource> resources) {
            this.resources = resources;
        }

        public NativeImage loadVariant(Identifier spriteId, String suffix) {
            return loadResource(resources.get(variantId(spriteId, suffix)));
        }

        public int size() {
            return resources.size();
        }
    }

    public static VariantCache buildVariantCache(ResourceManager rm) {
        return new VariantCache(rm.listResources("textures", LabPBRConverter::isVariantResource));
    }

    private static boolean isVariantResource(Identifier id) {
        String path = id.getPath();
        return path.endsWith("_n.png") || path.endsWith("_s.png") || path.endsWith("_sss.png");
    }

    private static NativeImage loadResource(Resource resource) {
        if (resource == null) return null;
        try (InputStream stream = resource.open()) {
            return NativeImage.read(stream);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Identifier variantId(Identifier spriteId, String suffix) {
        return Identifier.fromNamespaceAndPath(spriteId.getNamespace(), "textures/" + spriteId.getPath() + suffix + ".png");
    }

    /**
     * Checks whether any pixel in _s.B has SSS data (value >= 65).
     */
    public static boolean hasSSSData(NativeImage s) {
        for (int y = 0; y < s.getHeight(); y++)
            for (int x = 0; x < s.getWidth(); x++)
                if (((NativeImagePixels.abgr(s, x, y) >> 16) & 0xFF) >= 65) return true;
        return false;
    }

    /**
     * Checks whether any pixel in _s.A has emissive data (value < 255).
     * LabPBR: alpha 0-254 = emission, 255 = no emission.
     */
    public static boolean hasEmissiveData(NativeImage s) {
        for (int y = 0; y < s.getHeight(); y++)
            for (int x = 0; x < s.getWidth(); x++)
                if (((NativeImagePixels.abgr(s, x, y) >> 24) & 0xFF) < 255) return true;
        return false;
    }

    // --- Helpers ---

    private static void blitR8(byte[] dst, int atlasW, NativeImage img,
                               int ox, int oy, int w, int h, int shift) {
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int abgr = NativeImagePixels.abgr(img, x % img.getWidth(), y % img.getHeight());
                dst[(oy + y) * atlasW + (ox + x)] = (byte) ((abgr >> shift) & 0xFF);
            }
    }

    private static int clamp255(int v) { return Math.max(0, Math.min(255, v)); }

}
