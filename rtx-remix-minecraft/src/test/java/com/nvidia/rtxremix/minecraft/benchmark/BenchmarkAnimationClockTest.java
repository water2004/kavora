/* SPDX-License-Identifier: Apache-2.0 */
package com.nvidia.rtxremix.minecraft.benchmark;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BenchmarkAnimationClockTest {
    @Test
    void disabledAndReleasedClockPreserveWorldTime() {
        var disabled = new BenchmarkAnimationClock(-1);
        assertFalse(disabled.update(300));
        assertEquals(12345.125, disabled.gameTime(12345.125));
        var clock = new BenchmarkAnimationClock(600_000);
        assertTrue(clock.update(0));
        assertEquals(12_000.0, clock.gameTime(12345.125));
        assertTrue(clock.update(-1));
        assertEquals(12345.125, clock.gameTime(12345.125));
        assertFalse(clock.update(-1));
    }

    @Test
    void warmupAndRecordingShareOneAdvancingTimeline() {
        var clock = new BenchmarkAnimationClock(600_000);
        clock.update(239);
        assertEquals(603_983, clock.millis());
        clock.update(240);
        assertEquals(604_000, clock.millis());
        clock.update(240 + 60);
        assertEquals(605_000, clock.millis());
        assertFalse(clock.update(300));
    }

    @Test
    void rejectsTimeOverflowInsteadOfJumpingCloudPhase() {
        var clock = new BenchmarkAnimationClock((1L << 24) - 10);
        assertThrows(IllegalStateException.class, () -> clock.update(1));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkAnimationClock(1L << 24));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkAnimationClock(-2));
    }
}
