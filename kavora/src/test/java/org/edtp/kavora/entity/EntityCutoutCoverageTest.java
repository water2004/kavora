/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityCutoutCoverageTest {
    @Test
    void cutoutFacesUseTheirOwnUvRectangle() {
        byte[] rgba = new byte[8 * 4 * 4];
        rgba[(1 * 8 + 3) * 4 + 3] = 1;
        var coverage = EntityCutoutCoverage.fromRgba(rgba, 8, 4);
        assertFalse(coverage.hasCoverage(0, 0, 3f / 8, 1));
        assertFalse(coverage.hasCoverage(0.5f, 0, 1, 1));
        assertFalse(coverage.hasCoverage(0, 0.5f, 1, 1));
        assertTrue(coverage.hasCoverage(0, 0, 0.5f, 1));
        assertTrue(coverage.hasCoverage(0.375f, 0.25f, 0.5f, 0.5f));
        assertTrue(coverage.hasCoverage(-0.5f, 0, 0, 1));
        assertNull(EntityCutoutCoverage.fromRgba(new byte[]{0, 0, 0, (byte) 255}, 1, 1));
    }

    @Test
    void aPartiallyCoveredOverlayKeepsItsTexelAlignedOpening() {
        byte[] rgba = new byte[8 * 8 * 4];
        for (int x = 1; x <= 6; x++) rgba[(8 + x) * 4 + 3] = (byte) 255;
        for (int y = 5; y <= 6; y++) {
            rgba[(y * 8 + 1) * 4 + 3] = (byte) 255;
            rgba[(y * 8 + 6) * 4 + 3] = (byte) 255;
        }
        var coverage = EntityCutoutCoverage.fromRgba(rgba, 8, 8);
        assertArrayEquals(new int[]{1, 1, 7, 2, 1, 5, 2, 7, 6, 5, 7, 7},
                coverage.faceRectangles(1f / 8, 1f / 8, 7f / 8, 7f / 8, true));
        assertNull(coverage.faceRectangles(1f / 8, 1f / 8, 7f / 8, 7f / 8, false));
        assertArrayEquals(new int[0], coverage.faceRectangles(2f / 8, 2f / 8, 6f / 8, 7f / 8, true));
    }
}
