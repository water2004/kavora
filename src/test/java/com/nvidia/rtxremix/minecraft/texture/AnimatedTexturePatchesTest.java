/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.nvidia.rtxremix.TexturePatch;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AnimatedTexturePatchesTest {
    @Test
    void matchesSeparateChannelConversionIncludingInterpolationWrappingAndPadding() {
        Random random = new Random(78412);
        for (int variant = 0; variant < 32; variant++) {
            int w = 1 + random.nextInt(17), h = 1 + random.nextInt(15);
            int aw = 1 + random.nextInt(13), ah = 1 + random.nextInt(19);
            int sw = 1 + random.nextInt(11), sh = 1 + random.nextInt(23);
            int px = random.nextInt(4), py = random.nextInt(4), pitch = w * 4 + 12;
            byte[] raw = bytes(random, pitch * h);
            ByteBuffer source = ByteBuffer.allocateDirect(raw.length).put(raw).flip();
            source.order((variant & 1) == 0 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
            byte[] ao = (variant & 1) == 0 ? bytes(random, aw * ah) : null;
            byte[] alpha = bytes(random, sw * sh);
            alpha[0] = (byte) 255;
            alpha[alpha.length - 1] = (byte) 254;
            var pbr = new BlockTextureManager.AnimatedSpriteLabPBR(ao, aw, ah, alpha,
                    bytes(random, sw * sh), bytes(random, sw * sh), bytes(random, sw * sh),
                    sw, sh, true, true, w, h);
            int cx = random.nextInt(29), cy = random.nextInt(31), nx = random.nextInt(37), ny = random.nextInt(41);
            for (float blend : new float[]{0f, 1f, .5f, .137f, -.2f, 1.2f}) {
                TexturePatch a = patch(w, h, px, py, 4), e = patch(w, h, px, py, 4);
                a.pixelData.order((variant & 2) == 0 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
                e.pixelData.order((variant & 4) == 0 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
                TexturePatch r = patch(w, h, px, py, 1), m = patch(w, h, px, py, 1), t = patch(w, h, px, py, 1);
                AnimatedTexturePatches.compose(source, pitch, w, h, pbr, cx, cy, nx, ny, blend,
                        null, px, py, a, e, r, m, t);
                byte[] converted = new byte[w * h * 4];
                if (ao != null) AnimatedTextureReference.applyAOToPatch(converted, w * 4, source, pitch, ao, aw, ah, cx, cy, w, h);
                else AnimatedTextureReference.copyAlbedoPatch(converted, w * 4, source, pitch, w, h);
                assertPixels(converted, w, h, 4, px, py, a);
                AnimatedTextureReference.buildEmissivePatch(converted, w * 4, source, pitch, alpha, sw, sh,
                        cx, cy, nx, ny, blend, w, h, 0f);
                assertPixels(converted, w, h, 4, px, py, e);
                byte[][] tables = {pbr.sRoughnessTable, pbr.sMetallicTable, pbr.sSssTable};
                TexturePatch[] outputs = {r, m, t};
                byte[] values = new byte[w * h];
                for (int channel = 0; channel < 3; channel++) {
                    AnimatedTextureReference.buildR8Patch(values, w, tables[channel], sw, sh, cx, cy, nx, ny, blend, w, h);
                    assertPixels(values, w, h, 1, px, py, outputs[channel]);
                }
                assertEquals(0, source.position());
                assertArrayEquals(raw, sourceBytes(source));
            }
        }
    }

    @Test
    void builtinMaskAndUnconvertedAlbedoWorkWithoutMaterialSidecars() {
        int w = 7, h = 9, pitch = w * 4;
        byte[] raw = bytes(new Random(542), pitch * h);
        ByteBuffer source = ByteBuffer.wrap(raw);
        TexturePatch a = patch(w, h, 2, 3, 4), e = patch(w, h, 2, 3, 4);
        BuiltinEmission.Mask mask = (u, v) -> u < .3f ? 0f : v * 2f;
        AnimatedTexturePatches.compose(source, pitch, w, h, null, 0, 0, 4, 3, .5f,
                mask, 2, 3, a, e, null, null, null);
        assertPixels(raw, w, h, 4, 2, 3, a);
        byte[] converted = new byte[raw.length];
        AnimatedTextureReference.buildEmissiveMaskPatch(converted, pitch, source, pitch, w, h, mask);
        assertPixels(converted, w, h, 4, 2, 3, e);
        AnimatedTexturePatches.compose(source, pitch, w, h, null, 0, 0, 0, 0, 0f,
                null, 2, 3, a, null, null, null, null);
        assertPixels(raw, w, h, 4, 2, 3, a);
    }

    private static byte[] bytes(Random random, int count) {
        byte[] values = new byte[count];
        random.nextBytes(values);
        return values;
    }

    private static TexturePatch patch(int w, int h, int px, int py, int channels) {
        TexturePatch patch = new TexturePatch();
        patch.width = w + px * 2;
        patch.height = h + py * 2;
        patch.rowPitch = patch.width * channels;
        patch.pixelData = ByteBuffer.allocateDirect(patch.rowPitch * patch.height);
        return patch;
    }

    private static void assertPixels(byte[] converted, int w, int h, int channels, int px, int py, TexturePatch actual) {
        ByteBuffer expected = ByteBuffer.allocate(actual.rowPitch * actual.height);
        SpriteAtlasContent.copyPadded(converted, w, h, w * channels, channels, px, py, expected);
        assertEquals(0, actual.pixelData.position());
        assertEquals(expected.position(), actual.pixelData.limit());
        assertArrayEquals(expected.array(), sourceBytes(actual.pixelData));
    }

    private static byte[] sourceBytes(ByteBuffer source) {
        byte[] result = new byte[source.limit()];
        source.get(0, result);
        return result;
    }
}
