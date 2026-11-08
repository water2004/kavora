/* SPDX-License-Identifier: LGPL-3.0-only */
package com.gbwater.rtxremix.horizons;

import static org.junit.jupiter.api.Assertions.*;

import com.gbwater.rtxremix.horizons.voxel.VoxelMesher;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

class TerrainMeshStreamTest {
    private static final class Worker implements Executor {
        final ArrayDeque<Runnable> work = new ArrayDeque<>();

        public void execute(Runnable task) {
            work.addLast(task);
        }

        void drain() {
            while (!work.isEmpty()) work.removeFirst().run();
        }
    }

    private static TerrainMeshConverter.Cursor cursor() {
        long[] quads = new long[12];
        for (int i = 0; i < quads.length; i++) quads[i] = VoxelMesher.pack(i, 0, 0, 1, 1, 1, i + 1);
        var cursor = new TerrainMeshConverter.Cursor(new VoxelMesher.Mesh(0, 1, quads), 10, () -> false);
        assertTrue(cursor.resolve(key -> new MaterialCatalog.Face(
                new TerrainMaterial.FaceMaterial(
                        new TerrainMaterial.SurfaceMaterial(key >>> 3, false, false), -1,
                        0, 0, 1, 1, 0, 0, 1, 0, 0, 1), -1)));
        return cursor;
    }

    @Test
    void workersPrefillAndResumeWithoutWaitingForAnotherRenderTick() {
        var worker = new Worker();
        var budget = new TerrainMeshStream.Budget(8);
        try (var stream = new TerrainMeshStream(cursor(), worker, budget)) {
            stream.resume();
            worker.drain();
            assertEquals(2, budget.batches());
            int indices = 0;
            while (!stream.isComplete()) {
                try (var batch = stream.poll()) {
                    assertNotNull(batch);
                    for (var page : batch.result.pages) indices += page.indexCount;
                }
                worker.drain();
            }
            assertEquals(72, indices);
            assertEquals(0, budget.batches());
        }
    }

    @Test
    void cancellationReleasesQueuedBatchesButKeepsAnUploadersLoanAlive() {
        var worker = new Worker();
        var budget = new TerrainMeshStream.Budget(2);
        var stream = new TerrainMeshStream(cursor(), worker, budget);
        stream.resume();
        worker.drain();
        var loan = stream.poll();
        assertNotNull(loan);
        stream.close();
        stream.close();
        assertEquals(1, budget.batches());
        assertNotNull(loan.result.pages.getFirst().vertices);
        loan.close();
        loan.close();
        assertEquals(0, budget.batches());
    }

    @Test
    void sharedStagingLimitAlsoCountsScheduledWorkersAndCancelledResults() {
        var worker = new Worker();
        var budget = new TerrainMeshStream.Budget(2);
        var a = new TerrainMeshStream(cursor(), worker, budget);
        var b = new TerrainMeshStream(cursor(), worker, budget);
        var c = new TerrainMeshStream(cursor(), worker, budget);
        a.resume();
        b.resume();
        c.resume();
        assertEquals(2, budget.batches());
        a.close();
        b.close();
        worker.drain();
        assertEquals(0, budget.batches());
        c.resume();
        worker.drain();
        assertEquals(2, budget.batches());
        c.close();
        assertEquals(0, budget.batches());
    }

    @Test
    void rejectedWorkReturnsItsStagingReservationAndSurfacesTheError() {
        var budget = new TerrainMeshStream.Budget(1);
        try (var stream = new TerrainMeshStream(cursor(), task -> {
            throw new RejectedExecutionException();
        }, budget)) {
            stream.resume();
            assertEquals(0, budget.batches());
            assertThrows(IllegalStateException.class, stream::poll);
        }
    }
}
