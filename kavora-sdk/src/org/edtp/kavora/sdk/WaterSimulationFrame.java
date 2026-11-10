/* SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0 */
package org.edtp.kavora.sdk;

/** Reusable batch of world-space water disturbances for a scene submission. */
public final class WaterSimulationFrame {
    public static final int MAX_IMPULSES = 16;
    private static final int COMPONENTS = 5;
    private final float[] impulses = new float[MAX_IMPULSES * COMPONENTS];
    private int count;
    private boolean reset;

    public boolean add(float x, float y, float z, float radius, float strength) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                || !(radius >= 0.25f && radius <= 32.0f) || !(Math.abs(strength) <= 4.0f)) {
            throw new IllegalArgumentException("Invalid water disturbance");
        }
        if (count == MAX_IMPULSES) return false;
        int offset = count++ * COMPONENTS;
        impulses[offset] = x;
        impulses[offset + 1] = y;
        impulses[offset + 2] = z;
        impulses[offset + 3] = radius;
        impulses[offset + 4] = strength;
        return true;
    }

    public void resetWaves() {
        count = 0;
        reset = true;
    }

    public boolean isEmpty() { return count == 0 && !reset; }

    public void clear() {
        count = 0;
        reset = false;
    }

    int count() { return count; }
    boolean resetsWaves() { return reset; }
    float value(int impulse, int component) { return impulses[impulse * COMPONENTS + component]; }
}
