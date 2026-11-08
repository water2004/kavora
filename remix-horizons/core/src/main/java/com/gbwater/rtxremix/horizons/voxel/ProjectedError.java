/* SPDX-License-Identifier: LGPL-3.0-only */
package com.gbwater.rtxremix.horizons.voxel;

/** Conservative pixel bounds of a section, independent of queue pressure and GPU memory usage. */
public final class ProjectedError {
    private final float[] matrix = new float[16];
    private double x, y, z;
    private int width, height;

    public void set(double x, double y, double z, float[] matrix, int width, int height) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.width = width;
        this.height = height;
        System.arraycopy(matrix, 0, this.matrix, 0, 16);
    }

    public double area(long key) {
        double minX = Double.POSITIVE_INFINITY, minY = minX, maxX = -minX, maxY = -minX;
        int front = 0, outsidePlanes = 31;
        double size = VoxelKey.size(key);
        for (int i = 0; i < 8; i++) {
            double px = VoxelKey.blockX(key) - x + ((i & 1) != 0 ? size : 0);
            double py = VoxelKey.blockY(key) - y + ((i & 2) != 0 ? size : 0);
            double pz = VoxelKey.blockZ(key) - z + ((i & 4) != 0 ? size : 0);
            double w = matrix[3] * px + matrix[7] * py + matrix[11] * pz + matrix[15];
            double clipX = matrix[0] * px + matrix[4] * py + matrix[8] * pz + matrix[12];
            double clipY = matrix[1] * px + matrix[5] * py + matrix[9] * pz + matrix[13];
            int outside =
                    (clipX < -w ? 1 : 0)
                            | (clipX > w ? 2 : 0)
                            | (clipY < -w ? 4 : 0)
                            | (clipY > w ? 8 : 0)
                            | (w <= .001 ? 16 : 0);
            outsidePlanes &= outside;
            if (w <= 0.001) continue;
            front++;
            double sx = clipX / w;
            double sy = clipY / w;
            minX = Math.min(minX, sx);
            maxX = Math.max(maxX, sx);
            minY = Math.min(minY, sy);
            maxY = Math.max(maxY, sy);
        }
        if (outsidePlanes != 0 || front == 0) return -1;
        if (front < 8) return Double.POSITIVE_INFINITY;
        if (minX > 1 || maxX < -1 || minY > 1 || maxY < -1) return -1;
        return (maxX - minX) * (maxY - minY) * width * height * .25;
    }

    public boolean within(long key, int radius) {
        int size = VoxelKey.size(key);
        double dx = Math.max(VoxelKey.blockX(key) - x, x - (VoxelKey.blockX(key) + size));
        double dz = Math.max(VoxelKey.blockZ(key) - z, z - (VoxelKey.blockZ(key) + size));
        return Math.max(dx, dz) <= radius;
    }
}
