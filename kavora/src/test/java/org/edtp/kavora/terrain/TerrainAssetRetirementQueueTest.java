/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.terrain;

import org.edtp.kavora.sdk.HardcodedVertex;
import org.edtp.kavora.asset.KavoraInstance;
import org.edtp.kavora.asset.KavoraMesh;
import org.edtp.kavora.asset.KavoraMeshSurface;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainAssetRetirementQueueTest {

    private static final long MIB = 1024L * 1024L;

    @Test
    void retiresInstanceBeforeMeshWithOneSceneFrameOfGrace() {
        TerrainAssetRetirementQueue queue = new TerrainAssetRetirementQueue(() -> 0L, new TerrainNativeCallStats());
        KavoraMesh mesh = oneQuadMesh();
        KavoraInstance instance = new KavoraInstance(mesh);

        queue.retire(instance);
        assertEquals(mesh.estimatedNativeBytes(), queue.pendingRetirementBytes());

        queue.drainFrame();
        assertTrue(instance.isDeleted());
        assertFalse(mesh.isDeleted());
        assertEquals(1, queue.pendingRetirements());
        assertEquals(mesh.estimatedNativeBytes(), queue.pendingRetirementBytes());

        queue.drainFrame();
        assertTrue(mesh.isDeleted());
        assertEquals(0, queue.pendingRetirements());
        assertEquals(0, queue.pendingRetirementBytes());
    }

    @Test
    void hardRetirementPressureStillHonorsElapsedDeadline() {
        AtomicInteger clockRead = new AtomicInteger();
        LongSupplier clock = () -> clockRead.getAndIncrement() < 2 ? 0L : 1_000_000L;
        TerrainAssetRetirementQueue queue = new TerrainAssetRetirementQueue(clock, new TerrainNativeCallStats());

        int meshCount = 512;
        for (int i = 0; i < meshCount; i++) queue.retire(oneQuadMesh());

        queue.drainFrame();
        assertEquals(meshCount, queue.pendingRetirements());

        queue.drainFrame();
        assertEquals(meshCount - 1, queue.pendingRetirements(),
                "Hard pressure must not bypass the retirement time deadline");

        queue.clear();
        assertEquals(0, queue.pendingRetirements());
        assertEquals(0, queue.pendingRetirementBytes());
    }

    @Test
    void softPressureUsesHysteresisUntilTheQueueDrainsBelowHalf() {
        TerrainAssetRetirementQueue queue = new TerrainAssetRetirementQueue(() -> 0L, new TerrainNativeCallStats());
        for (int i = 0; i < 256; i++) queue.retire(oneQuadMesh());

        assertTrue(queue.retirementBackpressured());
        queue.drainFrame();
        assertTrue(queue.retirementBackpressured());

        queue.drainFrame();
        assertEquals(0, queue.pendingRetirements());
        assertFalse(queue.retirementBackpressured());
    }

    @Test
    void installGateTripsOnlyOnHardRetirementBytes() {
        TerrainAssetRetirementQueue queue = new TerrainAssetRetirementQueue(() -> 0L, new TerrainNativeCallStats());
        for (int i = 0; i < 512; i++) queue.retire(oneQuadMesh());
        assertTrue(queue.retirementBackpressured());
        assertFalse(queue.retirementBytesBackpressured(),
                "Item counts alone must never pause installs");
        queue.clear();

        queue.retire(meshOfBytes(256L * MIB));
        assertTrue(queue.retirementBackpressured());
        assertFalse(queue.retirementBytesBackpressured());

        queue.retire(meshOfBytes(256L * MIB));
        assertTrue(queue.retirementBytesBackpressured());

        queue.drainFrame();
        queue.drainFrame();
        assertEquals(1, queue.pendingRetirements());
        assertFalse(queue.retirementBytesBackpressured());
        assertTrue(queue.retirementBackpressured(), "Producer hysteresis is unchanged");

        queue.drainFrame();
        assertEquals(0, queue.pendingRetirements());
        assertFalse(queue.retirementBackpressured());
    }

    @Test
    void clearIgnoresFrameEligibilityAndBudgets() {
        AtomicInteger clockRead = new AtomicInteger();
        TerrainAssetRetirementQueue queue = new TerrainAssetRetirementQueue(
                () -> clockRead.getAndIncrement() == 0 ? 0L : Long.MAX_VALUE / 2,
                new TerrainNativeCallStats());
        KavoraMesh mesh = oneQuadMesh();
        KavoraInstance instance = new KavoraInstance(mesh);

        queue.retire(instance);
        queue.clear();

        assertTrue(instance.isDeleted());
        assertTrue(mesh.isDeleted());
        assertEquals(0, queue.pendingRetirements());
        assertEquals(0, queue.pendingRetirementBytes());
        assertFalse(queue.retirementBackpressured());
    }

    private static KavoraMesh oneQuadMesh() {
        return new KavoraMesh(new KavoraMeshSurface[]{oneQuadSurface()});
    }

    /** A mesh that reports the given native footprint without allocating it. */
    private static KavoraMesh meshOfBytes(long estimatedNativeBytes) {
        return new KavoraMesh(new KavoraMeshSurface[]{oneQuadSurface()}) {
            @Override
            public long estimatedNativeBytes() {
                return estimatedNativeBytes;
            }
        };
    }

    private static KavoraMeshSurface oneQuadSurface() {
        return new KavoraMeshSurface(null)
                .setVertexData(ByteBuffer.allocate(4 * HardcodedVertex.STRIDE), 4)
                .setIndexData(ByteBuffer.allocate(6 * Integer.BYTES), 6);
    }
}
