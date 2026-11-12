/* SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0 */
package org.edtp.kavora.render;

import org.edtp.kavora.sdk.WaterSimulationFrame;

/** Collects nearby splash impacts on the client thread for the next native submission. */
public final class KavoraWaterRipples {
    private static final WaterSimulationFrame FRAME = new WaterSimulationFrame();
    private static final double[] POSITIONS = new double[WaterSimulationFrame.MAX_IMPULSES * 3];
    private static int splashCount;
    private static boolean enabled;
    private static boolean rings;
    private static boolean sludge;
    private static float lastRain = Float.NaN;
    private static double lastX = Double.POSITIVE_INFINITY;
    private static double lastZ = Double.POSITIVE_INFINITY;

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

    public static void splash(double x, double y, double z) {
        if (splashCount == WaterSimulationFrame.MAX_IMPULSES) return;
        double dx = x - lastX;
        double dz = z - lastZ;
        if (dx * dx + dz * dz < 0.25) return;

        int offset = splashCount++ * 3;
        POSITIONS[offset] = x;
        POSITIONS[offset + 1] = y;
        POSITIONS[offset + 2] = z;
        lastX = x;
        lastZ = z;
    }

    public static void reset() {
        FRAME.resetWaves();
        lastRain = Float.NaN;
        clearSplashPositions();
    }

    static WaterSimulationFrame pendingFrame() {
        FRAME.clearImpulses();
        RenderOrigin origin = RenderOrigin.current();
        for (int i = 0; i < splashCount; i++) {
            int offset = i * 3;
            FRAME.add(origin.localX(POSITIONS[offset]), (float) POSITIONS[offset + 1],
                    origin.localZ(POSITIONS[offset + 2]), 0.85f, -0.8f);
        }
        return FRAME.isEmpty() ? null : FRAME;
    }

    static void submitted() {
        FRAME.clear();
        clearSplashPositions();
    }

    private static void clearSplashPositions() {
        splashCount = 0;
        lastX = lastZ = Double.POSITIVE_INFINITY;
    }
}
