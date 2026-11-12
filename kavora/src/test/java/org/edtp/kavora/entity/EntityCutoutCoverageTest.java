/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.entity;

import org.junit.jupiter.api.Test;

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
}
