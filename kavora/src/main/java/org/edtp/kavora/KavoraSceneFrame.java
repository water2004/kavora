/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.edtp.kavora.asset.KavoraInstanceStore;
import org.edtp.kavora.asset.KavoraLightStore;
import org.edtp.kavora.asset.KavoraMaterialStore;
import org.edtp.kavora.asset.KavoraMeshStore;
import org.edtp.kavora.asset.KavoraTextureStore;
import org.edtp.kavora.benchmark.BenchmarkController;
import org.edtp.kavora.entity.KavoraBlockEntityPass;
import org.edtp.kavora.entity.KavoraEntityPass;
import org.edtp.kavora.entity.KavoraEntitySubmitter;
import org.edtp.kavora.entity.KavoraFirstPersonPass;
import org.edtp.kavora.light.LightManager;
import org.edtp.kavora.logic.AtmosphereDimension;
import org.edtp.kavora.particle.ParticleFramePipeline;
import org.edtp.kavora.profiling.ProfileScope;
import org.edtp.kavora.render.KavoraFramePresentation;
import org.edtp.kavora.render.KavoraInterop;
import org.edtp.kavora.sdk.RemixAPI;
import org.edtp.kavora.sdk.RemixException;
import org.edtp.kavora.terrain.ChunkManager;
import org.edtp.kavora.texture.BlockTextureManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Submits one captured world frame on the render thread. */
final class KavoraSceneFrame {
    private static final Logger LOGGER = LoggerFactory.getLogger(KavoraSceneFrame.class);

    private final KavoraInterop interop;
    private final KavoraFramePresentation presentation;
    private final ChunkManager chunkManager;
    private final LightManager lightManager;
    private final KavoraEntityPass entityPass;
    private final KavoraEntitySubmitter entitySubmitter;
    private final KavoraBlockEntityPass blockEntityPass;
    private final KavoraFirstPersonPass firstPersonPass;
    private final KavoraSceneEffects effects;
    private final BlockTextureManager textureManager;
    private final ParticleFramePipeline particles;
    private final KavoraFrameMetrics perfTimer;
    private final BenchmarkController benchmarkController;
    private boolean renderThreadNamed;

    KavoraSceneFrame(
            KavoraInterop interop, KavoraFramePresentation presentation,
            ChunkManager chunkManager, LightManager lightManager,
            KavoraEntityPass entityPass, KavoraEntitySubmitter entitySubmitter,
            KavoraBlockEntityPass blockEntityPass, KavoraFirstPersonPass firstPersonPass,
            KavoraSceneEffects effects, BlockTextureManager textureManager,
            ParticleFramePipeline particles,
            KavoraFrameMetrics perfTimer, BenchmarkController benchmarkController) {
        this.interop = interop;
        this.presentation = presentation;
        this.chunkManager = chunkManager;
        this.lightManager = lightManager;
        this.entityPass = entityPass;
        this.entitySubmitter = entitySubmitter;
        this.blockEntityPass = blockEntityPass;
        this.firstPersonPass = firstPersonPass;
        this.effects = effects;
        this.textureManager = textureManager;
        this.particles = particles;
        this.perfTimer = perfTimer;
        this.benchmarkController = benchmarkController;
    }

