/* SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0 */
package org.edtp.kavora.render;

import org.edtp.kavora.sdk.WaterSimulationFrame;

/** Collects nearby splash impacts on the client thread for the next native submission. */
public final class KavoraWaterRipples {
    private static final WaterSimulationFrame FRAME = new WaterSimulationFrame();
    private static boolean enabled;
    private static float lastX = Float.POSITIVE_INFINITY;
    private static float lastZ = Float.POSITIVE_INFINITY;

    private KavoraWaterRipples() {}

    public static boolean isEnabled() { return enabled; }

    public static void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        reset();
    }

    public static void splash(float x, float y, float z) {
        float dx = x - lastX;
        float dz = z - lastZ;
        if (dx * dx + dz * dz < 0.25f) return;
        if (FRAME.add(x, y, z, 0.85f, -0.8f)) {
            lastX = x;
            lastZ = z;
        }
    }

    public static void reset() {
        FRAME.resetWaves();
        lastX = lastZ = Float.POSITIVE_INFINITY;
    }

    static WaterSimulationFrame pendingFrame() { return FRAME.isEmpty() ? null : FRAME; }

    static void submitted() {
        FRAME.clear();
        lastX = lastZ = Float.POSITIVE_INFINITY;
    }
}
