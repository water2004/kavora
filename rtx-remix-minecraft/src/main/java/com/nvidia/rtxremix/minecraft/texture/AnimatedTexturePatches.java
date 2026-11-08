/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.nvidia.rtxremix.TexturePatch;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Composes the animated material channels together, directly into their final upload buffers. */
final class AnimatedTexturePatches {
    private AnimatedTexturePatches() {}

    /** Source pixels are RGBA8 at offset zero; optional output patches may be null. */
    static void compose(ByteBuffer source, int sourcePitch, int width, int height,
            BlockTextureManager.AnimatedSpriteLabPBR pbr,
            int currentX, int currentY, int nextX, int nextY, float blend,
            BuiltinEmission.Mask mask, int paddingX, int paddingY,
            TexturePatch albedo, TexturePatch emissive, TexturePatch roughness,
            TexturePatch metallic, TexturePatch thickness) {
        byte[] ao = pbr == null ? null : pbr.aoTable;
        byte[] emission = emissive != null && pbr != null && pbr.hasPerPixelEmissive ? pbr.sAlphaTable : null;
        byte[] rough = roughness == null ? null : pbr.sRoughnessTable;
        byte[] metal = metallic == null ? null : pbr.sMetallicTable;
        byte[] sss = thickness == null ? null : pbr.sSssTable;
        boolean sampleS = emission != null || rough != null || metal != null || sss != null;
        int sw = sampleS ? pbr.sW : 1, sh = sampleS ? pbr.sH : 1;
        int aw = ao == null ? 1 : pbr.aoW, ah = ao == null ? 1 : pbr.aoH;
        boolean sourceLittleEndian = source.order() == ByteOrder.LITTLE_ENDIAN;
        boolean albedoLittleEndian = albedo.pixelData.order() == ByteOrder.LITTLE_ENDIAN;
        boolean emissiveLittleEndian = emissive != null && emissive.pixelData.order() == ByteOrder.LITTLE_ENDIAN;
        float b = Math.clamp(blend, 0f, 1f), a = 1f - b;
        for (int y = 0; y < height; y++) {
            int ar = ((currentY + y) % ah) * aw, ax = currentX % aw;
            int sr = ((currentY + y) % sh) * sw, sx = currentX % sw;
            int nr = ((nextY + y) % sh) * sw, nx = nextX % sw;
            int si = y * sourcePitch;
            int ai = (y + paddingY) * albedo.rowPitch + paddingX * 4;
            int ei = emissive == null ? 0 : (y + paddingY) * emissive.rowPitch + paddingX * 4;
            int ri = roughness == null ? 0 : (y + paddingY) * roughness.rowPitch + paddingX;
            int mi = metallic == null ? 0 : (y + paddingY) * metallic.rowPitch + paddingX;
            int ti = thickness == null ? 0 : (y + paddingY) * thickness.rowPitch + paddingX;
            for (int x = 0; x < width; x++, si += 4, ai += 4, ei += 4, ri++, mi++, ti++) {
                int rgba = source.getInt(si);
                if (!sourceLittleEndian) rgba = Integer.reverseBytes(rgba);
                int r = rgba & 255, g = (rgba >>> 8) & 255, blue = (rgba >>> 16) & 255;
                int occlusion = ao == null ? 255 : ao[ar + ax] & 255;
                int albedoRgba = occlusion == 255 ? rgba : (rgba & 0xff000000)
                        | (r * occlusion / 255) | (g * occlusion / 255) << 8 | (blue * occlusion / 255) << 16;
                albedo.pixelData.putInt(ai, albedoLittleEndian ? albedoRgba : Integer.reverseBytes(albedoRgba));
                int current = sr + sx, next = nr + nx;
                if (rough != null) roughness.pixelData.put(ri, sample(rough, current, next, a, b));
                if (metal != null) metallic.pixelData.put(mi, sample(metal, current, next, a, b));
                if (sss != null) thickness.pixelData.put(ti, sample(sss, current, next, a, b));
                if (emissive != null) {
                    float factor;
                    if (emission != null) {
                        factor = emissionFactor(emission[current] & 255);
                        if (b > 0f) factor = factor * a + emissionFactor(emission[next] & 255) * b;
                    } else {
                        factor = mask.factor((x + .5f) / width, (y + .5f) / height);
                    }
                    // Emission uses the unoccluded albedo, including Minecraft's frame interpolation.
                    int emissiveRgba = Math.clamp((int) (r * factor), 0, 255)
                            | Math.clamp((int) (g * factor), 0, 255) << 8
                            | Math.clamp((int) (blue * factor), 0, 255) << 16
                            | (factor > 0f ? 0xff000000 : 0);
                    emissive.pixelData.putInt(ei, emissiveLittleEndian ? emissiveRgba : Integer.reverseBytes(emissiveRgba));
                }
                if (++ax == aw) ax = 0;
                if (++sx == sw) sx = 0;
                if (++nx == sw) nx = 0;
            }
        }
        finish(albedo, width, height, paddingX, paddingY, 4);
        finish(emissive, width, height, paddingX, paddingY, 4);
        finish(roughness, width, height, paddingX, paddingY, 1);
        finish(metallic, width, height, paddingX, paddingY, 1);
        finish(thickness, width, height, paddingX, paddingY, 1);
    }

    private static byte sample(byte[] table, int current, int next, float a, float b) {
        int value = table[current] & 255;
        return b > 0f ? (byte) Math.round(value * a + (table[next] & 255) * b) : (byte) value;
    }

    private static float emissionFactor(int alpha) {
        return alpha < 255 ? alpha / 254f : 0f;
    }

    private static void finish(TexturePatch patch, int width, int height, int px, int py, int channels) {
        if (patch == null) return;
        ByteBuffer pixels = patch.pixelData;
        for (int y = py; y < py + height; y++) {
            int row = y * patch.rowPitch;
            for (int x = 0; x < px; x++) {
                pixels.put(row + x * channels, pixels, row + px * channels, channels);
                pixels.put(row + (px + width + x) * channels, pixels, row + (px + width - 1) * channels, channels);
            }
        }
        for (int y = 0; y < py; y++) {
            pixels.put(y * patch.rowPitch, pixels, py * patch.rowPitch, patch.rowPitch);
            pixels.put((py + height + y) * patch.rowPitch, pixels, (py + height - 1) * patch.rowPitch, patch.rowPitch);
        }
        pixels.limit(patch.rowPitch * patch.height).position(0);
    }
}
