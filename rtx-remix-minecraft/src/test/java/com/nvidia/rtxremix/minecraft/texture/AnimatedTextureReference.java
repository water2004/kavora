/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import java.nio.ByteBuffer;

/** Scalar channel-by-channel reference for byte-exact animation conversion tests. */
final class AnimatedTextureReference {

    
    public static void applyAOToPatch(
            byte[] dst, int dstRowPitch,
            ByteBuffer src, int srcRowPitch,
            byte[] aoTable, int aoW, int aoH,
            int w, int h) {
        applyAOToPatch(dst, dstRowPitch, src, srcRowPitch, aoTable, aoW, aoH, 0, 0, w, h);
    }

    public static void applyAOToPatch(
            byte[] dst, int dstRowPitch,
            ByteBuffer src, int srcRowPitch,
            byte[] aoTable, int aoW, int aoH,
            int aoX, int aoY,
            int w, int h) {
        for (int y = 0; y < h; y++) {
            int sRow = y * srcRowPitch;
            int dRow = y * dstRowPitch;
            int aoRow = wrapNonNegative(aoY + y, aoH) * aoW;
            for (int x = 0; x < w; x++) {
                int ao = aoTable[aoRow + wrapNonNegative(aoX + x, aoW)] & 0xFF;
                int si = sRow + x * 4;
                int di = dRow + x * 4;
                dst[di]     = (byte) ((src.get(si)     & 0xFF) * ao / 255);
                dst[di + 1] = (byte) ((src.get(si + 1) & 0xFF) * ao / 255);
                dst[di + 2] = (byte) ((src.get(si + 2) & 0xFF) * ao / 255);
                dst[di + 3] = src.get(si + 3);
            }
        }
    }

    
    public static void copyAlbedoPatch(
            byte[] dst, int dstRowPitch,
            ByteBuffer src, int srcRowPitch,
            int w, int h) {
        int rowBytes = w * 4;
        for (int y = 0; y < h; y++) {
            int sOff = y * srcRowPitch;
            int dOff = y * dstRowPitch;
            for (int i = 0; i < rowBytes; i++) {
                dst[dOff + i] = src.get(sOff + i);
            }
        }
    }

    
    public static boolean buildEmissivePatch(
            byte[] dst, int dstRowPitch,
            ByteBuffer src, int srcRowPitch,
            byte[] sAlphaTable, int sW, int sH,
            int w, int h,
            float fallbackFactor) {
        return buildEmissivePatch(dst, dstRowPitch, src, srcRowPitch,
                sAlphaTable, sW, sH, 0, 0, w, h, fallbackFactor);
    }

    public static boolean buildEmissivePatch(
            byte[] dst, int dstRowPitch,
            ByteBuffer src, int srcRowPitch,
            byte[] sAlphaTable, int sW, int sH,
            int maskX, int maskY,
            int w, int h,
            float fallbackFactor) {
        return buildEmissivePatch(dst, dstRowPitch, src, srcRowPitch,
                sAlphaTable, sW, sH, maskX, maskY, maskX, maskY, 0.0f,
                w, h, fallbackFactor);
    }

    public static boolean buildEmissivePatch(
            byte[] dst, int dstRowPitch,
            ByteBuffer src, int srcRowPitch,
            byte[] sAlphaTable, int sW, int sH,
            int maskX, int maskY,
            int nextMaskX, int nextMaskY,
            float nextMaskBlend,
            int w, int h,
            float fallbackFactor) {
        boolean anyEmission = false;
        float nextBlend = Math.max(0.0f, Math.min(1.0f, nextMaskBlend));
        float currentBlend = 1.0f - nextBlend;
        for (int y = 0; y < h; y++) {
            int sRow = y * srcRowPitch;
            int dRow = y * dstRowPitch;
            int saRow = (sAlphaTable != null) ? wrapNonNegative(maskY + y, sH) * sW : 0;
            int nextSaRow = (sAlphaTable != null) ? wrapNonNegative(nextMaskY + y, sH) * sW : 0;
            for (int x = 0; x < w; x++) {
                float factor;
                if (sAlphaTable != null) {
                    int alpha = sAlphaTable[saRow + wrapNonNegative(maskX + x, sW)] & 0xFF;
                    factor = emissiveFactorFromSAlpha(alpha, fallbackFactor);
                    if (nextBlend > 0.0f) {
                        int nextAlpha = sAlphaTable[nextSaRow + wrapNonNegative(nextMaskX + x, sW)] & 0xFF;
                        float nextFactor = emissiveFactorFromSAlpha(nextAlpha, fallbackFactor);
                        factor = factor * currentBlend + nextFactor * nextBlend;
                    }
                } else {
                    factor = fallbackFactor;
                }
                int si = sRow + x * 4;
                int di = dRow + x * 4;
                int r = src.get(si)     & 0xFF;
                int g = src.get(si + 1) & 0xFF;
                int b = src.get(si + 2) & 0xFF;
                dst[di]     = (byte) clamp255((int) (r * factor));
                dst[di + 1] = (byte) clamp255((int) (g * factor));
                dst[di + 2] = (byte) clamp255((int) (b * factor));
                dst[di + 3] = (byte) (factor > 0 ? 255 : 0);
                if (factor > 0) anyEmission = true;
            }
        }
        return anyEmission;
    }

    private static float emissiveFactorFromSAlpha(int alpha, float fallbackFactor) {
        return alpha < 255 ? alpha / 254.0f : fallbackFactor;
    }

    
    public static void buildEmissiveMaskPatch(byte[] dst, int dstRowPitch,
                                              ByteBuffer src, int srcRowPitch,
                                              int w, int h, BuiltinEmission.Mask mask) {
        for (int y = 0; y < h; y++) {
            int sRow = y * srcRowPitch;
            int dRow = y * dstRowPitch;
            for (int x = 0; x < w; x++) {
                float factor = mask.factor((x + 0.5f) / w, (y + 0.5f) / h);
                int si = sRow + x * 4;
                int di = dRow + x * 4;
                dst[di]     = (byte) clamp255((int) ((src.get(si)     & 0xFF) * factor));
                dst[di + 1] = (byte) clamp255((int) ((src.get(si + 1) & 0xFF) * factor));
                dst[di + 2] = (byte) clamp255((int) ((src.get(si + 2) & 0xFF) * factor));
                dst[di + 3] = (byte) (factor > 0 ? 255 : 0);
            }
        }
    }

    public static void buildR8Patch(
            byte[] dst, int dstRowPitch,
            byte[] valueTable, int tableW, int tableH,
            int valueX, int valueY,
            int nextValueX, int nextValueY,
            float nextValueBlend,
            int w, int h) {
        float nextBlend = Math.max(0.0f, Math.min(1.0f, nextValueBlend));
        float currentBlend = 1.0f - nextBlend;
        for (int y = 0; y < h; y++) {
            int dRow = y * dstRowPitch;
            int row = wrapNonNegative(valueY + y, tableH) * tableW;
            int nextRow = wrapNonNegative(nextValueY + y, tableH) * tableW;
            for (int x = 0; x < w; x++) {
                int value = valueTable[row + wrapNonNegative(valueX + x, tableW)] & 0xFF;
                if (nextBlend > 0.0f) {
                    int nextValue = valueTable[nextRow + wrapNonNegative(nextValueX + x, tableW)] & 0xFF;
                    value = Math.round(value * currentBlend + nextValue * nextBlend);
                }
                dst[dRow + x] = (byte) value;
            }
        }
    }

    private static int clamp255(int value) { return Math.clamp(value, 0, 255); }
    private static int wrapNonNegative(int value, int size) { return value % size; }
}
