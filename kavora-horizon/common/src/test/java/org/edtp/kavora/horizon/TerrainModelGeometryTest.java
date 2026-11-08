/* SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package org.edtp.kavora.horizon;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class TerrainModelGeometryTest {
    private static BakedQuad quad(Direction direction, float... xyz) {
        return new BakedQuad(
                new Vector3f(xyz[0], xyz[1], xyz[2]),
                new Vector3f(xyz[3], xyz[4], xyz[5]),
                new Vector3f(xyz[6], xyz[7], xyz[8]),
                new Vector3f(xyz[9], xyz[10], xyz[11]),
                0,
                0,
                0,
                0,
                direction,
                null);
    }

    @Test
    void ordinaryCubeStaysOnUnsplitFastPath() {
        var face = quad(Direction.UP, 0, 1, 0, 1, 1, 0, 1, 1, 1, 0, 1, 1);
        assertNull(TerrainMaterialPalette.fitShape(face, 1, 0, 0, 1, 1));
    }

    @Test
    void realBakedSlabCoordinatesKeepInsetDepth() {
        var face = quad(Direction.UP, 0, .5f, 0, 1, .5f, 0, 1, .5f, 1, 0, .5f, 1);
        var shape = TerrainMaterialPalette.fitShape(face, 1, 0, 0, 1, 1);
        assertEquals(.5f, shape.origin());
        assertFalse(shape.repeatS());
        assertFalse(shape.repeatT());
    }

    @Test
    void realBakedPlantCoordinatesRetainTheCrossedPlane() {
        var face = quad(Direction.NORTH, .1f, 0, .1f, .9f, 0, .9f, .9f, 1, .9f, .1f, 1, .1f);
        var shape = TerrainMaterialPalette.fitShape(face, 2, .1f, 0, .9f, 1);
        assertEquals(0, shape.origin());
        assertEquals(1, shape.ds());
        assertEquals(0, shape.dt());
        assertTrue(shape.repeatS());
        assertFalse(shape.repeatT());
        assertEquals(.1f, shape.minS());
        assertEquals(.9f, shape.maxS());
    }
}
