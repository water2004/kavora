/* SPDX-License-Identifier: LGPL-3.0-only */
package com.gbwater.rtxremix.horizons.voxel;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CoveredVolumeTest {
    @Test
    void everyBoxAgreesWithPublishedCellsAndHoles() {
        var random = new Random(0x761a);
        var grid = new CoveredVolume();
        int width = 7, height = 5, depth = 6;
        var cells = ByteBuffer.allocate(width * height * depth);
        for (int pass = 0; pass < 3; pass++) {
            for (int i = 0; i < cells.capacity(); i++)
                cells.put(i, (byte) (pass == 0 || random.nextInt(20) != 0 ? 1 : 0));
            grid.update(cells, width, height, depth);
            for (int z0 = 0; z0 < depth; z0++)
                for (int y0 = 0; y0 < height; y0++)
                    for (int x0 = 0; x0 < width; x0++)
                        for (int z1 = z0 + 1; z1 <= depth; z1++)
                            for (int y1 = y0 + 1; y1 <= height; y1++)
                                for (int x1 = x0 + 1; x1 <= width; x1++) {
                                    boolean all = true, any = false;
                                    for (int z = z0; z < z1; z++)
                                        for (int y = y0; y < y1; y++)
                                            for (int x = x0; x < x1; x++) {
                                                boolean covered = cells.get(x + width * (y + height * z)) != 0;
                                                all &= covered;
                                                any |= covered;
                                            }
                                    assertEquals(all, grid.covers(x0, y0, z0, x1, y1, z1));
                                    assertEquals(all ? CoveredVolume.FULL : any ? CoveredVolume.PARTIAL : CoveredVolume.NONE,
                                            grid.classify(x0, y0, z0, x1, y1, z1));
                                }
            assertFalse(grid.covers(-1, 0, 0, 1, 1, 1));
            assertFalse(grid.covers(0, 0, 0, width + 1, height, depth));
            assertFalse(grid.covers(0, 0, 0, 0, 1, 1));
        }
        grid.update(ByteBuffer.allocate(1), 1, 1, 1);
        assertFalse(grid.covers(0, 0, 0, 1, 1, 1));
    }

    @Test
    void outsideAndBoundaryBoxesOnlyClipWhenTheyTouchPublishedCells() {
        var grid = new CoveredVolume();
        assertEquals(CoveredVolume.NONE, grid.classify(-10, -10, -10, 10, 10, 10));
        grid.update(ByteBuffer.wrap(new byte[] {1, 0}), 2, 1, 1);
        assertEquals(CoveredVolume.NONE, grid.classify(2, 0, 0, 3, 1, 1));
        assertEquals(CoveredVolume.NONE, grid.classify(1, -1, -1, 3, 2, 2));
        assertEquals(CoveredVolume.NONE, grid.classify(-1, 0, 0, 0, 1, 1));
        assertEquals(CoveredVolume.NONE, grid.classify(0, 0, 0, 0, 1, 1));
        assertEquals(CoveredVolume.FULL, grid.classify(0, 0, 0, 1, 1, 1));
        assertEquals(CoveredVolume.PARTIAL, grid.classify(-1, 0, 0, 1, 1, 1));
        assertEquals(CoveredVolume.PARTIAL, grid.classify(0, 0, 0, 2, 1, 1));
        assertEquals(CoveredVolume.PARTIAL, grid.classify(-1, -1, -1, 3, 2, 2));
    }
}
