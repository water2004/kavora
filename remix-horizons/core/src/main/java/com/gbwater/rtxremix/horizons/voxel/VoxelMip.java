/* SPDX-License-Identifier: LGPL-3.0-only */
package com.gbwater.rtxremix.horizons.voxel;

import java.util.Arrays;
import java.util.function.IntUnaryOperator;

/** Representative-material reduction in all three axes, with deterministic opacity precedence. */
public final class VoxelMip {
    private VoxelMip() {}

    public static int[] reduce(VoxelSection source, IntUnaryOperator opacity) {
        int[] result = new int[16 * 16 * 16];
        reduceInto(source, opacity, result);
        return result;
    }

    /** The caller owns the scratch array; every sample is overwritten before it is consumed. */
    static void reduceInto(VoxelSection source, IntUnaryOperator opacity, int[] result) {
        if (source.cells() == VoxelSection.AIR) {
            Arrays.fill(result, 0);
            return;
        }
        int childSize = 1 << VoxelKey.level(source.key());
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) {
                    int chosen = 0, priority = -1, chosenY = 0;
                    // Stable ties retain the upper sample, rather than averaging unrelated block
                    // identities.
                    for (int dy = 0; dy < 2; dy++)
                        for (int dz = 0; dz < 2; dz++)
                            for (int dx = 0; dx < 2; dx++) {
                                int cell = source.cell(x * 2 + dx, y * 2 + dy, z * 2 + dz);
                                int id = VoxelSection.material(cell);
                                if (id == 0) continue;
                                int p = id == VoxelSection.material(chosen)
                                        ? priority : opacity.applyAsInt(id);
                                if (p >= priority) {
                                    priority = p;
                                    chosen = cell;
                                    chosenY = dy;
                                }
                            }
                    if (VoxelSection.fluidTop(chosen) != 0)
                        chosen =
                                VoxelSection.withFluidTop(
                                        VoxelSection.material(chosen),
                                        VoxelSection.fluidTop(chosen) + chosenY * childSize * 9);
                    result[x | z << 4 | y << 8] = chosen;
                }
    }
}
