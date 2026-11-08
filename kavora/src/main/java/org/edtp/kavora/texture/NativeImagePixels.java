/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.texture;

import com.mojang.blaze3d.platform.NativeImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** CPU pixel conversion from Minecraft ARGB to the Remix RGBA byte order. */
public final class NativeImagePixels {
    public static int abgr(NativeImage image, int x, int y) {
        int argb = image.getPixel(x, y);
        return (argb & 0xff00ff00) | ((argb >>> 16) & 255) | ((argb & 255) << 16);
    }

    /** Copies an animation frame into owned RGBA bytes before the image can be reloaded. */
    static void copyFrame(NativeImage image, int x, int y, int nextX, int nextY,
            int width, int height, float blend, ByteBuffer destination) {
        if (image.isClosed() || image.format() != NativeImage.Format.RGBA) {
            throw new IllegalArgumentException("Animation requires an open RGBA image");
        }
        if (!(blend >= 0 && blend <= 1)) throw new IllegalArgumentException("Invalid animation blend");
        java.util.Objects.checkFromIndexSize(x, width, image.getWidth());
        java.util.Objects.checkFromIndexSize(y, height, image.getHeight());
        if (blend != 0) {
            java.util.Objects.checkFromIndexSize(nextX, width, image.getWidth());
            java.util.Objects.checkFromIndexSize(nextY, height, image.getHeight());
        }
        int rowBytes = Math.multiplyExact(width, 4);
        if (destination.remaining() < Math.multiplyExact(rowBytes, height)) throw new java.nio.BufferOverflowException();
        ByteBuffer source = image.getPixelBytes().order(ByteOrder.LITTLE_ENDIAN);
        int stride = image.getWidth() * 4;
        if (blend == 0 || blend == 1) {
            int start = (blend == 0 ? y * stride + x * 4 : nextY * stride + nextX * 4);
            for (int row = 0; row < height; row++) {
                int position = destination.position();
                destination.put(position, source, start + row * stride, rowBytes);
                destination.position(position + rowBytes);
            }
            return;
        }
        float currentWeight = 1 - blend;
        for (int row = 0; row < height; row++) {
            int currentStart = (y + row) * stride + x * 4;
            int nextStart = (nextY + row) * stride + nextX * 4;
            for (int column = 0; column < rowBytes; column += 4) {
                int current = source.getInt(currentStart + column);
                int next = source.getInt(nextStart + column);
                for (int channel = 0; channel < 4; channel++) {
                    int a = current >>> (channel * 8) & 255;
                    int b = next >>> (channel * 8) & 255;
                    destination.put((byte) Math.round(a * currentWeight + b * blend));
                }
            }
        }
    }
    private NativeImagePixels() {}
}
