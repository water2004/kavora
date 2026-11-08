/* SPDX-License-Identifier: Apache-2.0 */
package com.nvidia.rtxremix.minecraft.benchmark;

/** An opt-in advancing shader clock, independent of loading time and measured frame duration. */
final class BenchmarkAnimationClock {
    static final String OPTION = "rtx.animation.timeOverrideMs";
    private static final long PERIOD_MILLIS = 1L << 24;
    private final long epochMillis;
    private long millis = -1;

    BenchmarkAnimationClock(long epochMillis) {
        if (epochMillis < -1 || epochMillis >= PERIOD_MILLIS) {
            throw new IllegalArgumentException("Animation epoch must be -1 or within the runtime's 24-bit millisecond period");
        }
        this.epochMillis = epochMillis;
    }

    boolean update(long frame) {
        long next = epochMillis < 0 || frame < 0
                ? -1 : epochMillis + Math.multiplyExact(frame, 1000L) / 60L;
        if (next >= PERIOD_MILLIS) {
            throw new IllegalStateException("Benchmark animation exceeds the shader time range; choose an earlier epoch");
        }
        if (next == millis) return false;
        millis = next;
        return true;
    }

    long millis() {
        return millis;
    }

    long epochMillis() {
        return epochMillis;
    }

    double gameTime(double ordinaryTicks) {
        return millis < 0 ? ordinaryTicks : millis / 50.0;
    }
}
