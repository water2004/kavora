/* SPDX-License-Identifier: LGPL-3.0-only */
package org.edtp.kavora.horizon.voxel;

/** Signed three-dimensional section coordinates. Every section contains 32 cubed samples. */
public final class VoxelKey {
    public static final int SIDE = 32, VOLUME = SIDE * SIDE * SIDE, ROOT_LEVEL = 4;
    private static final long XZ_MASK = (1L << 22) - 1;

    private VoxelKey() {}

    public static long of(int level, int x, int y, int z) {
        if (level < 0
                || level > ROOT_LEVEL
                || x < -(1 << 21)
                || x >= 1 << 21
                || z < -(1 << 21)
                || z >= 1 << 21
                || y < Short.MIN_VALUE
                || y > Short.MAX_VALUE)
            throw new IllegalArgumentException("Voxel section coordinate out of range");
        return ((long) level << 60)
                | ((long) y & 65535) << 44
                | ((long) z & XZ_MASK) << 22
                | ((long) x & XZ_MASK);
    }

    public static int level(long key) {
        return (int) (key >>> 60);
    }

    public static int x(long key) {
        return (int) (key << 42 >> 42);
    }

    public static int y(long key) {
        return (short) (key >>> 44);
    }

    public static int z(long key) {
        return (int) (key << 20 >> 42);
    }

    public static int size(long key) {
        return SIDE << level(key);
    }

    public static int blockX(long key) {
        return x(key) * size(key);
    }

    public static int blockY(long key) {
        return y(key) * size(key);
    }

    public static int blockZ(long key) {
        return z(key) * size(key);
    }

    public static long parent(long key) {
        return of(level(key) + 1, x(key) >> 1, y(key) >> 1, z(key) >> 1);
    }

    public static long child(long key, int octant) {
        return of(
                level(key) - 1,
                2 * x(key) + (octant & 1),
                2 * y(key) + (octant >>> 2),
                2 * z(key) + ((octant >>> 1) & 1));
    }

    public static int octant(long key) {
        return (x(key) & 1) | ((z(key) & 1) << 1) | ((y(key) & 1) << 2);
    }

    public static long containing(int level, int bx, int by, int bz) {
        return of(level, bx >> (5 + level), by >> (5 + level), bz >> (5 + level));
    }

    public static long neighbor(long key, int axis, int sign) {
        return of(
                level(key),
                x(key) + (axis == 0 ? sign : 0),
                y(key) + (axis == 1 ? sign : 0),
                z(key) + (axis == 2 ? sign : 0));
    }

    public static boolean contains(long outer, long inner) {
        int shift = level(outer) - level(inner);
        return shift >= 0
                && x(outer) == x(inner) >> shift
                && y(outer) == y(inner) >> shift
                && z(outer) == z(inner) >> shift;
    }

    public static int index(int x, int y, int z) {
        return x | z << 5 | y << 10;
    }

    public static String describe(long key) {
        return "L" + level(key) + "[" + x(key) + "," + y(key) + "," + z(key) + "]";
    }
}
