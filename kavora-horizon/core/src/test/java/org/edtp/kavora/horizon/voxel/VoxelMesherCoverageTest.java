/* SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package org.edtp.kavora.horizon.voxel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class VoxelMesherCoverageTest {
    private static final int[] AXIS = {1, 1, 2, 2, 0, 0};
    private static final VoxelMesher.Occlusion OCCLUSION = new VoxelMesher.Occlusion() {
        public boolean hidden(int a, int b, int side) {
            return b == 1 || (a == 3 && (b == 3 || b == 4))
                    || (a == 2 && b == 2 && (side & 1) == 0);
        }

        public int classify(int material) {
            return (material == 1 ? 1 : 0) | (material == 4 ? 3 << 1 : 0);
        }
    };

    @Test
    void rectanglesExactlyCoverExposedFacesAcrossReusedSparseAndDenseMasks() {
        var mesher = new VoxelMesher();
        var random = new Random(0x7ac092);
        for (int sample = 0; sample < 18; sample++) {
            var neighborhood = new VoxelSection[7];
            for (int section = 0; section < neighborhood.length; section++) {
                if (section != 0 && (sample + section) % 3 == 0) continue;
                int[] cells = new int[VoxelKey.VOLUME];
                for (int i = 0; i < cells.length; i++) {
                    int material = sample % 3 == 0 ? (random.nextInt(32) == 0 ? 2 : 0)
                            : sample % 3 == 1 ? 1 : random.nextInt(5);
                    int height = sample >= 12 ? 2048 + random.nextInt(2000) : 1 + random.nextInt(8);
                    cells[i] = material >= 3 ? VoxelSection.withFluidTop(material, height) : material;
                }
                neighborhood[section] = new VoxelSection(section, sample, 255, 255, cells);
            }

            int[] expected = new int[12 * VoxelKey.VOLUME];
            int[] actual = new int[expected.length];
            for (int side = 0; side < 6; side++) {
                int axis = AXIS[side], sign = (side & 1) == 0 ? -1 : 1;
                for (int y = 0; y < 32; y++)
                    for (int z = 0; z < 32; z++)
                        for (int x = 0; x < 32; x++) {
                            int index = VoxelKey.index(x, y, z);
                            int cell = neighborhood[0].cells()[index];
                            int material = VoxelSection.material(cell);
                            if (material == 0) continue;
                            int nx = x + (axis == 0 ? sign : 0);
                            int ny = y + (axis == 1 ? sign : 0);
                            int nz = z + (axis == 2 ? sign : 0);
                            boolean boundary = nx < 0 || nx >= 32 || ny < 0 || ny >= 32 || nz < 0 || nz >= 32;
                            var neighbor = boundary ? neighborhood[1 + axis * 2 + (side & 1)] : neighborhood[0];
                            int adjacent = neighbor == null ? 0
                                    : VoxelSection.material(neighbor.cell(nx & 31, ny & 31, nz & 31));
                            if (!OCCLUSION.hidden(material, adjacent, side))
                                expected[side * VoxelKey.VOLUME + index] = cell;
                            if (material == 4 && !OCCLUSION.hidden(3, adjacent, side))
                                expected[(6 + side) * VoxelKey.VOLUME + index] =
                                        VoxelSection.withFluidTop(3, VoxelSection.fluidTop(cell));
                        }
            }
            for (long quad : mesher.build(neighborhood, OCCLUSION).quads()) {
                int side = VoxelMesher.side(quad), axis = AXIS[side];
                int s = (axis + 1) % 3, t = (axis + 2) % 3;
                int[] p = {VoxelMesher.x(quad), VoxelMesher.y(quad), VoxelMesher.z(quad)};
                int cell = VoxelMesher.cell(quad);
                for (int v = 0; v < VoxelMesher.height(quad); v++)
                    for (int u = 0; u < VoxelMesher.width(quad); u++) {
                        int x = p[0] + (s == 0 ? u : t == 0 ? v : 0);
                        int y = p[1] + (s == 1 ? u : t == 1 ? v : 0);
                        int z = p[2] + (s == 2 ? u : t == 2 ? v : 0);
                        int index = VoxelKey.index(x, y, z);
                        boolean fluid = VoxelSection.material(cell) == 3
                                && VoxelSection.material(neighborhood[0].cells()[index]) == 4;
                        int slot = ((fluid ? 6 : 0) + side) * VoxelKey.VOLUME + index;
                        assertEquals(0, actual[slot], "A face was emitted twice");
                        actual[slot] = cell;
                    }
            }
            assertArrayEquals(expected, actual, "Exposed faces changed in sample " + sample);
        }
    }
}
