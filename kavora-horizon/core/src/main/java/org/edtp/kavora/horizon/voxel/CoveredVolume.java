/* SPDX-License-Identifier: LGPL-3.0-only */
package org.edtp.kavora.horizon.voxel;

import java.nio.ByteBuffer;

/** Summed volume of a published binary coverage grid; box queries allocate nothing. */
public final class CoveredVolume {
    public static final int NONE = 0, PARTIAL = 1, FULL = 2;
    private int width, height, depth, row, plane;
    private int[] sums = new int[0];

    public void update(ByteBuffer cells, int width, int height, int depth) {
        this.width = width;
        this.height = height;
        this.depth = depth;
        row = width + 1;
        plane = row * (height + 1);
        int length = Math.multiplyExact(plane, depth + 1);
        if (sums.length != length) sums = new int[length];
        else java.util.Arrays.fill(sums, 0);
        for (int z = 1; z <= depth; z++)
            for (int y = 1; y <= height; y++) {
                int alongX = 0;
                for (int x = 1; x <= width; x++) {
                    alongX += cells.get(x - 1 + width * (y - 1 + height * (z - 1))) == 0 ? 0 : 1;
                    int i = x + row * y + plane * z;
                    sums[i] = alongX + sums[i - row] + sums[i - plane] - sums[i - row - plane];
                }
            }
    }

    /** Integer cell bounds are half-open. Any part outside the published grid is uncovered. */
    public boolean covers(int x0, int y0, int z0, int x1, int y1, int z1) {
        return classify(x0, y0, z0, x1, y1, z1) == FULL;
    }

    /** Distinguishes boxes needing per-hit clipping from fully covered or disjoint boxes. */
    public int classify(int x0, int y0, int z0, int x1, int y1, int z1) {
        boolean contained = x0 >= 0 && y0 >= 0 && z0 >= 0
                && x1 <= width && y1 <= height && z1 <= depth;
        x0 = Math.max(0, x0);
        y0 = Math.max(0, y0);
        z0 = Math.max(0, z0);
        x1 = Math.min(width, x1);
        y1 = Math.min(height, y1);
        z1 = Math.min(depth, z1);
        if (x0 >= x1 || y0 >= y1 || z0 >= z1) return NONE;
        int count = at(x1, y1, z1) - at(x0, y1, z1) - at(x1, y0, z1) - at(x1, y1, z0)
                + at(x0, y0, z1) + at(x0, y1, z0) + at(x1, y0, z0) - at(x0, y0, z0);
        if (count == 0) return NONE;
        return contained && count == (x1 - x0) * (y1 - y0) * (z1 - z0) ? FULL : PARTIAL;
    }

    private int at(int x, int y, int z) {
        return sums[x + row * y + plane * z];
    }
}
