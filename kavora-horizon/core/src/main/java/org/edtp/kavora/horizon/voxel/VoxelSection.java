/* SPDX-License-Identifier: LGPL-3.0-only */
package org.edtp.kavora.horizon.voxel;

import java.util.Arrays;

/** Immutable after publication. Unknown octants and observed empty octants are distinct. */
public record VoxelSection(
        long key, long revision, int knownChildren, int occupiedChildren, int[] cells) {
    public static final int MATERIAL_MASK = (1 << 20) - 1;
    public static final int[] AIR = new int[VoxelKey.VOLUME];

    public VoxelSection {
        if (cells.length != VoxelKey.VOLUME
                || (knownChildren & ~255) != 0
                || (occupiedChildren & ~knownChildren) != 0)
            throw new IllegalArgumentException("Invalid voxel section");
    }

    public int cell(int x, int y, int z) {
        return cells[VoxelKey.index(x, y, z)];
    }

    public static int material(int cell) {
        return cell & MATERIAL_MASK;
    }

    /** Fluid surface height above the cell's base, in ninths of a world block. */
    public static int fluidTop(int cell) {
        return cell >>> 20;
    }

    public static int withFluidTop(int material, int ninths) {
        return material | ninths << 20;
    }

    public Info info() {
        return new Info(key, revision, knownChildren, occupiedChildren);
    }

    public record Info(long key, long revision, int knownChildren, int occupiedChildren) {
        public boolean empty() {
            return occupiedChildren == 0;
        }
    }

    /** One batch owns the writable copy; finishing transfers it to an immutable snapshot. */
    static final class Editor {
        final VoxelSection previous;
        private final long key;
        private final int[] original;
        private int[] cells;
        private long[] dirtyRows;
        private int meshChanges;
        private int known, occupied;
        private boolean finished;

        Editor(long key, VoxelSection previous) {
            this.key = key;
            this.previous = previous;
            original = previous == null ? AIR : previous.cells();
            cells = original;
            known = previous == null ? 0 : previous.knownChildren();
            occupied = previous == null ? 0 : previous.occupiedChildren();
        }

        void replaceOctant(int octant, int[] samples) {
            if (finished) throw new IllegalStateException("Voxel edit already published");
            if (samples.length != 4096) throw new IllegalArgumentException("Expected a 16 cubed snapshot");
            boolean nonempty = false;
            int ox = (octant & 1) * 16, oy = (octant >>> 2) * 16, oz = ((octant >>> 1) & 1) * 16;
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++) {
                    int source = z << 4 | y << 8;
                    int target = VoxelKey.index(ox, y + oy, z + oz);
                    if (!nonempty) {
                        for (int x = 0; x < 16; x++) {
                            if (material(samples[source + x]) != 0) {
                                nonempty = true;
                                break;
                            }
                        }
                    }
                    if (Arrays.mismatch(cells, target, target + 16, samples, source, source + 16) < 0)
                        continue;
                    if (cells == original) cells = original.clone();
                    if (dirtyRows == null) dirtyRows = new long[16];
                    int row = target >>> 5;
                    dirtyRows[row >>> 6] |= 1L << (row & 63);
                    System.arraycopy(samples, source, cells, target, 16);
                }
            known |= 1 << octant;
            setOccupied(octant, nonempty);
        }

        void setOccupied(int octant, boolean nonempty) {
            if (finished) throw new IllegalStateException("Voxel edit already published");
            occupied = (occupied & ~(1 << octant)) | (nonempty ? 1 << octant : 0);
        }

        VoxelSection finish() {
            if (finished) throw new IllegalStateException("Voxel edit already published");
            finished = true;
            if (previous != null && cells == original
                    && known == previous.knownChildren() && occupied == previous.occupiedChildren()) return previous;
            meshChanges = changedMeshes();
            return new VoxelSection(key, previous == null ? 1 : previous.revision() + 1, known, occupied, cells);
        }

        int meshChanges() {
            if (!finished) throw new IllegalStateException("Voxel edit not yet published");
            return meshChanges;
        }

        private int changedMeshes() {
            if (dirtyRows == null) return 0;
            int mask = 0;
            for (int group = 0; group < dirtyRows.length; group++) {
                long rows = dirtyRows[group];
                while (rows != 0) {
                    int row = (group << 6) | Long.numberOfTrailingZeros(rows);
                    rows &= rows - 1;
                    int start = row << 5;
                    // Repeated observations can restore a row to the committed contents.
                    if (Arrays.mismatch(original, start, start + 32, cells, start, start + 32) < 0) continue;
                    mask |= 1;
                    if (material(original[start]) != material(cells[start])) mask |= 1 << 1;
                    if (material(original[start + 31]) != material(cells[start + 31])) mask |= 1 << 2;
                    int y = row >>> 5, z = row & 31;
                    int boundary = (y == 0 ? 1 << 3 : y == 31 ? 1 << 4 : 0)
                            | (z == 0 ? 1 << 5 : z == 31 ? 1 << 6 : 0);
                    if ((mask & boundary) != boundary) {
                        for (int x = start; x < start + 32; x++) {
                            // Neighbour occlusion depends on material, not fluid height.
                            if (material(original[x]) != material(cells[x])) {
                                mask |= boundary;
                                break;
                            }
                        }
                    }
                    if (mask == 127) return mask;
                }
            }
            return mask;
        }
    }
}
