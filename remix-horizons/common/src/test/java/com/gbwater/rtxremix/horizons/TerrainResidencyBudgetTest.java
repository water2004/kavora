/* SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.gbwater.rtxremix.horizons;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TerrainResidencyBudgetTest {
    @Test
    void replacementMustFitBothPeakAndFinalSteadyBudget() {
        var budget = new TerrainResidencyBudget(1000, 250);
        try (var old = budget.reserveTransition(600, 0)) {
            old.uploaded(600);
        }
        assertNull(budget.reserveTransition(151, 0));
        assertNull(budget.reserveTransition(351, 200)); // final residency would exceed 750
        try (var replacement = budget.reserveTransition(350, 200)) {
            assertNotNull(replacement);
            replacement.uploaded(350);
        }
        assertEquals(950, budget.residentBytes());
        assertNull(budget.reserveTransition(100, 200)); // peak would exceed 1000
        budget.retire(200);
        assertEquals(750, budget.residentBytes());
        assertNull(budget.reserveTransition(1, 0));
    }

    @Test
    void promisedBytesAreSharedAndCancellationKeepsUploadedPagesCharged() {
        var budget = new TerrainResidencyBudget(1000, 250);
        try (var old = budget.reserveTransition(750, 0)) {
            old.uploaded(750);
        }
        var first = budget.reserveTransition(200, 200);
        assertNotNull(first);
        assertNull(budget.reserveTransition(100, 100));
        first.uploaded(100);
        first.close();
        first.close();
        assertEquals(850, budget.residentBytes());
        assertEquals(0, budget.promisedBytes());
        assertThrows(IllegalStateException.class, () -> first.uploaded(1));
        budget.retire(100);
        try (var retry = budget.reserveTransition(200, 200)) {
            assertNotNull(retry);
        }
    }

    @Test
    void rejectsInvalidOrOversizedTransitions() {
        var budget = new TerrainResidencyBudget(1000, 250);
        assertNull(budget.reserveTransition(751, 0));
        assertThrows(IllegalArgumentException.class, () -> budget.reserveTransition(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> budget.reserveTransition(1, -1));
        assertThrows(IllegalArgumentException.class, () -> budget.reserveTransition(1, 1));
        assertThrows(IllegalStateException.class, () -> budget.retire(1));
    }
}
