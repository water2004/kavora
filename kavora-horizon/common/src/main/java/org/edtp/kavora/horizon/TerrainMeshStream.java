/* SPDX-License-Identifier: LGPL-3.0-only */
package org.edtp.kavora.horizon;

import org.edtp.kavora.profiling.ProfileScope;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

/** Bounded worker production continues independently of the render thread's upload cadence. */
final class TerrainMeshStream implements AutoCloseable {
    static final class Budget {
        private final int limit;
        private final Semaphore slots;

        Budget(int limit) {
            if (limit < 1) throw new IllegalArgumentException("Empty staging budget");
            this.limit = limit;
            slots = new Semaphore(limit);
        }

        int batches() {
            return limit - slots.availablePermits();
        }
    }

    final class Batch implements AutoCloseable {
        final TerrainMeshConverter.Result result;
        private boolean closed;

        private Batch(TerrainMeshConverter.Result result) {
            this.result = result;
        }

        @Override
        public void close() {
            synchronized (TerrainMeshStream.this) {
                if (closed) return;
                closed = true;
                result.close();
                held--;
                budget.slots.release();
                resume();
            }
        }
    }

    private final TerrainMeshConverter.Cursor cursor;
    private final Executor executor;
    private final Budget budget;
    private final ArrayDeque<Batch> ready = new ArrayDeque<>(2);
    private boolean running, finished, closed;
    private int held;
    private Throwable failure;

    TerrainMeshStream(TerrainMeshConverter.Cursor cursor, Executor executor, Budget budget) {
        this.cursor = cursor;
        this.executor = executor;
        this.budget = budget;
        finished = cursor.isComplete();
    }

    synchronized void resume() {
        if (closed || running || finished || failure != null || held >= 2
                || !budget.slots.tryAcquire()) return;
        held++;
        running = true;
        try {
            executor.execute(this::produce);
        } catch (RuntimeException | Error error) {
            running = false;
            held--;
            budget.slots.release();
            failure = error;
        }
    }

    private void produce() {
        TerrainMeshConverter.Result result = null;
        Throwable error = null;
        try (var ignored = ProfileScope.beginJfrOnly("Lod.Expand")) {
            result = cursor.nextBatch(4);
        } catch (RuntimeException | Error thrown) {
            error = thrown;
        }
        synchronized (this) {
            running = false;
            if (closed || error != null) {
                if (result != null) result.close();
                held--;
                budget.slots.release();
                if (!closed) failure = error;
            } else {
                ready.addLast(new Batch(result));
                finished = cursor.isComplete();
                resume();
            }
        }
    }

    synchronized Batch poll() {
        if (failure != null) throw new IllegalStateException("Voxel vertex expansion failed", failure);
        return ready.pollFirst();
    }

    synchronized boolean isComplete() {
        if (failure != null) throw new IllegalStateException("Voxel vertex expansion failed", failure);
        return finished && held == 0;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        for (Batch batch; (batch = ready.pollFirst()) != null; ) batch.close();
    }
}
