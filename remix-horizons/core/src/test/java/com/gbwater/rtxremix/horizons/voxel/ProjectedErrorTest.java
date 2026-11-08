/* SPDX-License-Identifier: LGPL-3.0-only */
package com.gbwater.rtxremix.horizons.voxel;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ProjectedErrorTest {
    private static float[] perspective() {
        float[] matrix = new float[16];
        matrix[0] = .8033f;
        matrix[5] = 1.428f;
        matrix[11] = -1;
        matrix[10] = -1;
        matrix[14] = -.2f;
        return matrix;
    }

    @Test
    void sidePlanesRejectOffscreenBoxesEvenWhenTheyTouchTheCameraPlane() {
        ProjectedError view = new ProjectedError();
        view.set(0, 0, 0, perspective(), 1920, 1080);
        assertEquals(-1, view.area(VoxelKey.of(0, 8, 0, -1)));
        assertEquals(-1, view.area(VoxelKey.of(0, 0, 8, -1)));
        assertEquals(-1, view.area(VoxelKey.of(0, 0, 0, 1)));
        assertTrue(view.area(VoxelKey.of(0, 0, 0, -8)) > 0);
    }

    @Test
    void aBoxContainingTheCameraStillRequiresRefinement() {
        ProjectedError view = new ProjectedError();
        view.set(16, 16, 16, perspective(), 1920, 1080);
        assertEquals(Double.POSITIVE_INFINITY, view.area(VoxelKey.of(0, 0, 0, 0)));
    }
}
