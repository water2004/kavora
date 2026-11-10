/* SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0 */
package org.edtp.kavora.render;

import org.edtp.kavora.sdk.WaterSimulationFrame;

/** Collects nearby splash impacts on the client thread for the next native submission. */
public final class KavoraWaterRipples {
    private static final WaterSimulationFrame FRAME = new WaterSimulationFrame();
    private static boolean enabled;
    private static boolean rings;
    private static boolean sludge;
    private static float lastRain = Float.NaN;
    private static float lastX = Float.POSITIVE_INFINITY;
    private static float lastZ = Float.POSITIVE_INFINITY;

    private KavoraWaterRipples() {}

    public static boolean isEnabled() { return enabled || rings || sludge; }

    public static void updateOption(String id, boolean value) {
        switch (id) {
            case "water.simulation" -> enabled = value;
            case "water.dropletRings" -> {
                if (rings != value) lastRain = Float.NaN;
                rings = value;
            }
            case "water.floatingSludge" -> sludge = value;
            default -> { }
        }
    }

    public static boolean wantsRain() { return rings; }

    public static void rain(float intensity) {
        float amount = Math.round(intensity * 32) / 32.0f;
        if (amount != lastRain) {
            FRAME.setRain(amount);
            lastRain = amount;
        }
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
        lastRain = Float.NaN;
        lastX = lastZ = Float.POSITIVE_INFINITY;
    }

    static WaterSimulationFrame pendingFrame() { return FRAME.isEmpty() ? null : FRAME; }

    static void submitted() {
        FRAME.clear();
        lastX = lastZ = Float.POSITIVE_INFINITY;
    }
}
