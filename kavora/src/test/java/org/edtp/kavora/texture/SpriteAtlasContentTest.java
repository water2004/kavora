/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.texture;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import java.nio.ByteBuffer;

class SpriteAtlasContentTest {
    @Test
    void guttersRepeatTheSpriteEdgeWithoutTouchingAdjacentSlots() {
        for (int channels : new int[] {1, 4}) {
            byte[] pixels = new byte[8 * 8 * channels];
            for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) {
                for (int c = 0; c < channels; c++) pixels[((y + 2) * 8 + x + 2) * channels + c] = (byte) (1 + y * 2 + x);
            }
            SpriteAtlasContent.extrudePadding(pixels, 8, 2, 2, 2, 2, 1, 1, channels);
            for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
                int expected = x >= 1 && x < 5 && y >= 1 && y < 5
                        ? 1 + Math.clamp(y - 2, 0, 1) * 2 + Math.clamp(x - 2, 0, 1) : 0;
                for (int c = 0; c < channels; c++) assertEquals(expected, pixels[(y * 8 + x) * channels + c]);
            }
        }
    }

    @Test
    void animationGuttersFollowEveryFrameAndRespectSourcePitch() {
        ByteBuffer source = ByteBuffer.allocate(16);
        ByteBuffer padded = ByteBuffer.allocate(16);
        source.position(3);
        for (int base : new int[] {10, 20}) {
            source.put(3, (byte) (base + 1)).put(4, (byte) (base + 2));
            source.put(8, (byte) (base + 3)).put(9, (byte) (base + 4));
            padded.clear();
            SpriteAtlasContent.copyPadded(source, 2, 2, 5, 1, 1, 1, padded);
            byte a = (byte) (base + 1), b = (byte) (base + 2), c = (byte) (base + 3), d = (byte) (base + 4);
            assertArrayEquals(new byte[] {a, a, b, b, a, a, b, b, c, c, d, d, c, c, d, d}, padded.array());
            assertEquals(3, source.position());
        }
    }

    @Test
    void bulkPatchCopiesPreserveEveryByteAcrossPitchesGuttersAndBufferOrders() {
        var random = new java.util.Random(263);
        for (int channels : new int[] {1, 4}) for (int width : new int[] {1, 7}) {
            for (int height : new int[] {1, 5}) for (int padX : new int[] {0, 1, 3}) {
                for (int padY : new int[] {0, 2}) {
                    int pitch = width * channels + 7;
                    byte[] input = new byte[height * pitch];
                    random.nextBytes(input);
                    ByteBuffer source = ByteBuffer.allocateDirect(input.length + 5).order(java.nio.ByteOrder.BIG_ENDIAN);
                    source.position(5).put(input).position(5);
                    int bytes = (width + 2 * padX) * (height + 2 * padY) * channels;
                    byte[] expected = new byte[bytes];
                    int output = 0;
                    for (int y = -padY; y < height + padY; y++) for (int x = -padX; x < width + padX; x++) {
                        int pixel = Math.clamp(y, 0, height - 1) * pitch + Math.clamp(x, 0, width - 1) * channels;
                        for (int c = 0; c < channels; c++) expected[output++] = input[pixel + c];
                    }
                    for (boolean arraySource : new boolean[] {false, true}) {
                        ByteBuffer destination = ByteBuffer.allocateDirect(bytes + 11).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                        for (int i = 0; i < destination.capacity(); i++) destination.put(i, (byte) 0x5a);
                        destination.position(3);
                        if (arraySource) SpriteAtlasContent.copyPadded(input, width, height, pitch, channels, padX, padY, destination);
                        else SpriteAtlasContent.copyPadded(source, width, height, pitch, channels, padX, padY, destination);
                        assertEquals(bytes + 3, destination.position());
                        byte[] actual = new byte[bytes];
                        destination.get(3, actual);
                        assertArrayEquals(expected, actual);
                        assertEquals(5, source.position());
                        for (int i = 0; i < 3; i++) assertEquals((byte) 0x5a, destination.get(i));
                        for (int i = bytes + 3; i < destination.capacity(); i++) assertEquals((byte) 0x5a, destination.get(i));
                    }
                }
            }
        }
    }

    @Test
    void contentOriginsMatchMinecraftUvsWithAndWithoutPadding() {
        Identifier id = Identifier.withDefaultNamespace("block/grass_block_top");
        Identifier atlas = Identifier.withDefaultNamespace("textures/atlas/blocks.png");
        for (int atlasWidth : new int[] {256, 1024, 8192}) {
            for (int padding : new int[] {0, 2, 4, 8}) {
                NativeImage image = new NativeImage(16, 8, false);
                image.fillRect(0, 0, 16, 8, 0xff80aa30);
                try (SpriteContents contents = new SpriteContents(id, new FrameSize(16, 8), image)) {
                    TextureAtlasSprite sprite = new TextureAtlasSprite(atlas, contents,
                            atlasWidth, 512, 32, 64, padding) {};
                    assertEquals(32, sprite.getX());
                    assertEquals(64, sprite.getY());
                    assertEquals(32 + padding, SpriteAtlasContent.x(sprite));
                    assertEquals(64 + padding, SpriteAtlasContent.y(sprite));
                }
            }
        }
    }
}
