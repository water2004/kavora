/* SPDX-License-Identifier: LGPL-3.0-only */
package org.edtp.kavora.horizon.voxel;

import java.util.Arrays;

/** Greedy rectangles over six directional masks. One face is an eight-byte CPU record. */
public final class VoxelMesher {
    @FunctionalInterface
    public interface Occlusion {
        boolean hidden(int material, int adjacent, int side);

        default int containedFluid(int material) {
            return 0;
        }

        /** Bit 0 means a full opaque cube. Higher bits hold its contained-fluid material. */
        default int classify(int material) {
            return containedFluid(material) << 1;
        }
    }

    public record Mesh(long key, long revision, long[] quads) {
        public long bytes() {
            return (long) quads.length * 8;
        }
    }

    // Side order matches the model palette: down, up, north, south, west, east.
    private static final int[] AXIS = {1, 1, 2, 2, 0, 0};
    private static final int[] STRIDE = {1, 32 * 32, 32};
    // A set row bit owns a live mask entry. All other entries may belong to a previous plane.
    private final int[] mask = new int[32 * 32];
    private final int[] faceRows = new int[32];
    private final int[] fluidCells = new int[32 * 32 * 32];
    // Each axis has 32 planes of 32 rows, with one occupancy bit per cell along the row.
    private final int[] occupied = new int[3 * 32 * 32];
    private final int[] opaque = new int[3 * 32 * 32];
    private final int[] fluids = new int[3 * 32 * 32];
    private long[] output = new long[4096];
    private static final long[] NO_QUADS = new long[0];
    private int count;

    public Mesh build(VoxelSection[] neighborhood, Occlusion occlusion) {
        VoxelSection source = neighborhood[0];
        if (source == null) throw new IllegalArgumentException("Missing meshing source");
        count = 0;
        if (source.cells() == VoxelSection.AIR)
            return new Mesh(source.key(), source.revision(), NO_QUADS);
        int[] sourceCells = source.cells();
        boolean containedFluid = prepareMasks(sourceCells, occlusion);
        for (int pass = 0; pass < (containedFluid ? 2 : 1); pass++)
            for (int side = 0; side < 6; side++) {
                int axis = AXIS[side],
                        s = (axis + 1) % 3,
                        t = (axis + 2) % 3,
                        sign = (side & 1) == 0 ? -1 : 1;
                int planeStride = STRIDE[axis], uStride = STRIDE[s], vStride = STRIDE[t];
                VoxelSection neighbor = neighborhood[1 + axis * 2 + (sign > 0 ? 1 : 0)];
                int[] boundaryCells = neighbor == null ? null : neighbor.cells();
                int[] cells = pass == 0 ? sourceCells : fluidCells;
                for (int plane = 0; plane < 32; plane++) {
                    boolean boundary = plane == (sign > 0 ? 31 : 0);
                    int[] adjacentCells = boundary ? boundaryCells : sourceCells;
                    int adjacentBase = ((plane + sign) & 31) * planeStride;
                    for (int v = 0; v < 32; v++) {
                        int faces = 0;
                        int row = axis * 1024 + plane * 32 + v;
                        int candidates = (pass == 0 ? occupied : fluids)[row];
                        if (pass == 0 && opaque[row] != 0) {
                            int neighborOpaque = 0;
                            if (!boundary) neighborOpaque = opaque[row + sign * 32];
                            else if (adjacentCells != null) {
                                int otherIndex = adjacentBase + v * vStride;
                                for (int u = 0; u < 32; u++, otherIndex += uStride) {
                                    int other = VoxelSection.material(adjacentCells[otherIndex]);
                                    if (other != 0 && (occlusion.classify(other) & 1) != 0)
                                        neighborOpaque |= 1 << u;
                                }
                            }
                            candidates &= ~(opaque[row] & neighborOpaque);
                        }
                        while (candidates != 0) {
                            int u = Integer.numberOfTrailingZeros(candidates);
                            candidates &= candidates - 1;
                            int index = plane * planeStride + v * vStride + u * uStride;
                            int adjacentIndex = adjacentBase + v * vStride + u * uStride;
                            int cell = cells[index];
                            int material = VoxelSection.material(cell);
                            if (material != 0) {
                                int adjacent = adjacentCells == null ? 0 : adjacentCells[adjacentIndex];
                                if (!occlusion.hidden(
                                        material, VoxelSection.material(adjacent), side)) {
                                    mask[u + v * 32] = cell;
                                    faces |= 1 << u;
                                }
                            }
                        }
                        faceRows[v] = faces;
                    }
                    for (int v = 0; v < 32; v++)
                        while (faceRows[v] != 0) {
                            int u = Integer.numberOfTrailingZeros(faceRows[v]);
                            int face = mask[u + v * 32];
                            int width = 1;
                            while (u + width < 32
                                    && (faceRows[v] & (1 << (u + width))) != 0
                                    && mask[u + width + v * 32] == face) width++;
                            int rectangle = (-1 >>> (32 - width)) << u;
                            int height = 1;
                            rows:
                            while (v + height < 32
                                    && (faceRows[v + height] & rectangle) == rectangle) {
                                for (int a = 0; a < width; a++)
                                    if (mask[u + a + (v + height) * 32] != face) break rows;
                                height++;
                            }
                            for (int b = 0; b < height; b++)
                                faceRows[v + b] &= ~rectangle;
                            int x = axis == 0 ? plane : s == 0 ? u : v,
                                    y = axis == 1 ? plane : s == 1 ? u : v,
                                    z = axis == 2 ? plane : s == 2 ? u : v;
                            append(pack(x, y, z, side, width, height, face));
                        }
                }
            }
        return new Mesh(source.key(), source.revision(), Arrays.copyOf(output, count));
    }

