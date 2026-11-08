/* SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package org.edtp.kavora.horizon;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ModelFacePatchesTest {
    private static float[] top(float size, float y) {
        return new float[] {0, y, 0, size, y, 0, size, y, size, 0, y, size};
    }

    @Test
    void fullFacesPreserveEveryCoordinateIncludingSlopedWater() {
        float[] water = {0, 64.75f, 0, 16, 64.875f, 0, 16, 64.5f, 16, 0, 64.875f, 16};
        var patches = new ModelFacePatches();
        patches.reset(null, 1, 16, water);
        assertTrue(patches.next());
        assertArrayEquals(water, patches.positions);
        assertFalse(patches.hasNext());
    }

    @Test
    void slabTopKeepsHalfVoxelHeightWithoutSplittingContinuousFloor() {
        var patches = new ModelFacePatches();
        patches.reset(new TerrainMaterial.FaceShape(0, 0, 1, 1, .5f, 0, 0), 1, 16, top(64, 80));
        assertTrue(patches.next());
        for (int i = 0; i < 12; i += 3) assertEquals(72f, patches.positions[i + 1]);
        assertEquals(64, patches.positions[3]);
        assertFalse(patches.hasNext());
    }

    @Test
    void stackedPartialSidesKeepVerticalGapsAndMergeAlongContinuousAxis() {
        var patches = new ModelFacePatches();
        float[] north = {0, 0, 0, 8, 0, 0, 8, 12, 0, 0, 12, 0};
        patches.reset(new TerrainMaterial.FaceShape(0, 0, 1, .5f, 0, 0, 0), 2, 4, north);
        for (int y = 0; y < 3; y++) {
            assertTrue(patches.next());
            assertEquals(y * 4, patches.positions[1]);
            assertEquals(y * 4 + 2, patches.positions[7]);
            assertEquals(8, patches.positions[3]);
        }
        assertFalse(patches.hasNext());
    }

    @Test
    void postFootprintsRepeatPerLodCellWithoutBecomingOneWall() {
        var patches = new ModelFacePatches();
        var post = new TerrainMaterial.FaceShape(.375f, .375f, .625f, .625f, 1, 0, 0);
        patches.reset(post, 1, 4, top(16, 65));
        int count = 0;
        while (patches.hasNext()) {
            assertTrue(patches.next());
            float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
            float minZ = minX, maxZ = maxX;
            for (int i = 0; i < 12; i += 3) {
                minX = Math.min(minX, patches.positions[i]);
                maxX = Math.max(maxX, patches.positions[i]);
                minZ = Math.min(minZ, patches.positions[i + 2]);
                maxZ = Math.max(maxZ, patches.positions[i + 2]);
            }
            assertEquals(1, maxX - minX);
            assertEquals(1, maxZ - minZ);
            assertEquals(1.5f, minX % 4);
            assertEquals(1.5f, minZ % 4);
            count++;
        }
        assertEquals(16, count);
    }

    @Test
    void crossedPlantPlaneKeepsItsDiagonalAndCellBoundary() {
        var patches = new ModelFacePatches();
        patches.reset(
                new TerrainMaterial.FaceShape(0, 0, 1, 1, 0, 1, 0),
                2,
                1,
                new float[] {0, 0, 0, 3, 0, 0, 3, 1, 0, 0, 1, 0});
        for (int x = 0; x < 3; x++) {
            assertTrue(patches.next());
            assertEquals(x, patches.positions[0]);
            assertEquals(0, patches.positions[2]);
            assertEquals(x + 1, patches.positions[3]);
            assertEquals(1, patches.positions[5]);
        }
        assertFalse(patches.hasNext());
    }
}
