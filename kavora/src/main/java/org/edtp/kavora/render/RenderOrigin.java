/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.render;

/**
 * Coordinate frame for native scene submissions. The render thread owns the
 * current origin. Work prepared off-thread carries the immutable origin it
 * was given when scheduled.
 */
public record RenderOrigin(int x, int z) {
    private static final int GRID = 256;
    private static RenderOrigin current = new RenderOrigin(0, 0);

    public static RenderOrigin current() {
        return current;
    }

    public RenderOrigin near(double x, double z) {
        if (Math.abs(x - this.x) < GRID && Math.abs(z - this.z) < GRID) return this;
        return new RenderOrigin(gridCenter(x), gridCenter(z));
    }

    private static int gridCenter(double value) {
        return Math.toIntExact(Math.round(value / GRID) * GRID);
    }

    public static void publish(RenderOrigin next) {
        current = next;
    }

    public float localX(double worldX) {
        return (float) (worldX - x);
    }

    public float localZ(double worldZ) {
        return (float) (worldZ - z);
    }
}
