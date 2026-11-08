/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.texture;

import com.mojang.blaze3d.platform.NativeImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeImagePixelsTest {
    @Test
    void animationCopiesMatchTheVanillaPixelConversionAndOriginalInterpolationExactly() {
        try (NativeImage image = new NativeImage(13, 17, false)) {
            var random = new java.util.Random(263);
            for (int y = 0; y < 17; y++) for (int x = 0; x < 13; x++) image.setPixel(x, y, random.nextInt());
            for (float blend : new float[] {0, .01f, .1f, .25f, .5f, .99f, 1}) {
                for (ByteOrder order : new ByteOrder[] {ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN}) {
                    ByteBuffer result = ByteBuffer.allocateDirect(7 * 5 * 4 + 11).order(order);
                    for (int i = 0; i < result.capacity(); i++) result.put(i, (byte) 73);
                    result.position(3);
                    NativeImagePixels.copyFrame(image, 1, 2, 6, 12, 7, 5, blend, result);
                    assertEquals(3 + 7 * 5 * 4, result.position());
                    assertSame(order, result.order());
                    int index = 3;
                    for (int y = 0; y < 5; y++) for (int x = 0; x < 7; x++) {
                        int a = NativeImagePixels.abgr(image, 1 + x, 2 + y);
                        int b = NativeImagePixels.abgr(image, 6 + x, 12 + y);
                        for (int c = 0; c < 4; c++) {
                            int expected = Math.round((a >>> (c * 8) & 255) * (1f - blend) + (b >>> (c * 8) & 255) * blend);
                            assertEquals((byte) expected, result.get(index++), "blend=" + blend);
                        }
                    }
                    for (int i = 0; i < 3; i++) assertEquals(73, result.get(i));
                    for (int i = index; i < result.capacity(); i++) assertEquals(73, result.get(i));
                }
            }
        }
    }

    @Test
    void invalidFramesFailBeforeReadingNativeMemoryOrWritingOutput() {
        try (NativeImage image = new NativeImage(4, 4, false)) {
            ByteBuffer output = ByteBuffer.allocate(64);
            assertThrows(IndexOutOfBoundsException.class, () -> NativeImagePixels.copyFrame(image, 3, 0, 0, 0, 2, 2, 0, output));
            assertThrows(IndexOutOfBoundsException.class, () -> NativeImagePixels.copyFrame(image, 0, 0, 3, 0, 2, 2, .5f, output));
            assertThrows(IllegalArgumentException.class, () -> NativeImagePixels.copyFrame(image, 0, 0, 0, 0, 2, 2, Float.NaN, output));
            assertThrows(java.nio.BufferOverflowException.class, () -> NativeImagePixels.copyFrame(image, 0, 0, 0, 0, 4, 4, 0, ByteBuffer.allocate(63)));
            assertEquals(0, output.position());
        }
        NativeImage closed = new NativeImage(1, 1, false);
        closed.close();
        assertThrows(IllegalArgumentException.class, () -> NativeImagePixels.copyFrame(closed, 0, 0, 0, 0, 1, 1, 0, ByteBuffer.allocate(4)));
    }
}
