/* SPDX-License-Identifier: LGPL-3.0-only */
package org.edtp.kavora.horizon;

import static org.junit.jupiter.api.Assertions.*;

import org.edtp.kavora.horizon.voxel.*;
import org.edtp.kavora.sdk.HardcodedVertex;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CancellationException;

class TerrainMeshConverterTest {
    private static final TerrainMaterial.SurfaceMaterial SURFACE =
            new TerrainMaterial.SurfaceMaterial(0, false, false);
    private static final MaterialCatalog.Face FACE =
            new MaterialCatalog.Face(
                    new TerrainMaterial.FaceMaterial(
                            SURFACE, -1, .25f, .5f, .5f, .75f, .25f, .5f, .25f, 0, 0, .25f),
                    0xff97b6ce);

    private static TerrainMeshConverter.Cursor cursor(long key, long... quads) {
        return new TerrainMeshConverter.Cursor(
                new VoxelMesher.Mesh(key, 1, quads), 10, () -> false);
    }

    /** Decode the wire layout to check the original geometric and material contracts. */
    private static ByteBuffer decoded(TerrainMeshConverter.Page page) {
        var out = ByteBuffer.allocate(page.vertexCount * HardcodedVertex.STORAGE_STRIDE).order(ByteOrder.LITTLE_ENDIAN);
        for (int vertex = 0; vertex < page.vertexCount; vertex++) {
            int q = (vertex & ~3) * 16;
            float ax = page.vertices.getFloat(q + 16) - page.vertices.getFloat(q);
            float ay = page.vertices.getFloat(q + 20) - page.vertices.getFloat(q + 4);
            float az = page.vertices.getFloat(q + 24) - page.vertices.getFloat(q + 8);
            float bx = page.vertices.getFloat(q + 32) - page.vertices.getFloat(q);
            float by = page.vertices.getFloat(q + 36) - page.vertices.getFloat(q + 4);
            float bz = page.vertices.getFloat(q + 40) - page.vertices.getFloat(q + 8);
            float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            int v = vertex * 16, m = page.vertices.getInt(v + 12) * 40;
            int axis = page.attributes.getInt(m + 36);
            float s = page.vertices.getFloat(v + (axis + 1) % 3 * 4);
            float t = page.vertices.getFloat(v + (axis + 2) % 3 * 4);
            out.putFloat(page.vertices.getFloat(v)).putFloat(page.vertices.getFloat(v + 4)).putFloat(page.vertices.getFloat(v + 8));
            out.putFloat(nx / length).putFloat(ny / length).putFloat(nz / length);
            out.putFloat(page.attributes.getFloat(m) + page.attributes.getFloat(m + 8) * s + page.attributes.getFloat(m + 16) * t);
            out.putFloat(page.attributes.getFloat(m + 4) + page.attributes.getFloat(m + 12) * s + page.attributes.getFloat(m + 20) * t);
            out.putInt(page.attributes.getInt(m + 32)).putInt(page.attributes.getInt(m + 24)).putInt(page.attributes.getInt(m + 28));
        }
        return out.flip();
    }

    @Test
    void interleavedSurfaceCategoriesProduceDensePagesWithoutLosingFaces() {
        long[] quads = new long[500];
        for (int i = 0; i < quads.length; i++)
            quads[i] = VoxelMesher.pack(i % 32, i / 32, 0, 3, 1, 1, 1 + i % 5);
        var cursor = cursor(0, quads);
        assertTrue(
                cursor.resolve(
                        key ->
                                new MaterialCatalog.Face(
                                        new TerrainMaterial.FaceMaterial(
                                                new TerrainMaterial.SurfaceMaterial(
                                                        key >>> 3, false, false),
                                                -1,
                                                .25f,
                                                .5f,
                                                .5f,
                                                .75f,
                                                .25f,
                                                .5f,
                                                .25f,
                                                0,
                                                0,
                                                .25f),
                                        0xffffffff)));
        int pages = 0, indices = 0;
        while (!cursor.isComplete()) {
            try (var result = cursor.nextBatch(4)) {
                pages += result.pages.size();
                for (var page : result.pages) indices += page.indexCount;
            }
        }
        assertEquals(5, pages);
        assertEquals(3000, indices);
    }

    @Test
    void sixDirectionsPreserveNormalsTintAndFullMaterialIdentity() {
        for (int side = 0; side < 6; side++) {
            final int direction = side;
            var cursor = cursor(0, VoxelMesher.pack(0, 4, 0, side, 16, 16, 50000));
            assertTrue(
                    cursor.resolve(
                            key -> {
                                assertEquals(50000, key >>> 3);
                                assertEquals(direction, key & 7);
                                return FACE;
                            }));
            try (var result = cursor.nextBatch(8)) {
                assertEquals(92, result.geometryBytes());
                var vertices = decoded(result.pages.getFirst());
                var expected = TerrainMaterial.Side.values()[side];
                for (int p = 0; p < vertices.limit(); p += HardcodedVertex.STORAGE_STRIDE) {
                    assertEquals(
                            expected.sign, vertices.getFloat(p + 12 + expected.axis * 4), 1e-6);
                    assertEquals(0xff97b6ce, vertices.getInt(p + 32));
                }
            }
        }
    }

