/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import com.nvidia.rtxremix.minecraft.mixin.SpriteAtlasAccessor;
import java.nio.ByteBuffer;

/** Pixel placement of sprite contents, excluding the atlas stitcher's surrounding padding. */
public final class SpriteAtlasContent {
    private SpriteAtlasContent() {}

    public static int x(TextureAtlasSprite sprite) {
        return Math.round(sprite.getU0() / (sprite.getU1() - sprite.getU0()) * sprite.contents().width());
    }

    public static int y(TextureAtlasSprite sprite) {
        return Math.round(sprite.getV0() / (sprite.getV1() - sprite.getV0()) * sprite.contents().height());
    }

    /** Repeats edge pixels into the stitcher's owned gutters before mip generation. */
    public static void extrudePadding(TextureAtlas atlas, byte[] pixels, int channels) {
        SpriteAtlasAccessor layout = (SpriteAtlasAccessor) atlas;
        for (TextureAtlasSprite sprite : layout.getSprites().values()) {
            int x = x(sprite), y = y(sprite);
            extrudePadding(pixels, layout.getWidth(), x, y, sprite.contents().width(), sprite.contents().height(),
                    x - sprite.getX(), y - sprite.getY(), channels);
        }
    }

    static void extrudePadding(byte[] pixels, int atlasWidth, int x, int y, int width, int height,
            int paddingX, int paddingY, int channels) {
        if (paddingX == 0 && paddingY == 0) return;
        for (int dy = -paddingY; dy < height + paddingY; dy++) {
            int sy = Math.clamp(dy, 0, height - 1);
            for (int dx = -paddingX; dx < width + paddingX; dx++) {
                if (dx >= 0 && dx < width && dy >= 0 && dy < height) continue;
                int sx = Math.clamp(dx, 0, width - 1);
                System.arraycopy(pixels, ((y + sy) * atlasWidth + x + sx) * channels,
                        pixels, ((y + dy) * atlasWidth + x + dx) * channels, channels);
            }
        }
    }

    /** Expands one animation patch without changing its unpadded LabPBR sampling coordinates. */
    static void copyPadded(ByteBuffer source, int width, int height, int rowPitch, int channels,
            int paddingX, int paddingY, ByteBuffer destination) {
        int origin = source.position();
        for (int y = -paddingY; y < height + paddingY; y++) {
            int row = origin + Math.clamp(y, 0, height - 1) * rowPitch;
            for (int x = 0; x < paddingX; x++) copy(source, row, channels, destination);
            copy(source, row, width * channels, destination);
            int right = row + (width - 1) * channels;
            for (int x = 0; x < paddingX; x++) copy(source, right, channels, destination);
        }
    }

    private static void copy(ByteBuffer source, int offset, int bytes, ByteBuffer destination) {
        int position = destination.position();
        destination.put(position, source, offset, bytes);
        destination.position(position + bytes);
    }

    /** Copies a converted CPU patch directly into its final native upload buffer. */
    static void copyPadded(byte[] source, int width, int height, int rowPitch, int channels,
            int paddingX, int paddingY, ByteBuffer destination) {
        for (int y = -paddingY; y < height + paddingY; y++) {
            int row = Math.clamp(y, 0, height - 1) * rowPitch;
            for (int x = 0; x < paddingX; x++) destination.put(source, row, channels);
            destination.put(source, row, width * channels);
            int right = row + (width - 1) * channels;
            for (int x = 0; x < paddingX; x++) destination.put(source, right, channels);
        }
    }
}
