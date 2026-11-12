/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import org.edtp.kavora.KavoraMod;
import org.edtp.kavora.particle.KavoraParticleRenderer.PreparedFrame;
import org.edtp.kavora.profiling.PerformanceTimer;
import org.edtp.kavora.profiling.ProfileScope;
import org.edtp.kavora.render.RenderOrigin;
import org.edtp.kavora.sdk.RemixAPI;
import org.edtp.kavora.texture.ParticleAtlasManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Owns the one-frame particle handoff. Only the render thread calls this class.
 * The worker prepares CPU data. The render thread publishes it before the next
 * scene submit and discards it before the renderer or atlas is cleared.
 */
public final class ParticleFramePipeline {
    private static final Logger LOGGER = LoggerFactory.getLogger(ParticleFramePipeline.class);

    private final PerformanceTimer perfTimer;
    private KavoraParticleRenderer particleRenderer;
    private final ExecutorService particleExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Kavora Particles");
        thread.setDaemon(true);
        return thread;
    });
    private CompletableFuture<ParticleRenderResult> particleRenderFuture;
    // Double-buffer: holds frame N's prepared particles for submission during frame N+1.
    private PreparedFrame pendingParticleFrame;
    private long particleNanosThisFrame;
    /** World the pending payload was prepared against, so a swap cannot publish it. */
    private ClientLevel pendingParticleFrameWorld;

    private int perfParticles = -1;
    private int cntParticles = -1;
    private int cntParticleDraws = -1;
    private int cntParticleDynamicQuads = -1;
    private int cntParticleNewSlots = -1;
    private int cntParticleRemovedSlots = -1;
    private int cntParticleCapacityGrows = -1;
    private int cntParticleCapacity = -1;
    private int cntParticleMeshUpdates = -1;

    public ParticleFramePipeline(PerformanceTimer perfTimer) {
        this.perfTimer = perfTimer;
    }

    public void attach(KavoraParticleRenderer renderer) {
        particleRenderer = renderer;
    }

    public void registerMetrics() {
        perfParticles = perfTimer.registerSection("Particles");
        cntParticles = perfTimer.registerCounter("Ptcl.Count");
        cntParticleDraws = perfTimer.registerCounter("Ptcl.MeshBuckets");
        cntParticleDynamicQuads = perfTimer.registerCounter("Ptcl.DynamicQuads");
        cntParticleNewSlots = perfTimer.registerCounter("Ptcl.NewSlots");
        cntParticleRemovedSlots = perfTimer.registerCounter("Ptcl.RemovedSlots");
        cntParticleCapacityGrows = perfTimer.registerCounter("Ptcl.CapacityGrows");
        cntParticleCapacity = perfTimer.registerCounter("Ptcl.Capacity");
        cntParticleMeshUpdates = perfTimer.registerCounter("Ptcl.MeshUpdates");
    }

    /** Starts CPU preparation after the pending payload has been consumed or discarded. */
    public void startPrepare(ParticleAtlasManager particleAtlasManager, float tickDelta, long frameIndex) {
        if (!KavoraMod.isParticlesEnabled() || particleRenderer == null || particleAtlasManager == null) {
            if (particleRenderer != null) particleRenderer.invalidateMotionHistory();
            return;
        }

        if (!particleAtlasManager.isInitialized()) {
            particleAtlasManager.initialize();
        }
        if (!particleAtlasManager.isInitialized()) {
            return;
        }

        RenderOrigin origin = RenderOrigin.current();
        particleRenderFuture = CompletableFuture.supplyAsync(
                () -> renderParticlesOnWorker(tickDelta, frameIndex, origin),
                particleExecutor);
    }

    private ParticleRenderResult renderParticlesOnWorker(float tickDelta, long frameIndex, RenderOrigin origin) {
        RemixAPI.tracyThreadName("Particles");
        long previousFrameIndex = ProfileScope.setFrameIndexForCurrentThread(frameIndex);
        PreparedFrame preparedFrame = null;
        // The scope records the worker's own duration on the worker thread, which is where it
        // belongs. It is deliberately not returned to the render thread: it is off the critical
        // path, so charging it to a render-thread section overstates that section.
        try (ProfileScope particleScope = ProfileScope.begin("Particles")) {
            preparedFrame = particleRenderer.prepare(tickDelta, origin);
            particleScope
                    .captureCount(preparedFrame.particleCount())
                    .drawCalls(preparedFrame.plannedDrawCount())
                    .vertexCount(preparedFrame.plannedActiveQuadCount() * 4);
            return new ParticleRenderResult(preparedFrame, null);
        } catch (Exception e) {
            if (preparedFrame != null) preparedFrame.close();
            return new ParticleRenderResult(null, e);
        } catch (Error e) {
            if (preparedFrame != null) preparedFrame.close();
            throw e;
        } finally {
            ProfileScope.restoreFrameIndexForCurrentThread(previousFrameIndex);
        }
    }

    /**
     * Joins the particle worker and releases its payload without publishing it. Used on the
     * frame error path and by every teardown path, where publishing transient native assets
     * moments before {@link #clearResources()} would leak them.
     */
    public void discardInFlight() {
        CompletableFuture<ParticleRenderResult> future = particleRenderFuture;
        if (future == null) {
            return;
        }

        try {
            ParticleRenderResult result = future.join();
            PreparedFrame preparedFrame = result.preparedFrame();
            if (preparedFrame != null) {
                preparedFrame.close();
            }
            if (result.error() != null) {
                LOGGER.error("Error rendering particles", result.error());
            }
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            LOGGER.error("Error rendering particles", cause);
        } finally {
            if (particleRenderer != null) particleRenderer.invalidateMotionHistory();
            particleRenderFuture = null;
        }
    }

    /** Submits the frame prepared by the previous render call. No blocking: the work is already done. */
    public void submitPendingFrame(ClientLevel world) {
        // Portals can swap worlds without disconnecting or rendering a null world.
        if (pendingParticleFrame != null && pendingParticleFrameWorld != world) {
            discardPendingFrame();
        }
        PreparedFrame frame = pendingParticleFrame;
        pendingParticleFrame = null;
        if (frame == null) return;

        long submitStart = System.nanoTime();
        try (ProfileScope scope = ProfileScope.begin("Particles.Submit")) {
            particleRenderer.submit(frame);
            scope.captureCount(particleRenderer.getLastParticleCount())
                 .drawCalls(particleRenderer.getLastDrawCount())
                 .vertexCount(particleRenderer.getLastActiveQuadCount() * 4);
            recordParticleCounters();
        } catch (Exception e) {
            particleRenderer.invalidateMotionHistory();
            LOGGER.error("Error submitting particles", e);
        } finally {
            frame.close();
            particleNanosThisFrame += System.nanoTime() - submitStart;
        }
    }

    /**
     * Joins the particle worker after the scene submit and stores the result for the
     * next frame's submit. Never submits native Remix calls, which happen in
     * {@link #submitPendingFrame(ClientLevel)}.
     *
     * <p>The frame timer charges the join wait. The worker records its own duration
     * in the worker profiling scope.
     *
     * @param preparedFor the world the prepare task was dispatched against, recorded with the
     *                    payload so the next frame can refuse it after a world swap
     */
    public void finishAfterSubmit(ClientLevel preparedFor) {
        CompletableFuture<ParticleRenderResult> future = particleRenderFuture;
        if (future == null) return;
        try {
            long waitStart = System.nanoTime();
            ParticleRenderResult result = future.join();
            particleNanosThisFrame += System.nanoTime() - waitStart;

            if (result.error() != null) {
                if (particleRenderer != null) particleRenderer.invalidateMotionHistory();
                LOGGER.error("Error rendering particles", result.error());
            } else {
                pendingParticleFrame = result.preparedFrame();
                pendingParticleFrameWorld = preparedFor;
            }
        } catch (CompletionException e) {
            if (particleRenderer != null) particleRenderer.invalidateMotionHistory();
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            LOGGER.error("Error rendering particles", cause);
        } finally {
            particleRenderFuture = null;
        }
    }

    /**
     * Publishes the frame's total particle cost as one sample. {@code injectNanos} overwrites
     * rather than accumulates, so the previous payload's submit and the wait for
     * this frame's worker are combined in one sample.
     */
    public void recordFrameTiming() {
        perfTimer.injectNanos(perfParticles, particleNanosThisFrame);
        particleNanosThisFrame = 0L;
    }

    /** Closes the pending particle frame without submitting it. Used during cleanup and world changes. */
    public void discardPendingFrame() {
        PreparedFrame frame = pendingParticleFrame;
        pendingParticleFrame = null;
        pendingParticleFrameWorld = null;
        if (frame != null) frame.close();
        if (particleRenderer != null) particleRenderer.invalidateMotionHistory();
    }

    private void recordParticleCounters() {
        perfTimer.count(cntParticles, particleRenderer.getLastParticleCount());
        perfTimer.count(cntParticleDraws, particleRenderer.getLastDrawCount());
        perfTimer.count(cntParticleDynamicQuads, particleRenderer.getLastActiveQuadCount());
        perfTimer.count(cntParticleNewSlots, particleRenderer.getLastNewSlotCount());
        perfTimer.count(cntParticleRemovedSlots, particleRenderer.getLastRemovedSlotCount());
        perfTimer.count(cntParticleCapacityGrows, particleRenderer.getLastCapacityGrowCount());
        perfTimer.count(cntParticleCapacity, particleRenderer.getLastCapacityCount());
        perfTimer.count(cntParticleMeshUpdates, particleRenderer.getLastMeshUpdateCount());
    }

    /** A successful prepare returns a payload. A failed prepare closes it and returns the error. */
    private record ParticleRenderResult(
            PreparedFrame preparedFrame,
            Exception error) {}

    /** Call after discarding both the in-flight and the pending payload. */
    public void clearResources() {
        if (particleRenderer != null) particleRenderer.cleanup();
    }

    public void shutdownRenderer() {
        var resource = particleRenderer;
        particleRenderer = null;
        if (resource != null) resource.cleanup();
    }

    public void shutdownWorker() {
        particleExecutor.shutdown();
        try {
            if (!particleExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                particleExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            particleExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