    @Test
    void coarseFaceRepeatsAtlasTextureAtWorldBlockScale() {
        var cursor = cursor(VoxelKey.of(2, 0, 0, 0), VoxelMesher.pack(0, 4, 0, 1, 4, 4, 1));
        assertTrue(cursor.resolve(key -> FACE));
        try (var result = cursor.nextBatch(8)) {
            var vertices = decoded(result.pages.getFirst());
            float min = Float.POSITIVE_INFINITY, max = -min;
            for (int p = 0; p < vertices.limit(); p += HardcodedVertex.STORAGE_STRIDE) {
                min = Math.min(min, vertices.getFloat(p + 24));
                max = Math.max(max, vertices.getFloat(p + 24));
                assertEquals(HardcodedVertex.packHalf2x16(.25f, .5f), vertices.getInt(p + 36));
            }
            assertEquals(16, (max - min) / .25f, 1e-6);
        }
    }

    @Test
    void fluidWorldHeightIsIdenticalAcrossEveryLodAndUsesTheSameNormalPhase() {
        var water =
                new MaterialCatalog.Face(
                        new TerrainMaterial.FaceMaterial(
                                new TerrainMaterial.SurfaceMaterial(2, false, true),
                                0,
                                0,
                                0,
                                1,
                                1,
                                0,
                                0,
                                1,
                                0,
                                0,
                                1),
                        -1);
        for (int level = 0; level <= VoxelKey.ROOT_LEVEL; level++) {
            int scale = 1 << level, base = 9 / scale, top = 89 - base * scale * 9;
            var cursor =
                    cursor(
                            VoxelKey.of(level, 0, 0, 0),
                            VoxelMesher.pack(
                                    0, base, 0, 1, 1, 1, VoxelSection.withFluidTop(1, top)));
            assertTrue(cursor.resolve(key -> water));
            try (var result = cursor.nextBatch(8)) {
                var vertices = decoded(result.pages.getFirst());
                for (int p = 0; p < vertices.limit(); p += HardcodedVertex.STORAGE_STRIDE) {
                    assertEquals(89 / 9f, vertices.getFloat(p + 4), 1e-6);
                    assertEquals(1, vertices.getFloat(p + 16), 1e-6);
                    assertEquals(vertices.getFloat(p) / 10, vertices.getFloat(p + 24), 1e-6);
                    assertEquals(vertices.getFloat(p + 8) / 10, vertices.getFloat(p + 28), 1e-6);
                }
            }
        }
    }

    @Test
    void everyUploadBatchAndPageIsBoundedWithoutLosingTriangles() {
        long[] quads = new long[6000];
        for (int i = 0; i < quads.length; i++)
            quads[i] = VoxelMesher.pack(i % 32, i / 1024, i / 32 % 32, 1, 1, 1, 1);
        var cursor = cursor(0, quads);
        assertTrue(cursor.resolve(key -> FACE));
        int triangles = 0, pages = 0;
        while (!cursor.isComplete())
            try (var result = cursor.nextBatch(2)) {
                assertTrue(result.pages.size() <= 2);
                for (var page : result.pages) {
                    assertTrue(page.geometryBytes() <= TerrainMeshConverter.PAGE_BYTES);
                    triangles += page.indexCount / 3;
                    pages++;
                }
            }
        assertEquals(12000, triangles);
        assertTrue(pages >= 2);
    }

    @Test
    void unresolvedPaletteCannotPublishAndCancellationStopsBeforeAllocatingPages() {
        var cursor =
                cursor(
                        0,
                        VoxelMesher.pack(0, 0, 0, 1, 1, 1, 1),
                        VoxelMesher.pack(1, 0, 0, 1, 1, 1, 2));
        assertFalse(cursor.resolve(key -> key >>> 3 == 1 ? FACE : null));
        assertThrows(IllegalStateException.class, () -> cursor.nextBatch(4));
        assertTrue(cursor.resolve(key -> FACE));
        try (var result = cursor.nextBatch(4)) {
            assertEquals(12, result.pages.getFirst().indexCount);
        }
        var cancelled =
                new TerrainMeshConverter.Cursor(
                        new VoxelMesher.Mesh(0, 1, new long[0]), 10, () -> true);
        assertThrows(CancellationException.class, () -> cancelled.resolve(key -> FACE));
    }
}
