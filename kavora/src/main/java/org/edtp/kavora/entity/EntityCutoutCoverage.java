/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.entity;

import java.util.BitSet;
import java.util.function.IntBinaryOperator;

/** Base-level alpha coverage of a resource-backed entity texture. */
record EntityCutoutCoverage(int width, int height, BitSet occupied) {
    static EntityCutoutCoverage fromRgba(byte[] pixels, int width, int height) {
        return fromAlpha(width, height, (x, y) -> pixels[(y * width + x) * 4 + 3]);
    }

    static EntityCutoutCoverage fromAlpha(int width, int height, IntBinaryOperator alphaAt) {
        BitSet occupied = new BitSet(width * height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (alphaAt.applyAsInt(x, y) != 0) occupied.set(y * width + x);
            }
        }
        return occupied.cardinality() == width * height ? null : new EntityCutoutCoverage(width, height, occupied);
    }

    boolean hasCoverage(float minU, float minV, float maxU, float maxV) {
        // Wrapped or degenerate UVs can sample outside this rectangle.
        if (!(minU >= 0 && minV >= 0 && maxU <= 1 && maxV <= 1 && minU < maxU && minV < maxV)) return true;
        int x0 = (int) Math.floor(minU * width), x1 = (int) Math.ceil(maxU * width);
        int y0 = (int) Math.floor(minV * height), y1 = (int) Math.ceil(maxV * height);
        int pixel = occupied.nextSetBit(y0 * width + x0);
        for (int y = y0; y < y1 && pixel >= 0; y++) {
            int row = y * width;
            if (pixel < row + x0) pixel = occupied.nextSetBit(row + x0);
            if (pixel >= 0 && pixel < row + x1) return true;
        }
        return false;
    }
}