    void renderAndSubmitFrame(long cameraUpdateNanos) {
        if (!renderThreadNamed) {
            RemixAPI.tracyThreadName("Render");
            renderThreadNamed = true;
        }

        perfTimer.beginFrame(interop);

        boolean entityFrameClosed = false;
        try (ProfileScope frameScope = ProfileScope.begin("renderAndSubmitFrame")) {
            Minecraft client = Minecraft.getInstance();
            ClientLevel world = client.level;

            if (world == null || client.gameRenderer == null) {
                if (chunkManager != null) chunkManager.hideRetainedInstances();
                particles.discardPendingFrame();
                // Closed by the finally, like the RTX-disabled branch below. endFrame is not
                // idempotent: it re-adds elapsed from the same frame start and refolds min,
                // max and the frame count.
                return;
            }

            // Vanilla draws this frame. The presentation owner still completes the
            // shared-image handoff for the host frame.
            if (!KavoraMod.isRtxEnabled()) {
                if (chunkManager != null) chunkManager.hideRetainedInstances();
                // A payload prepared before the toggle holds world-space vertices from the
                // old camera position; publishing it on re-enable would draw stale geometry.
                particles.discardPendingFrame();
                return;
            }

            Camera camera = client.gameRenderer.mainCamera();
            float tickDelta = client.getDeltaTracker().getGameTimeDeltaPartialTick(true);

            perfTimer.injectNanos(perfTimer.perfCamera, cameraUpdateNanos);

            Vec3 cameraPos = camera.position();
            SceneEffectSchedule.beforeGeometry(effects, world, camera, tickDelta);

            if (KavoraMod.isWorldEnabled()) {
                try {
                    chunkManager.updateVisibleChunks(world, cameraPos, perfTimer, perfTimer.perfChunkGen, perfTimer.perfChunkDraw);
                } catch (Exception e) {
                    LOGGER.error("Error updating chunks", e);
                }
            } else {
                chunkManager.hideRetainedInstances();
            }

            entitySubmitter.beginFrame();
            try {
                if (KavoraMod.isWorldEnabled() && KavoraMod.isBlockEntitiesEnabled()) {
                    boolean scoping = client.player != null && client.player.isScoping();
                    var beams = chunkManager.farBeaconBeams();
                    for (int i = 0; i < beams.size(); i++) {
                        blockEntityPass.submitFarBeacon(beams.get(i), cameraPos, scoping, entitySubmitter);
                    }
                }
                if (KavoraMod.isEntitiesEnabled()) {
                    perfTimer.begin(perfTimer.perfEntities);
                    try (ProfileScope entitiesScope = ProfileScope.begin("Entities")) {
                        KavoraEntityPass.FrameStats stats =
                                entityPass.finishFrame(
                                        world,
                                        cameraPos,
                                        tickDelta,
                                        lightManager.getFirstPersonShadowModelOffset(),
                                        entitySubmitter);
                        perfTimer.recordEntities(entitiesScope, stats);
                    } catch (Exception e) {
                        LOGGER.error("Error rendering entities", e);
                    } finally {
                        perfTimer.end(perfTimer.perfEntities);
                    }
                }

                // The first-person pass is captured from GameRenderer.renderHand just
                // before this scene is submitted. It intentionally uses the same
                // entity mesh/material path, but with a camera-to-world root transform.
                if (firstPersonPass != null && firstPersonPass.hasPendingCapture()) {
                    try (ProfileScope firstPersonScope = ProfileScope.begin("FirstPersonModel")) {
                        firstPersonPass.submitPending(entitySubmitter);
                    }
                }
            } finally {
                closeEntityFrame();
                entityFrameClosed = true;
            }
            SceneEffectSchedule.afterGeometry(effects, client, world, camera, cameraPos, tickDelta);

            // Forward atlas animation patches to Remix before presenting
            if (textureManager != null) {
                perfTimer.begin(perfTimer.perfAtlas);
                try (ProfileScope atlasScope = ProfileScope.begin("Atlas")) {
                    textureManager.flushAnimations(AtmosphereDimension.of(world.dimension()));
                } finally {
                    perfTimer.end(perfTimer.perfAtlas);
                }
            }

            perfTimer.begin(perfTimer.perfSubmit);
            try (ProfileScope submitScope = ProfileScope.begin("Submit")) {
                SceneEffectSchedule.beforeSubmit(world, cameraPos, tickDelta);
                presentation.submitScene();
            } finally {
                perfTimer.end(perfTimer.perfSubmit);
            }

            SceneEffectSchedule.afterSubmit(effects, world);

        } catch (RemixException e) {
            LOGGER.error("Remix error during frame render: {} (code: {})",
                e.getMessage(), e.getErrorCode(), e);
        } catch (Exception e) {
            LOGGER.error("Error during frame render", e);
        } finally {
            // In-place capture during vanilla's world render opens the entity frame before
            // this method runs, so a frame that aborts above the bracket would otherwise
            // leave it open.
            if (!entityFrameClosed) {
                try {
                    closeEntityFrame();
                } catch (RuntimeException e) {
                    LOGGER.error("Error closing the entity frame", e);
                }
            }
            particles.discardInFlight();
            particles.recordFrameTiming();
            try (ProfileScope gcScope = ProfileScope.begin("gcAssetStores")) {
                gcAssetStores();
            }
            perfTimer.finishFrame();
            benchmarkController.onFrameEnd(perfTimer);
        }
    }

    /**
     * Retires entity parts and closes the block-entity frame, publishing its counters
     * while the block-entity toggle is on so a disabled frame contributes no zero sample.
     * Block entities are captured and submitted in place during vanilla's world render,
     * inside the entity frame this closes. Retirement runs before Submit: an instance
     * destroyed after the submit is still in that frame's scene.
     */
    private void closeEntityFrame() {
        entitySubmitter.endFrame();
        KavoraBlockEntityPass.FrameStats stats = blockEntityPass.finishFrame();
        if (!KavoraMod.isBlockEntitiesEnabled()) {
            return;
        }
        perfTimer.recordBlockEntities(stats);
    }

    private static void gcAssetStores() {
        if (KavoraMeshStore.get() != null)      KavoraMeshStore.get().gc();
        if (KavoraInstanceStore.get() != null)  KavoraInstanceStore.get().gc();
        if (KavoraTextureStore.get() != null)   KavoraTextureStore.get().gc();
        if (KavoraMaterialStore.get() != null)  KavoraMaterialStore.get().gc();
        if (KavoraLightStore.get() != null)     KavoraLightStore.get().gc();
    }
}
