/* SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.gbwater.rtxremix.horizons;

import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.MeshQuadSurface;
import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;

/** Packs model-aware quads and shared affine attributes into bounded ray-tracing pages. */
final class TerrainMeshConverter {
    private static final TerrainMaterial.Side[] SIDES = TerrainMaterial.Side.values();
    static final int PAGE_BYTES = 256 << 10;
    static final int PAGE_STAGING_BYTES = 2 * PAGE_BYTES;

    record Bounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        boolean intersects(float x, float y, float z, float size) {
            return minX < x + size
                    && maxX >= x
                    && minY < y + size
                    && maxY >= y
                    && minZ < z + size
                    && maxZ >= z;
        }
    }

    static final class Page implements AutoCloseable {
        final TerrainMaterial.SurfaceMaterial material;
        ByteBuffer vertices;
        ByteBuffer attributes;
        int vertexCount;
        int indexCount;
        int materialCount;
        private final List<Map<MaterialCatalog.Layer, Integer>> palette =
                List.of(new HashMap<>(), new HashMap<>(), new HashMap<>());
        private float minX = Float.POSITIVE_INFINITY, minY = minX, minZ = minX;
        private float maxX = Float.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;

        Page(TerrainMaterial.SurfaceMaterial material) {
            this.material = material;
            vertices = DirectBufferPool.acquire(PAGE_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            try {
                attributes = DirectBufferPool.acquire(PAGE_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        boolean hasQuadCapacity() {
            return vertices.position() + attributes.position() + 64 + MeshQuadSurface.MATERIAL_STRIDE <= PAGE_BYTES;
        }

        long geometryBytes() {
            return (long) (vertexCount / 4) * MeshQuadSurface.RESIDENT_QUAD_BYTES
                    + (long) materialCount * MeshQuadSurface.MATERIAL_STRIDE;
        }

        int materialIndex(MaterialCatalog.Layer layer, int axis,
                int originX, int originY, int originZ, float waterTileSize) {
            var entries = palette.get(axis);
            Integer existing = entries.get(layer);
            if (existing != null) return existing;
            int index = materialCount++;
            entries.put(layer, index);
            var face = layer.material();
            if (material.fluid()) {
                float scale = 1 / waterTileSize;
                attributes.putFloat((axis == 0 ? originZ : originX) * scale);
                attributes.putFloat((axis == 1 ? originZ : originY) * scale);
                attributes.putFloat(axis == 2 ? scale : 0).putFloat(axis != 2 ? scale : 0);
                attributes.putFloat(axis != 2 ? scale : 0).putFloat(axis == 2 ? scale : 0);
            } else {
                attributes.putFloat(face.originU()).putFloat(face.originV());
                attributes.putFloat(face.duU()).putFloat(face.duV());
                attributes.putFloat(face.dvU()).putFloat(face.dvV());
            }
            attributes.putInt(HardcodedVertex.packHalf2x16(face.minU(), face.minV()));
            attributes.putInt(HardcodedVertex.packHalf2x16(face.maxU(), face.maxV()));
            attributes.putInt(layer.tint()).putInt(axis);
            return index;
        }

        void finish() {
            vertices.flip();
            attributes.flip();
        }

        void transferOwnership() {
            vertices = null;
            attributes = null;
        }

        Bounds bounds() {
            return new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
        }

        void include(float x, float y, float z) {
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }

        public void close() {
            if (vertices != null) {
                DirectBufferPool.release(vertices);
                vertices = null;
            }
            if (attributes != null) {
                DirectBufferPool.release(attributes);
                attributes = null;
            }
        }
    }

    static final class Result implements AutoCloseable {
        final List<Page> pages = new ArrayList<>();
        long bytes;

        long geometryBytes() {
            long size = 0;
            for (Page page : pages) size += page.geometryBytes();
            return size;
        }

        public void close() {
            for (Page page : pages) page.close();
        }
    }

    /**
     * Compact voxel faces stay on the CPU; only the next upload batch expands to Remix vertices.
     */
    static final class Cursor {
        private final long[] quads;
        private final Int2ObjectOpenHashMap<long[]> counts = new Int2ObjectOpenHashMap<>();
        private final Int2ObjectOpenHashMap<MaterialCatalog.Face> faces =
                new Int2ObjectOpenHashMap<>();
        private final float[] positions = new float[12];
        private final ModelFacePatches patches = new ModelFacePatches();
        private final BooleanSupplier cancelled;
        private final int originX, originY, originZ, cellWidth;
        private final float waterTileSize;
        private long geometryBytes;
        private int offset;
        private long[] emissions;
        private boolean resolved, patchesReady;

        Cursor(
                com.gbwater.rtxremix.horizons.voxel.VoxelMesher.Mesh mesh,
                float waterTileSize,
                BooleanSupplier cancelled) {
            this.quads = mesh.quads();
            this.cancelled = cancelled;
            this.waterTileSize = waterTileSize;
            long key = mesh.key();
            originX = com.gbwater.rtxremix.horizons.voxel.VoxelKey.blockX(key);
            originY = com.gbwater.rtxremix.horizons.voxel.VoxelKey.blockY(key);
            originZ = com.gbwater.rtxremix.horizons.voxel.VoxelKey.blockZ(key);
            cellWidth = 1 << com.gbwater.rtxremix.horizons.voxel.VoxelKey.level(key);
            for (long quad : quads) {
                int side = com.gbwater.rtxremix.horizons.voxel.VoxelMesher.side(quad);
                int id =
                        com.gbwater.rtxremix.horizons.voxel.VoxelSection.material(
                                com.gbwater.rtxremix.horizons.voxel.VoxelMesher.cell(quad));
                int faceKey = (id << 3) | side;
                long[] count = counts.get(faceKey);
                if (count == null) {
                    count = new long[4];
                    counts.put(faceKey, count);
                }
                int w = com.gbwater.rtxremix.horizons.voxel.VoxelMesher.width(quad),
                        h = com.gbwater.rtxremix.horizons.voxel.VoxelMesher.height(quad);
                count[0]++;
                count[1] += w;
                count[2] += h;
                count[3] += (long) w * h;
            }
        }

        boolean resolve(IntFunction<MaterialCatalog.Face> resolver) {
            checkCancelled();
            if (resolved) return true;
            boolean missing = false;
            for (var entry : counts.int2ObjectEntrySet()) {
                int key = entry.getIntKey();
                if (faces.containsKey(key)) continue;
                var face = resolver.apply(key);
                if (face == null) {
                    missing = true;
                    continue;
                }
                faces.put(key, face);
            }
            resolved = !missing;
            if (resolved) geometryBytes = estimateBytes();
            return resolved;
        }

        private long estimateBytes() {
            Map<TerrainMaterial.SurfaceMaterial, long[]> totals = new HashMap<>();
            for (var entry : faces.int2ObjectEntrySet()) {
                for (var layer : entry.getValue().layers()) {
                    var shape = layer.material().shape();
                    int split = shape == null ? 0 : (shape.repeatS() ? 1 : 0) | (shape.repeatT() ? 2 : 0);
                    long[] count = totals.computeIfAbsent(layer.material().surface(), ignored -> new long[2]);
                    count[0] += counts.get(entry.getIntKey())[split];
                    count[1]++;
                }
            }
            long bytes = 0;
            for (long[] count : totals.values()) {
                long pages = (count[0] * 104 + PAGE_BYTES - 105) / (PAGE_BYTES - 104);
                bytes += count[0] * MeshQuadSurface.RESIDENT_QUAD_BYTES
                        + Math.min(count[0], count[1] * pages) * MeshQuadSurface.MATERIAL_STRIDE;
            }
            return bytes;
        }

        long geometryBytes() {
            if (!resolved) throw new IllegalStateException("Materials pending");
            return geometryBytes;
        }

        boolean isComplete() {
            return emissions == null ? quads.length == 0 : offset == emissions.length;
        }

        boolean isResolved() {
            return resolved;
        }

        Result nextBatch(int maxPages) {
            if (!resolved || maxPages < 1)
                throw new IllegalStateException("Invalid conversion batch");
            if (emissions == null) orderSurfaces();
            Result result = new Result();
            Page page = null;
            int processed = 0;
            try {
                conversion:
                while (!isComplete()) {
                    if ((processed++ & 255) == 0) checkCancelled();
                    long emission = emissions[offset];
                    long quad = quads[(int) emission];
                    int side = com.gbwater.rtxremix.horizons.voxel.VoxelMesher.side(quad);
                    int cell = com.gbwater.rtxremix.horizons.voxel.VoxelMesher.cell(quad);
                    var face =
                            faces.get(
                                    (com.gbwater.rtxremix.horizons.voxel.VoxelSection.material(cell)
                                                    << 3)
                                            | side);
                    var layer = face.layers().get((int) (emission >>> 32));
                    var material = layer.material();
                    if (!patchesReady) {
                        coordinates(quad, material.surface().fluid(), positions, cellWidth);
                        patches.reset(material.shape(), side, cellWidth, positions);
                        patchesReady = true;
                    }
                    while (patches.hasNext()) {
                        if ((processed++ & 255) == 0) checkCancelled();
                        if (page == null
                                || !page.material.equals(material.surface())
                                || !page.hasQuadCapacity()) {
                            if (result.pages.size() == maxPages) break conversion;
                            page = new Page(material.surface());
                            result.pages.add(page);
                            result.bytes += PAGE_STAGING_BYTES;
                        }
                        if (patches.next())
                            writeQuad(
                                    page,
                                    layer,
                                    side,
                                    patches.positions,
                                    originX,
                                    originY,
                                    originZ,
                                    waterTileSize);
                    }
                    patchesReady = false;
                    offset++;
                }
                result.pages.removeIf(
                        finished -> {
                            if (finished.vertexCount == 0) {
                                finished.close();
                                result.bytes -= PAGE_STAGING_BYTES;
                                return true;
                            }
                            return false;
                        });
                for (Page finished : result.pages) finished.finish();
                return result;
            } catch (RuntimeException | Error failure) {
                result.close();
                throw failure;
            }
        }

        /**
         * Stable bucket order keeps each surface's paint order and avoids tiny interleaved pages.
         */
        private void orderSurfaces() {
            Map<TerrainMaterial.SurfaceMaterial, Integer> sizes = new HashMap<>();
            for (var entry : faces.int2ObjectEntrySet()) {
                int count = Math.toIntExact(counts.get(entry.getIntKey())[0]);
                for (var layer : entry.getValue().layers())
                    sizes.merge(layer.material().surface(), count, Math::addExact);
            }
            Map<TerrainMaterial.SurfaceMaterial, int[]> offsets = new HashMap<>();
            int total = 0;
            for (var entry : sizes.entrySet()) {
                offsets.put(entry.getKey(), new int[] {total});
                total = Math.addExact(total, entry.getValue());
            }
            emissions = new long[total];
            for (int i = 0; i < quads.length; i++) {
                if ((i & 255) == 0) checkCancelled();
                long quad = quads[i];
                int side = com.gbwater.rtxremix.horizons.voxel.VoxelMesher.side(quad);
                int id =
                        com.gbwater.rtxremix.horizons.voxel.VoxelSection.material(
                                com.gbwater.rtxremix.horizons.voxel.VoxelMesher.cell(quad));
                var layers = faces.get((id << 3) | side).layers();
                for (int j = 0; j < layers.size(); j++) {
                    int[] at = offsets.get(layers.get(j).material().surface());
                    emissions[at[0]++] = (long) j << 32 | Integer.toUnsignedLong(i);
                }
            }
        }

        private void checkCancelled() {
            if (cancelled.getAsBoolean()) throw new CancellationException("Voxel mesh retired");
        }
    }

    static void coordinates(long quad, boolean water, float[] result, int scale) {
        int side = com.gbwater.rtxremix.horizons.voxel.VoxelMesher.side(quad),
                axis = SIDES[side].axis,
                s = (axis + 1) % 3,
                t = (axis + 2) % 3;
        float x = com.gbwater.rtxremix.horizons.voxel.VoxelMesher.x(quad) * scale;
        float y = com.gbwater.rtxremix.horizons.voxel.VoxelMesher.y(quad) * scale;
        float z = com.gbwater.rtxremix.horizons.voxel.VoxelMesher.z(quad) * scale;
        float loS = s == 0 ? x : s == 1 ? y : z, loT = t == 0 ? x : t == 1 ? y : z;
        float hiS = loS + com.gbwater.rtxremix.horizons.voxel.VoxelMesher.width(quad) * scale;
        float hiT = loT + com.gbwater.rtxremix.horizons.voxel.VoxelMesher.height(quad) * scale;
        float plane = (axis == 0 ? x : axis == 1 ? y : z) + (SIDES[side].sign > 0 ? scale : 0);
        int fluidTop =
                com.gbwater.rtxremix.horizons.voxel.VoxelSection.fluidTop(
                        com.gbwater.rtxremix.horizons.voxel.VoxelMesher.cell(quad));
        if (water && fluidTop != 0) {
            float adjustment = fluidTop / 9f - scale;
            if (side == 1) plane += adjustment;
            else if (axis != 1) {
                if (s == 1) hiS += adjustment;
                else hiT += adjustment;
            }
        }
        for (int i = 0; i < 4; i++) {
            result[i * 3 + axis] = plane;
            result[i * 3 + s] = (i == 1 || i == 2) ? hiS : loS;
            result[i * 3 + t] = i >= 2 ? hiT : loT;
        }
    }

    private static void writeQuad(
            Page page,
            MaterialCatalog.Layer layer,
            int side,
            float[] p,
            int originX,
            int originY,
            int originZ,
            float waterTileSize) {
        var direction = SIDES[side];
        int axis = direction.axis;
        float ax = p[3] - p[0], ay = p[4] - p[1], az = p[5] - p[2];
        float bx = p[6] - p[0], by = p[7] - p[1], bz = p[8] - p[2];
        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        float alignment = (axis == 0 ? nx : axis == 1 ? ny : nz) * direction.sign;
        boolean reverse = alignment < 0;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (!(length > 0)) return;
        int materialIndex = page.materialIndex(layer, axis, originX, originY, originZ, waterTileSize);
        for (int vertex = 0; vertex < 4; vertex++) {
            int i = (reverse ? (4 - vertex) & 3 : vertex) * 3;
            page.include(p[i], p[i + 1], p[i + 2]);
            page.vertices.putFloat(p[i]).putFloat(p[i + 1]).putFloat(p[i + 2]);
            page.vertices.putInt(materialIndex);
        }
        page.vertexCount += 4;
        page.indexCount += 6;
    }
}
