/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.terrain;

import org.edtp.kavora.sdk.HardcodedVertex;
import org.edtp.kavora.asset.KavoraMesh;
import org.edtp.kavora.asset.KavoraMeshSurface;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TerrainNativeCallStatsTest {

    @Test
    void talliesEachCallKind() {
        TerrainNativeCallStats stats = new TerrainNativeCallStats();
        KavoraMesh mesh = twoSurfaceMesh();

        stats.beginFrame();
        stats.pageCreated(mesh);
        stats.pageCreated(mesh);
        stats.instanceAdded();
        stats.instanceRemoved();
        stats.meshDestroyed();

        assertEquals(2, stats.pagesInstalled());
        assertEquals(2 * mesh.estimatedNativeBytes(), stats.installBytes());
        assertEquals(1, stats.instancesAdded());
        assertEquals(1, stats.instancesRemoved());
        assertEquals(1, stats.meshesDestroyed());
    }

    @Test
    void beginFrameDiscardsThePreviousFrame() {
        TerrainNativeCallStats stats = new TerrainNativeCallStats();
        KavoraMesh mesh = twoSurfaceMesh();

        stats.beginFrame();
        stats.pageCreated(mesh);
        stats.instanceAdded();
        stats.instanceRemoved();
        stats.meshDestroyed();
        stats.beginFrame();

        assertEquals(0, stats.pagesInstalled());
        assertEquals(0, stats.installBytes());
        assertEquals(0, stats.instancesAdded());
        assertEquals(0, stats.instancesRemoved());
        assertEquals(0, stats.meshesDestroyed());
    }

    private static KavoraMesh twoSurfaceMesh() {
        return new KavoraMesh(new KavoraMeshSurface[]{oneQuadSurface(), oneQuadSurface()});
    }

    private static KavoraMeshSurface oneQuadSurface() {
        return new KavoraMeshSurface(null)
                .setVertexData(ByteBuffer.allocate(4 * HardcodedVertex.STRIDE), 4)
                .setIndexData(ByteBuffer.allocate(6 * Integer.BYTES), 6);
    }
}