    private boolean prepareMasks(int[] cells, Occlusion occlusion) {
        Arrays.fill(occupied, 0, 2048, 0);
        Arrays.fill(opaque, 0, 2048, 0);
        Arrays.fill(fluids, 0, 2048, 0);
        boolean containedFluid = false;
        int index = 0;
        for (int y = 0; y < 32; y++) {
            int yBit = 1 << y, yRow = 1024 + y * 32;
            for (int z = 0; z < 32; z++) {
                int occupiedRow = 0, opaqueRow = 0, fluidRow = 0, zBit = 1 << z;
                for (int x = 0; x < 32; x++, index++) {
                    int cell = cells[index], material = VoxelSection.material(cell);
                    int classification = material == 0 ? 0 : occlusion.classify(material);
                    int fluid = classification >>> 1;
                    fluidCells[index] = fluid == 0 ? 0 : VoxelSection.withFluidTop(fluid, VoxelSection.fluidTop(cell));
                    int xBit = 1 << x, xRow = x * 32 + z;
                    if (material != 0) {
                        occupiedRow |= xBit;
                        occupied[xRow] |= yBit;
                        occupied[yRow + x] |= zBit;
                    }
                    if ((classification & 1) != 0) {
                        opaqueRow |= xBit;
                        opaque[xRow] |= yBit;
                        opaque[yRow + x] |= zBit;
                    }
                    if (fluid != 0) {
                        fluidRow |= xBit;
                        fluids[xRow] |= yBit;
                        fluids[yRow + x] |= zBit;
                    }
                }
                int zRow = 2048 + z * 32 + y;
                occupied[zRow] = occupiedRow;
                opaque[zRow] = opaqueRow;
                fluids[zRow] = fluidRow;
                containedFluid |= fluidRow != 0;
            }
        }
        return containedFluid;
    }

    private void append(long quad) {
        if (count == output.length) output = Arrays.copyOf(output, count * 2);
        output[count++] = quad;
    }

    public static long pack(int x, int y, int z, int side, int width, int height, int cell) {
        return x
                | (long) y << 5
                | (long) z << 10
                | (long) side << 15
                | (long) (width - 1) << 18
                | (long) (height - 1) << 23
                | Integer.toUnsignedLong(cell) << 28;
    }

    public static int x(long q) {
        return (int) q & 31;
    }

    public static int y(long q) {
        return (int) (q >>> 5) & 31;
    }

    public static int z(long q) {
        return (int) (q >>> 10) & 31;
    }

    public static int side(long q) {
        return (int) (q >>> 15) & 7;
    }

    public static int width(long q) {
        return ((int) (q >>> 18) & 31) + 1;
    }

    public static int height(long q) {
        return ((int) (q >>> 23) & 31) + 1;
    }

    public static int cell(long q) {
        return (int) (q >>> 28);
    }
}
