/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.texture;

import com.mojang.blaze3d.platform.NativeImage;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LabPBRConverterTest {

    @Test
    void roughnessAtlasAndAnimationTablesMatchRemixPerceptualEncoding() {
        try (NativeImage source = new NativeImage(16, 16, false)) {
            for (int s = 0; s < 256; s++) source.setPixel(s % 16, s / 16, 0xFF000000 | (s << 16));
            byte[] atlas = new byte[18 * 18];
            java.util.Arrays.fill(atlas, (byte) 37);
            LabPBRConverter.blitRoughness(atlas, 18, source, 1, 1, 16, 16);
            byte[] animation = LabPBRConverter.buildSRoughnessTable(source);
            for (int s = 0; s < 256; s++) {
                int roughness = atlas[(s / 16 + 1) * 18 + s % 16 + 1] & 255;
                assertEquals(255 - s, roughness);
                assertEquals(roughness, animation[s] & 255);
                double perceptual = roughness / 255.0;
                assertEquals(Math.pow(1 - s / 255.0, 2), perceptual * perceptual, 1e-12);
            }
            assertEquals(37, atlas[0]);
            assertEquals(37, atlas[17 * 18 + 17]);
        }
    }

    @Test
    void animatedRoughnessInterpolatesInTheSameDomainAsTheStaticAtlas() {
        try (NativeImage source = new NativeImage(1, 2, false)) {
            source.setPixel(0, 0, 0xFF400000);
            source.setPixel(0, 1, 0xFFC00000);
            byte[] animation = LabPBRConverter.buildSRoughnessTable(source);
            byte[] pixel = new byte[1];
            AnimatedTextureReference.buildR8Patch(pixel, 1, animation, 1, 2, 0, 0, 0, 1, .5f, 1, 1);
            assertEquals(127, pixel[0] & 255);
        }
    }

    @Test
    void singleChannelThicknessPreservesEveryLabPbrValueAndAnimationBlend() {
        try (NativeImage source = new NativeImage(16, 16, false)) {
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
                source.setPixel(x, y, 0xFF000000 | (y * 16 + x));
            }
            int w = 35, h = 21;
            byte[] rgba = new byte[w * h * 4], r8 = new byte[w * h];
            LabPBRConverter.blitSSS(rgba, w, source, 1, 2, 32, 18);
            LabPBRConverter.blitSssThickness(r8, w, source, 1, 2, 32, 18);
            for (int i = 0; i < r8.length; i++) assertEquals(rgba[i * 4], r8[i]);

            byte[] table = LabPBRConverter.buildSSssTable(source);
            byte[] patch = new byte[w * h];
            for (float blend : new float[]{0, .13f, .5f, .999f, 1}) {
                java.util.Arrays.fill(patch, (byte) 73);
                AnimatedTextureReference.buildR8Patch(patch, w, table, 16, 16,
                        3, 5, 11, 13, blend, 31, h);
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                    if (x >= 31) assertEquals((byte) 73, patch[y * w + x]);
                    else {
                        int a = table[(y + 5) % 16 * 16 + (x + 3) % 16] & 255;
                        int b = table[(y + 13) % 16 * 16 + (x + 11) % 16] & 255;
                        assertEquals((byte) Math.round(a * (1 - blend) + b * blend), patch[y * w + x]);
                    }
                }
            }
        }
    }

    @Test
    void invariantAnimationChannelsEqualTheirInitializedPixelsAtEveryFrameAndBlend() {
        for (int[] dimensions : new int[][] {
                {16, 64, 16, 16, 16, 16}, {6, 10, 4, 6, 2, 2},
                {16, 16, 32, 64, 16, 16}, {32, 48, 16, 16, 16, 16}}) {
            int w = dimensions[0], h = dimensions[1], fw = dimensions[2], fh = dimensions[3];
            byte[] table = new byte[w * h];
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                table[y * w + x] = (byte) ((x % dimensions[4]) * 31 + (y % dimensions[5]) * 47);
            }
            assertTrue(LabPBRConverter.isFrameInvariant(table, w, h, fw, fh));
            byte[] expected = new byte[fw * fh], actual = new byte[expected.length];
            AnimatedTextureReference.buildR8Patch(expected, fw, table, w, h, 0, 0, 0, 0, 0, fw, fh);
            for (int frame = 0; frame < 8; frame++) for (float blend : new float[] {0, .13f, .5f, .999f, 1}) {
                AnimatedTextureReference.buildR8Patch(actual, fw, table, w, h,
                        frame * fw, frame / 2 * fh, (frame + 3) * fw, (frame + 1) * fh, blend, fw, fh);
                org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual);
            }
        }
    }

    @Test
    void genuinelyAnimatedChannelsKeepTheirUpdatesIndependently() {
        byte[] constant = new byte[16 * 32], moving = constant.clone();
        moving[16 * 20 + 3] = (byte) 255;
        org.junit.jupiter.api.Assertions.assertFalse(LabPBRConverter.isFrameInvariant(moving, 16, 32, 16, 16));
        var channels = new BlockTextureManager.AnimatedSpriteLabPBR(null, 0, 0,
                null, constant, moving, constant, 16, 32, false, true, 16, 16);
        org.junit.jupiter.api.Assertions.assertFalse(channels.animatedRoughness);
        assertTrue(channels.animatedMetallic);
        org.junit.jupiter.api.Assertions.assertFalse(channels.animatedSss);
    }

    @Test
    void applyAoToPatchSamplesTheAoTableAtTheUploadSourceOffset() {
        ByteBuffer src = ByteBuffer.allocate(8);
        putPixel(src, 0, 100, 150, 200, 250);
        putPixel(src, 1, 64, 128, 255, 255);

        byte[] dst = new byte[8];
        byte[] aoTable = bytes(255, 128, 64, 0);

        AnimatedTextureReference.applyAOToPatch(dst, 8, src, 8, aoTable, 4, 1, 2, 0, 2, 1);

        assertEquals(25, unsigned(dst[0]));
        assertEquals(37, unsigned(dst[1]));
        assertEquals(50, unsigned(dst[2]));
        assertEquals(250, unsigned(dst[3]));
        assertEquals(0, unsigned(dst[4]));
        assertEquals(0, unsigned(dst[5]));
        assertEquals(0, unsigned(dst[6]));
        assertEquals(255, unsigned(dst[7]));
    }

    @Test
    void buildEmissivePatchSamplesTheAlphaTableAtTheUploadSourceOffset() {
        ByteBuffer src = ByteBuffer.allocate(8);
        putPixel(src, 0, 100, 100, 100, 255);
        putPixel(src, 1, 200, 50, 20, 255);

        byte[] dst = new byte[8];
        byte[] sAlphaTable = bytes(255, 0, 127, 255);

        boolean anyEmission = AnimatedTextureReference.buildEmissivePatch(
            dst, 8, src, 8, sAlphaTable, 4, 1, 1, 0, 2, 1, 1.0f);

        assertTrue(anyEmission);
        assertEquals(0, unsigned(dst[0]));
        assertEquals(0, unsigned(dst[1]));
        assertEquals(0, unsigned(dst[2]));
        assertEquals(0, unsigned(dst[3]));
        assertEquals(100, unsigned(dst[4]));
        assertEquals(25, unsigned(dst[5]));
        assertEquals(10, unsigned(dst[6]));
        assertEquals(255, unsigned(dst[7]));
    }

    @Test
    void buildEmissiveMaskPatchScalesFrameAlbedoByTheMaskAndMarksEmittingTexels() {
        // A 2x1 frame whose mask lights only the right half at 0.5: the left texel must
        // come out fully dark with alpha 0, the right one at half its albedo with alpha 255.
        ByteBuffer src = ByteBuffer.allocate(8);
        putPixel(src, 0, 100, 100, 100, 255);
        putPixel(src, 1, 200, 50, 20, 255);
        byte[] dst = new byte[8];

        AnimatedTextureReference.buildEmissiveMaskPatch(dst, 8, src, 8, 2, 1, (u, v) -> u > 0.5f ? 0.5f : 0f);

        assertEquals(0, unsigned(dst[0]));
        assertEquals(0, unsigned(dst[3]));
        assertEquals(100, unsigned(dst[4]));
        assertEquals(25, unsigned(dst[5]));
        assertEquals(10, unsigned(dst[6]));
        assertEquals(255, unsigned(dst[7]));
    }

    @Test
    void authoredEmissionMatchesAnimationWithoutAnExtraGain() {
        try (NativeImage source = new NativeImage(16, 16, false)) {
            byte[] albedo = new byte[256 * 4];
            for (int i = 0; i < 256; i++) {
                source.setPixel(i % 16, i / 16, i << 24);
                albedo[i * 4] = (byte) 200;
                albedo[i * 4 + 1] = (byte) 100;
                albedo[i * 4 + 2] = (byte) 50;
                albedo[i * 4 + 3] = (byte) 255;
            }
            byte[] atlas = new byte[albedo.length], animation = new byte[albedo.length];
            LabPBRConverter.blitEmissive(atlas, 16, albedo, source, 0, 0, 16, 16, 0);
            AnimatedTextureReference.buildEmissivePatch(animation, 64, ByteBuffer.wrap(albedo), 64,
                    LabPBRConverter.buildSAlphaTable(source), 16, 16, 0, 0, 16, 16, 0);
            org.junit.jupiter.api.Assertions.assertArrayEquals(atlas, animation);
            assertEquals(78, atlas[100 * 4] & 255);
            assertEquals(0, atlas[255 * 4] & 255);
        }
    }

    private static void putPixel(ByteBuffer buffer, int pixelIndex, int r, int g, int b, int a) {
        int offset = pixelIndex * 4;
        buffer.put(offset, (byte) r);
        buffer.put(offset + 1, (byte) g);
        buffer.put(offset + 2, (byte) b);
        buffer.put(offset + 3, (byte) a);
    }

    private static byte[] bytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return bytes;
    }

    private static int unsigned(byte value) {
        return value & 0xFF;
    }
}
