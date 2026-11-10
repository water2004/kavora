/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.entity;

import java.util.BitSet;

/** Base-level alpha coverage of a resource-backed entity texture. */
record EntityCutoutCoverage(int width, int height, BitSet occupied) {
    private static final int[] EMPTY_FACE = new int[0];
    private static final int MAX_FACE_TEXELS = 4096;
    private static final int MAX_FACE_RECTS = 32;

    static EntityCutoutCoverage fromRgba(byte[] pixels, int width, int height) {
        BitSet occupied = new BitSet(width * height);
        for (int i = 0; i < width * height; i++) {
            if (pixels[i * 4 + 3] != 0) occupied.set(i);
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

    /** Covered rectangles in source texels, or null to keep the original face. */
    int[] faceRectangles(float minU, float minV, float maxU, float maxV, boolean split) {
        if (!hasCoverage(minU, minV, maxU, maxV)) return EMPTY_FACE;
        if (!split) return null;

        int x0 = texelEdge(minU, width), x1 = texelEdge(maxU, width);
        int y0 = texelEdge(minV, height), y1 = texelEdge(maxV, height);
        if (x0 < 0 || y0 < 0 || x1 <= x0 || y1 <= y0) return null;
        int w = x1 - x0, h = y1 - y0;
        if ((long) w * h > MAX_FACE_TEXELS) return null;

        boolean hasHole = false;
        for (int y = y0; y < y1 && !hasHole; y++) {
            hasHole = occupied.nextClearBit(y * width + x0) < y * width + x1;
        }
        if (!hasHole) return null;

        boolean[] mask = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            int row = (y0 + y) * width + x0;
            for (int x = 0; x < w; x++) mask[y * w + x] = occupied.get(row + x);
        }
        int[] rects = KavoraCapturingConsumer.opaqueRects(mask, w, h, MAX_FACE_RECTS);
        if (rects == null) return null;
        for (int i = 0; i < rects.length; i += 4) {
            rects[i] += x0;
            rects[i + 1] += y0;
            rects[i + 2] += x0;
            rects[i + 3] += y0;
        }
        return rects;
    }

    private static int texelEdge(float uv, int size) {
        float position = uv * size;
        int edge = Math.round(position);
        return uv >= 0 && uv <= 1 && Math.abs(position - edge) < 1e-4f ? edge : -1;
    }
}
