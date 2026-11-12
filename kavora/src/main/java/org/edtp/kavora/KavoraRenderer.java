/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora;

import com.mojang.blaze3d.vertex.PoseStack;
import org.edtp.kavora.sdk.CameraInfo;
import org.edtp.kavora.sdk.CameraInfoParameterizedEXT;
import org.edtp.kavora.sdk.CameraType;
import org.edtp.kavora.sdk.RemixAPI;
import org.edtp.kavora.sdk.RemixException;
import org.edtp.kavora.sdk.VulkanClientFrameInfo;
import org.edtp.kavora.asset.KavoraInstanceStore;
import org.edtp.kavora.asset.KavoraLightStore;
import org.edtp.kavora.asset.KavoraMaterial;
import org.edtp.kavora.asset.KavoraMaterialStore;
import org.edtp.kavora.asset.KavoraMeshStore;
import org.edtp.kavora.asset.KavoraTexture;
import org.edtp.kavora.asset.KavoraTextureStore;
import org.edtp.kavora.benchmark.BenchmarkController;
import org.edtp.kavora.render.KavoraInterop;
import org.edtp.kavora.render.KavoraFramePresentation;
import org.edtp.kavora.render.RenderBackend;
import org.edtp.kavora.light.LightManager;
import org.edtp.kavora.logic.ViewDistanceFade;
import org.edtp.kavora.profiling.PerformanceTimer;
import org.edtp.kavora.profiling.ProfileScope;
import org.edtp.kavora.settings.PostProcessingSettings;
import org.edtp.kavora.sky.SkyRenderer;
import org.edtp.kavora.terrain.ChunkManager;
import org.edtp.kavora.texture.AtlasAnimationForwarder;
import org.edtp.kavora.texture.BlockTextureManager;
import org.edtp.kavora.texture.ParticleAtlasManager;
import org.edtp.kavora.particle.ParticleFramePipeline;
import org.edtp.kavora.particle.KavoraParticleRenderer;
import org.edtp.kavora.weather.KavoraWeatherRenderer;
import org.edtp.kavora.camera.CameraController;
import org.edtp.kavora.logic.ConfLayerDriver;
import org.edtp.kavora.logic.ZoneRegistry;
import org.edtp.kavora.entity.EntityRenderContext;
import org.edtp.kavora.entity.KavoraBlockDamagePass;
import org.edtp.kavora.entity.KavoraBlockEntityPass;
import org.edtp.kavora.entity.KavoraBlockOutlinePass;
import org.edtp.kavora.entity.KavoraEntityPass;
import org.edtp.kavora.entity.KavoraEntitySubmitter;
import org.edtp.kavora.entity.KavoraFirstPersonPass;
import org.edtp.kavora.cloud.CloudRenderer;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.renderer.state.level.BlockOutlineRenderState;
import net.minecraft.client.renderer.state.level.BlockBreakingRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.joml.Matrix4fc;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;

/**
 * Render-thread bridge between Minecraft's world and the Kavora runtime.
 *
 * <p>Owns Remix initialization against the Minecraft window, per-frame capture
 * of the scene (terrain, entities, block entities, particles, weather, sky,
 * clouds and lights) into Remix meshes, materials and instances, and camera and
 * frame submission. All of it runs once per rendered frame on the render
 * thread, not once per client tick.
 *
 * <p>{@link KavoraFramePresentation} owns the scene Submit and Present pair.
 * {@link KavoraInterop} borrows the runtime images on the shared Vulkan device.
 */
public class KavoraRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger(KavoraRenderer.class);

    // Remix API instance
    private RemixAPI remixApi;
    private boolean initialized = false;
    
    private KavoraInterop interop = null;
    private RenderBackend backend;
    private KavoraFramePresentation presentation;

    // Scene managers
    private ChunkManager chunkManager;
    // Zones authored via command blocks / commands; written on the server and
    // client threads, read per-frame on the render thread.
    private final ZoneRegistry zoneRegistry = new ZoneRegistry();
    // Drives the minecraft-logic conf option-layers per frame; acquired during
    // initialize(), so it is null only before the renderer comes up.
    private ConfLayerDriver confLayerDriver;
    private LightManager lightManager;
    private SkyRenderer skyRenderer;
    private CloudRenderer cloudRenderer;
    private BlockTextureManager textureManager;
    private PostProcessingSettings postProcessingSettings;
    
    // Entity rendering pipeline: vanilla capture + skinned submission
    private KavoraEntityPass entityPass;
    private KavoraEntitySubmitter entitySubmitter;
    private KavoraFirstPersonPass firstPersonPass;

    // Block entity rendering: captured in place from vanilla's dispatcher and
    // submitted through the entity submitter with the block entity as owner
    private KavoraBlockEntityPass blockEntityPass;
    private boolean entityAtlasPreloadPending = true;

    // Block-breaking cracks: captured in place from vanilla's damage render and
    // submitted through the entity submitter as terrain decals
    private KavoraBlockDamagePass blockDamagePass;

    // Targeted-block outline: Fabric event capture + dynamic Remix submission
    private KavoraBlockOutlinePass blockOutlinePass;

    // Particle rendering: atlas manager + per-frame renderer
    private ParticleAtlasManager particleAtlasManager;
    private KavoraWeatherRenderer weatherRenderer;
    // Deferred operations: flags are set from any thread, executed on the render thread.
    private volatile boolean pendingTextureReload = false;
    private volatile boolean pendingTerrainRecaptureAfterTextureReload = false;
    
    // Performance timing & benchmarking
    private final KavoraFrameMetrics perfTimer = new KavoraFrameMetrics();
    private final ParticleFramePipeline particles = new ParticleFramePipeline(perfTimer);
    private final CameraController cameraController = new CameraController();
    private final BenchmarkController benchmarkController = new BenchmarkController(cameraController);
    private long lastCameraMediumHandle = Long.MIN_VALUE;
    private final ViewDistanceFade viewDistanceFade = new ViewDistanceFade();
    
    /**
     * Initializes the Kavora runtime with the Minecraft window
     */
    public void initialize() {
        LOGGER.info("Initializing Kavora renderer...");

        try {
            lastCameraMediumHandle = Long.MIN_VALUE;

            Minecraft client = Minecraft.getInstance();
            remixApi = NativeRuntime.startup(client.getWindow().handle());

            postProcessingSettings = new PostProcessingSettings(remixApi::setConfigVariable);
            
            backend = RenderBackend.current();
            interop = backend.createInterop(remixApi);
            presentation = new KavoraFramePresentation(remixApi, interop, NativeRuntime.windowHandle());
            LOGGER.info("{} runtime interop initialized", backend);
            
            textureManager = new BlockTextureManager(remixApi);
            chunkManager = new ChunkManager(remixApi, textureManager);
            firstPersonPass = new KavoraFirstPersonPass();
            lightManager = new LightManager(remixApi, textureManager, firstPersonPass);
            skyRenderer = new SkyRenderer(remixApi, benchmarkController, lightManager);
            cloudRenderer = new CloudRenderer(remixApi, benchmarkController);
            
            particleAtlasManager = new ParticleAtlasManager(remixApi);
            entityPass = new KavoraEntityPass(lightManager::ownsLightThisFrame);
            entitySubmitter = new KavoraEntitySubmitter(remixApi, particleAtlasManager);

            blockEntityPass = new KavoraBlockEntityPass();
            blockDamagePass = new KavoraBlockDamagePass();

            particles.attach(new KavoraParticleRenderer(remixApi, textureManager, particleAtlasManager));
            weatherRenderer = new KavoraWeatherRenderer();
            
            perfTimer.registerFrameMetrics();
            particles.registerMetrics();
            
            initAssetStores();
            blockOutlinePass = new KavoraBlockOutlinePass(remixApi);
            
            // Conf layers own every dynamic gameplay look, so a runtime or an
            // install that cannot provide them fails initialization instead of
            // rendering a scene nothing drives. The gate runs before the
            // renderer calls itself initialized, so a failing install never
            // logs success.
            if (!remixApi.isConfigLayersSupported()) {
                throw new IllegalStateException("runtime lacks the config-layer API (needs API >= 0.9.0)");
            }
            Path gameDir = FabricLoader.getInstance().getGameDir();
            Path waterConf = gameDir.resolve("rtx-remix").resolve("mods").resolve("minecraft-logic")
                    .resolve("confs").resolve("water.conf");
            if (!Files.exists(waterConf)) {
                throw new IllegalStateException("conf payload missing (restored on the next launch): "
                        + waterConf);
            }
            ConfLayerDriver driver = new ConfLayerDriver();
            if (!driver.acquireAll(remixApi, gameDir)) {
                throw new IllegalStateException("conf failed to acquire (see log): " + waterConf);
            }
            confLayerDriver = driver;
            LOGGER.info("Config option-layers acquired - conf-layer blending active");

            initialized = true;
            LOGGER.info("Kavora renderer initialized successfully!");

        } catch (RemixException e) {
            LOGGER.error("Failed to initialize Kavora: {} (error code: {})",
                e.getMessage(), e.getErrorCode());
            releasePartialInitialization(e);
            throw e;
        } catch (Exception e) {
            LOGGER.error("Failed to initialize Kavora", e);
            releasePartialInitialization(e);
            throw new RuntimeException("Kavora initialization failed", e);
        }
    }

    /**
     * Tears down whatever initialization managed to build. A failure can land
     * after the runtime has started and the subsystems own native resources,
     * so the session must not carry a half-built renderer. A secondary failure
     * inside the teardown is logged rather than thrown, because the caller is
     * about to rethrow the original cause.
     */
    private void releasePartialInitialization(Exception initializationFailure) {
        initialized = false;
        try {
            shutdown();
        } catch (Exception e) {
            initializationFailure.addSuppressed(e);
            LOGGER.error("Error tearing down a failed Kavora initialization", e);
        }
    }

    private void initAssetStores() {
        KavoraMeshStore.init(remixApi);
        KavoraInstanceStore.init(remixApi);
        KavoraTextureStore.init(remixApi);
        KavoraMaterialStore.init(remixApi);
        KavoraLightStore.init(remixApi);
    }

    private void gcAssetStores() {
        if (KavoraMeshStore.get() != null)      KavoraMeshStore.get().gc();
        if (KavoraInstanceStore.get() != null)  KavoraInstanceStore.get().gc();
        if (KavoraTextureStore.get() != null)   KavoraTextureStore.get().gc();
        if (KavoraMaterialStore.get() != null)  KavoraMaterialStore.get().gc();
        if (KavoraLightStore.get() != null)     KavoraLightStore.get().gc();
    }

    /**
     * The single location of the Kavora runtime. The extractor installs the
     * packaged runtime here and development launches stage it here, so no other
     * path is searched.
     */
    static Path runtimeDll(Path gameDir) {
        return gameDir.resolve("kavora-runtime").resolve("d3d9.dll");
    }
    
    /**
     * Applies pending world/resource invalidation and preloads the large
     * block-entity atlases. Call before vanilla's world render, which is where
     * block entities are captured in place.
     */
    public void beginWorldRenderFrame() {
        if (!initialized) return;

        applyPendingWorldResourceChanges();
        if (KavoraMod.isBlockEntitiesEnabled()) {
            preloadEntityAtlasesForCapture();
        }
    }

    private void applyPendingWorldResourceChanges() {
        if (!pendingTextureReload) return;

        boolean recaptureTerrain = pendingTerrainRecaptureAfterTextureReload;
        pendingTextureReload = false;
        LOGGER.info("Reloading textures, materials, and terrain");
        particles.discardInFlight();
        particles.discardPendingFrame();
        AtlasAnimationForwarder.clear();
        // Retained meshes hold material handles. Release them before any of the
        // materials they reference are destroyed or rebuilt: the submitter's own,
        // the block atlas material it borrows from BlockTextureManager, and the
        // particle atlas material it borrows from ParticleAtlasManager.
        if (entitySubmitter != null) entitySubmitter.reloadMaterials();
        if (entityPass != null) entityPass.discardPoseSnapshots();
        particles.clearResources();
        if (particleAtlasManager != null) particleAtlasManager.cleanup();
        entityAtlasPreloadPending = true;
        if (weatherRenderer != null) weatherRenderer.cleanup();
        if (chunkManager != null) chunkManager.clear();
        if (textureManager != null) textureManager.reload();
        if (skyRenderer != null) skyRenderer.reloadResources();
        if (cloudRenderer != null) {
            try {
                cloudRenderer.cleanup();
            } catch (RuntimeException e) {
                LOGGER.error("Failed to release cloud resources during resource reload", e);
            }
        }
        if (recaptureTerrain) {
            Minecraft client = Minecraft.getInstance();
            if (client != null && client.levelRenderer != null) {
                LongSet built = KavoraMod.builtSections(client);
                client.levelExtractor.allChanged();
                KavoraMod.recaptureTerrainForRemix(client, built);
            }
            pendingTerrainRecaptureAfterTextureReload = false;
        }
    }

    private void preloadEntityAtlasesForCapture() {
        if (textureManager == null || entitySubmitter == null) return;
        if (!entityAtlasPreloadPending && entitySubmitter.hasLiveLargeBlockEntityAtlasMaterial()) return;

        textureManager.initialize();
        if (!textureManager.isInitialized()) return;

        try (ProfileScope ignored = ProfileScope.begin("EntityAtlas.preload")) {
            entityAtlasPreloadPending = !entitySubmitter.preloadLargeBlockEntityAtlases();
        }
    }

    private final JoinBurstHold joinBurstHold = new JoinBurstHold(JoinBurstHold.ENABLED);

    /**
     * The join hold of the renderer that is drawing the world, or null while Remix is not
     * rendering (no renderer, initialization failed, RTX toggled off), in which case vanilla
     * closes its terrain-download screen as usual.
     */
    public static JoinBurstHold activeJoinBurstHold() {
        KavoraMod mod = KavoraMod.getInstance();
        KavoraRenderer renderer = mod == null ? null : mod.getRenderer();
        if (renderer == null || !renderer.initialized || !KavoraMod.isRtxEnabled()) {
            return null;
        }
        return renderer.joinBurstHold;
    }
    private boolean renderThreadNamed;

    /**
     * Advances camera and benchmark frame-start state before Minecraft builds
     * its {@link Camera} for a world render.
     *
     * <p>This is called from the invocation boundary immediately before
     * {@code GameRenderer.render}. The eligibility check mirrors
     * {@code GameRenderer.render}: menu/loading frames that will not call
     * {@code renderWorld} must not advance an interactive camera timeline or a
     * deterministic benchmark frame.</p>
     */
    public void onMinecraftWorldRenderFrameStart(Minecraft client, boolean tick) {
        if (!initialized || remixApi == null || client == null) {
            return;
        }
        if (!shouldAdvanceWorldRenderFrame(tick, client.isGameLoadFinished(), client.level != null)) {
            return;
        }

        cameraController.onFrameStart(client);
        benchmarkController.onFrameStart(perfTimer);
        benchmarkController.updateAnimationClock();
        benchmarkController.captureGpuFrame();
    }

    static boolean shouldAdvanceWorldRenderFrame(boolean tick, boolean finishedLoading, boolean worldLoaded) {
        return tick && finishedLoading && worldLoaded;
    }

    /**
     * Opens a fresh entity capture window for the world render that is starting.
     *
     * <p>{@code WorldRenderEvents.END} does not fire during loading screens, so a
     * capture window can be opened and never closed. Resetting at the start of
     * every world render stops a dropped frame bleeding into the next one, and
     * opening the submitter's frame here (rather than in
     * {@link #renderAndSubmitFrame}) is what lets parts captured in place be
     * stamped with the frame that will decide whether to retire them.
     */
    public void beginEntityFrame() {
        if (entityPass == null || entitySubmitter == null) {
            return;
        }
        entityPass.resetFrame();
        if (initialized && KavoraMod.isRtxEnabled() && KavoraMod.isEntitiesEnabled()) {
            entityPass.beginFrame(entitySubmitter);
        }
    }

    /**
     * Captures one entity at vanilla's own traversal point, in place of the render
     * that would have gone to the vanilla render passes.
     *
     * <p>Called from {@code WorldRendererMixin}'s wrap of
     * {@code WorldRenderer.renderEntity}. Returning true tells the mixin to skip
     * vanilla's call, which is what reduces each entity to a single model
     * evaluation per frame. Returning false hands the entity back to vanilla, so
     * an uninitialised renderer or a disabled toggle still renders normally.
     */
    public boolean renderEntityInPlace(Entity entity, float tickDelta) {
        if (!initialized || remixApi == null || entityPass == null || entitySubmitter == null) {
            return false;
        }
        if (!KavoraMod.isRtxEnabled() || !KavoraMod.isEntitiesEnabled()) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        EntityRenderDispatcher dispatcher = client == null ? null : client.getEntityRenderDispatcher();
        if (dispatcher == null) {
            return false;
        }
        entityPass.beginFrame(entitySubmitter);
        entityPass.captureAndSubmit(entity, dispatcher, tickDelta, entitySubmitter);
        return true;
    }

    public boolean renderEntityInPlace(Entity entity, EntityRenderState state,
                                      EntityRenderDispatcher dispatcher, float tickDelta) {
        if (!initialized || entityPass == null || entitySubmitter == null
                || !KavoraMod.isRtxEnabled() || !KavoraMod.isEntitiesEnabled()) return false;
        entityPass.beginFrame(entitySubmitter);
        entityPass.captureAndSubmit(entity, state, dispatcher, tickDelta, entitySubmitter);
        return true;
    }

    /**
     * Captures one block entity at vanilla's own dispatch point, in place of the
     * render that would have gone to the vanilla render passes.
     *
     * <p>Called from {@code BlockEntityRenderDispatcherMixin}. Returning true tells
     * the mixin to skip vanilla's call; returning false hands the block entity back
     * to vanilla, so an uninitialised renderer or a disabled toggle still renders
     * normally. A block entity dispatched while a capture is already open is left
     * to that capture's owner.
     *
     * <p>The submitter's frame is opened here, idempotently, because retaining a
     * part requires an open frame and block entities are captured even while the
     * entity toggle, whose pass normally opens it, is off.
     */
    public boolean renderBlockEntityInPlace(BlockEntity be,
                                            KavoraBlockEntityPass.BlockEntityRenderCall call) {
        if (!initialized || remixApi == null || blockEntityPass == null || entitySubmitter == null) {
            return false;
        }
        if (!KavoraMod.isRtxEnabled() || !KavoraMod.isBlockEntitiesEnabled()) {
            return false;
        }
        if (EntityRenderContext.get().isActive()) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        EntityRenderDispatcher dispatcher = client == null ? null : client.getEntityRenderDispatcher();
        if (dispatcher == null) {
            return false;
        }
        entitySubmitter.beginFrame();
        blockEntityPass.capture(be, call, dispatcher, entitySubmitter);
        return true;
    }

    /**
     * Captures one block's breaking crack at vanilla's own render point, in place of
     * the render that would have gone to the vanilla render passes.
     *
     * <p>Called from {@code WorldRendererMixin}'s wrap of
     * {@code BlockRenderManager.renderDamage}. Returning true tells the mixin to skip
     * vanilla's call; returning false hands the crack back to vanilla, so an
     * uninitialised renderer or a disabled toggle still renders normally. The crack is
     * terrain decoration, so it follows the world toggle rather than the entity toggle.
     * The submitter's frame is opened here, idempotently, because retaining a part
     * requires an open frame and the entity pass, which normally opens it, runs only
     * while the entity toggle is on.
     */
    public boolean renderBlockDamageInPlace(BlockBreakingRenderState state) {
        if (!initialized || remixApi == null || blockDamagePass == null || entitySubmitter == null) {
            return false;
        }
        if (!KavoraMod.isRtxEnabled() || !KavoraMod.isWorldEnabled()) {
            return false;
        }
        entitySubmitter.beginFrame();
        blockDamagePass.capture(state, entitySubmitter);
        return true;
    }

    public void renderAndSubmitFrame() {
        if (!initialized || remixApi == null) {
            return;
        }

        joinBurstHold.onFrame(System.nanoTime());

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

            particles.submitPendingFrame(world);

            Camera camera = client.gameRenderer.mainCamera();
            float tickDelta = client.getDeltaTracker().getGameTimeDeltaPartialTick(true);
            particles.startPrepare(particleAtlasManager, tickDelta, ProfileScope.currentFrameIndex());
            
            perfTimer.begin(perfTimer.perfCamera);
            try (ProfileScope cameraScope = ProfileScope.begin("Camera")) {
                updateCamera(camera, tickDelta);
            } finally {
                perfTimer.end(perfTimer.perfCamera);
            }
            
            Vec3 cameraPos = camera.position();

            perfTimer.begin(perfTimer.perfLights);
            try (ProfileScope lightsScope = ProfileScope.begin("Lights")) {
                // Entity lights first: updateLights() draws them, so updating
                // after it would submit last frame's positions. Visible as a
                // one-frame lag between a spinning beacon and its beam.
                lightManager.updateEntityLights(world, camera, tickDelta);
                lightManager.updateLights(world, camera, tickDelta);
            } finally {
                perfTimer.end(perfTimer.perfLights);
            }

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
            blockDamagePass.finishFrame();

            // Submit the targeted-block outline through Remix so it is
            // depth-tested against the traced scene.
            if (blockOutlinePass != null) {
                try (ProfileScope blockOutlineScope = ProfileScope.begin("BlockOutline")) {
                    blockOutlinePass.submit();
                } catch (Exception e) {
                    LOGGER.error("Error rendering targeted-block outline", e);
                }
            }

            // The sky owner also clears host metadata when its rendering is disabled.
            perfTimer.begin(perfTimer.perfSky);
            try (ProfileScope skyScope = ProfileScope.begin("Sky")) {
                skyRenderer.render(world, camera, cameraPos, tickDelta, confLayerDriver);
            } catch (Exception e) {
                LOGGER.error("Error rendering sky", e);
            } finally {
                perfTimer.end(perfTimer.perfSky);
            }

            if (KavoraMod.isWorldActive()) {
                perfTimer.begin(perfTimer.perfClouds);
                try (ProfileScope cloudScope = ProfileScope.begin("Clouds")) {
                    cloudRenderer.render(world, camera, cameraPos, tickDelta, client.options.getCloudStatus());
                    perfTimer.recordClouds(cloudScope, cloudRenderer);
                } catch (Exception e) {
                    LOGGER.error("Error rendering clouds", e);
                } finally {
                    perfTimer.end(perfTimer.perfClouds);
                }
            } else {
                try {
                    cloudRenderer.disable();
                } catch (RuntimeException e) {
                    LOGGER.error("Failed to disable cloud rendering", e);
                }
            }

            // Forward atlas animation patches to Remix before presenting
            if (textureManager != null) {
                perfTimer.begin(perfTimer.perfAtlas);
                try (ProfileScope atlasScope = ProfileScope.begin("Atlas")) {
                    KavoraTexture albedoTex = textureManager.getBlockAtlasTexture();
                    KavoraTexture emissiveTex = textureManager.getEmissiveAtlasTexture();
                    KavoraTexture roughnessTex = textureManager.getRoughnessAtlasTexture();
                    KavoraTexture metallicTex = textureManager.getMetallicAtlasTexture();
                    KavoraTexture sssTex = textureManager.getSssAtlasTexture();
                    long albedoHandle = albedoTex != null ? albedoTex.getHandle() : 0;
                    long emissiveHandle = emissiveTex != null ? emissiveTex.getHandle() : 0;
                    long roughnessHandle = roughnessTex != null ? roughnessTex.getHandle() : 0;
                    long metallicHandle = metallicTex != null ? metallicTex.getHandle() : 0;
                    long sssHandle = sssTex != null ? sssTex.getHandle() : 0;
                    if (albedoHandle != 0) {
                        AtlasAnimationForwarder.flush(remixApi, albedoHandle, emissiveHandle,
                                roughnessHandle, metallicHandle, sssHandle);
                    }
                } finally {
                    perfTimer.end(perfTimer.perfAtlas);
                }
            }

            perfTimer.begin(perfTimer.perfSubmit);
            try (ProfileScope submitScope = ProfileScope.begin("Submit")) {
                if (org.edtp.kavora.render.KavoraWaterRipples.wantsRain()) {
                    float rain = world.getRainLevel(tickDelta);
                    if (rain > 0) {
                        BlockPos position = BlockPos.containing(cameraPos);
                        if (world.getBiome(position).value().getPrecipitationAt(position, world.getSeaLevel()) != Biome.Precipitation.RAIN) {
                            rain = 0;
                        }
                    }
                    org.edtp.kavora.render.KavoraWaterRipples.rain(rain);
                }
                presentation.submitScene();
            } finally {
                perfTimer.end(perfTimer.perfSubmit);
            }

            // The worker publishes CPU data for the next scene after this one is submitted.
            particles.finishAfterSubmit(world);

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

    public void onMinecraftRenderFrameEnd(long fullFrameNanos) {
        if (!initialized || benchmarkController == null) {
            return;
        }
        benchmarkController.onMinecraftRenderFrameEnd(fullFrameNanos, perfTimer);
    }

    /**
     * Updates the Remix camera from Minecraft's camera
     */
    private void updateCamera(Camera camera, float tickDelta) {
        try {
            Vec3 pos = camera.position();
            float pitch = camera.xRot();
            float yaw = camera.yRot();
    
            float yawRad = (float) Math.toRadians(-yaw);
            float pitchRad = (float) Math.toRadians(pitch);
    
            float forwardX = (float) (Math.sin(yawRad) * Math.cos(pitchRad));
            float forwardY = (float) (-Math.sin(pitchRad));
            float forwardZ = (float) (Math.cos(yawRad) * Math.cos(pitchRad));
    
            float rightX = (float) (-Math.cos(yawRad));
            float rightY = 0.0f;
            float rightZ = (float) (Math.sin(yawRad));
    
            float upX = rightY * forwardZ - rightZ * forwardY;
            float upY = rightZ * forwardX - rightX * forwardZ;
            float upZ = rightX * forwardY - rightY * forwardX;
    
            Minecraft client = Minecraft.getInstance();
            float worldFov = camera.getFov();
            int width = client.getWindow().getWidth();
            int height = client.getWindow().getHeight();
            float aspect = (float) width / (float) height;
            int farDistanceChunks = chunkManager == null ? 0 : chunkManager.getFarRenderDistanceChunks();
            if (weatherRenderer != null) {
                weatherRenderer.updateCamera(
                        camera,
                        worldFov,
                        aspect);
            }
    
            CameraInfoParameterizedEXT worldCameraParams = CameraInfoParameterizedEXT.builder()
                .position((float) pos.x, (float) pos.y, (float) pos.z)
                .forward(forwardX, forwardY, forwardZ)
                .up(upX, upY, upZ)
                .right(rightX, rightY, rightZ)
                .fovYInDegrees(worldFov)
                .aspect(aspect)
                .nearPlane(0.05f)
                .farPlane(Math.max(1000.0f, farDistanceChunks * 16.0f))
                .build();
    
            CameraInfo worldCameraInfo = CameraInfo.builder()
                .type(CameraType.WORLD)
                .parameterizedExt(worldCameraParams)
                .build();
    
            CameraInfo viewModelCameraInfo = CameraInfo.builder()
                .type(CameraType.VIEW_MODEL)
                // The hand-FOV adjustment is already applied to first-person
                // bones. Supplying the exact world camera keeps Remix's
                // perspective-correction matrix at identity while retaining
                // its per-frame view-model transform/history update.
                .parameterizedExt(worldCameraParams)
                .build();
    
            remixApi.setupCamera(worldCameraInfo);
            remixApi.setupCamera(viewModelCameraInfo);
            float viewDistanceStrength = viewDistanceFade.layerStrength(ClientViewDistance.chunks(),
                    farDistanceChunks);
            if (confLayerDriver != null) confLayerDriver.setFarViewDistanceStrength(viewDistanceFade.farLayerStrength());
            updateCameraMedium(camera, pos, viewDistanceStrength, tickDelta);
        } catch (RemixException e) {
            LOGGER.error("Error updating camera: {} (code: {})", e.getMessage(), e.getErrorCode());
        } catch (Exception e) {
            LOGGER.error("Error updating camera", e);
        }
    }

    ViewDistanceFade getViewDistanceFade() {
        return viewDistanceFade;
    }

    public boolean isViewDistanceFadeAuto() {
        return viewDistanceFade.isAuto();
    }

    public void setViewDistanceFadeAuto(boolean auto) {
        viewDistanceFade.setAuto(auto);
        syncViewDistanceFade();
    }

    /**
     * Recomputes the band that {@code /kavora viewdistance} and the settings
     * screen report, so a mode change is visible before the next rendered
     * frame recomputes it anyway. The driver takes its strength from
     * {@code updateCamera}.
     */
    private void syncViewDistanceFade() {
        if (!initialized) {
            return;
        }
        viewDistanceFade.layerStrength(ClientViewDistance.chunks(),
                chunkManager == null ? 0 : chunkManager.getFarRenderDistanceChunks());
    }

    private void updateCameraMedium(Camera camera, Vec3 cameraPos, float viewDistanceStrength, float tickDelta) {
        try {
            Minecraft client = Minecraft.getInstance();
            ClientLevel world = client != null ? client.level : null;
    
            // Submersion is read before material readiness so the driver sees
            // it on early frames.
            boolean underwater = camera.getFluidInCamera() == FogType.WATER;
            long materialHandle = 0;
            if (underwater && textureManager != null) {
                KavoraMaterial cameraMediumMaterial = textureManager.getWaterCameraMediumMaterial();
                if (cameraMediumMaterial != null && !cameraMediumMaterial.isDeleted()) {
                    materialHandle = cameraMediumMaterial.getHandle();
                }
            }
    
            remixApi.setCameraMediumMaterial(materialHandle);
            // Conf layers own the volumetric profile and the view-distance
            // fade. A driver left inactive by a failed reload drives nothing
            // until the next successful /kavora conf reload.
            if (confLayerDriver != null && confLayerDriver.isActive() && world != null) {
                confLayerDriver.update(world, cameraPos, underwater,
                        viewDistanceStrength, tickDelta, zoneRegistry);
            }
            if (materialHandle != lastCameraMediumHandle) {
                LOGGER.info("Camera medium updated: {}", materialHandle != 0 ? "water" : "clear");
                lastCameraMediumHandle = materialHandle;
            }
        } catch (RemixException e) {
            LOGGER.error("Error updating camera medium: {} (code: {})", e.getMessage(), e.getErrorCode());
        } catch (Exception e) {
            LOGGER.error("Error updating camera medium", e);
        }
    }
    
    /** Draws the runtime's independent menu layer above Minecraft screens, immediately before the surface blit. */
    public void compositeRemixMenus() {
        if (!KavoraMod.getRemixInputGate().isCaptureSessionOpen()) return;
        presentation.compositeMenu();
    }
    
    public void beginWindowRender() {
        presentation.beginWindowRender();
    }

    public void endWindowRender() {
        presentation.endWindowRender();
    }

    public void finishDirectRender() {
        presentation.finishDirectRender();
    }

    /** Records the shared-image release in Minecraft's frame submission. */
    public void preparePresentFrame() {
        try (ProfileScope ignored = ProfileScope.begin("Interop.ClientToRemix")) {
            presentation.releaseImages();
        }
    }

    /** Completes the runtime frame after Minecraft submits the shared-image release. */
    public void presentFrame(VulkanClientFrameInfo frame) {
        presentation.present(frame);
    }

    /** Acquires runtime images even when disabled, then composites the world before screen effects and HUD. */
    public boolean compositeFrameColor() {
        try (ProfileScope ignored = ProfileScope.begin("Interop.RemixToClient")) {
            presentation.acquireImages();
            return KavoraMod.isRtxEnabled() && interop.blitColor(
                    Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTexture());
        }
    }

    public long getInteropSnapshotTextureBytes() { return interop.snapshotTextureBytes(); }
    public long getInteropCopiedSnapshotBytes() { return interop.copiedSnapshotBytes(); }

    public void renderWeather() {
        if (weatherRenderer == null || !isSceneReady()) return;
        try (ProfileScope scope = ProfileScope.begin("Weather")) {
            weatherRenderer.render(interop);
            scope.drawCalls(weatherRenderer.getLastDrawCount()).vertexCount(weatherRenderer.getLastQuadCount() * 4);
        }
    }

    /** Runs on the client executor so the clear lands during teardown, before the next world initializes. */
    public void clearWorld() {
        LOGGER.info("World clear requested (will execute on render thread)");
        Minecraft.getInstance().execute(() -> {
            if (initialized) {
                org.edtp.kavora.render.KavoraWaterRipples.reset();
                doClearWorld();
            }
        });
    }

    /**
     * Requests a full texture/material rebuild at the next world-render start.
     * Call when the sprite atlas changes (resource pack add/remove/reorder).
     */
    public void requestTextureReload() {
        // Resource loading may finish section builds before the next world frame.
        // Clearing their captured meshes without also invalidating vanilla's compiled
        // sections leaves no reason for those sections to be captured again.
        requestMaterialSettingsReload();
    }

    /** Requests a material reload followed by one complete loaded-terrain recapture. */
    public void requestMaterialSettingsReload() {
        if (!initialized) return;
        pendingTerrainRecaptureAfterTextureReload = true;
        pendingTextureReload = true;
    }

    /** Clears stale outline capture at the start of vanilla world rendering. */
    public void beginBlockOutlineFrame() {
        if (blockOutlinePass != null) {
            blockOutlinePass.beginFrame();
        }
    }

    /**
     * Captures vanilla's targeted-block outline for Remix submission later in
     * the same frame.
     */
    public void captureBlockOutline(
            LevelRenderContext context,
            BlockOutlineRenderState outline) {
        if (blockOutlinePass != null) {
            blockOutlinePass.captureBlockOutline(context, outline);
        }
    }
    
    /**
     * Actually clears all world-specific Remix resources (meshes, materials, lights)
     * without tearing down the API or Vulkan device state. Must be called on the render thread.
     */
    private void doClearWorld() {
        LOGGER.info("Clearing world resources...");
        particles.discardInFlight();
        particles.discardPendingFrame();
        
        if (chunkManager != null) {
            chunkManager.clear();
        }
        if (lightManager != null) {
            lightManager.clear();
        }
        if (entitySubmitter != null) {
            entitySubmitter.clear();
        }
        entityAtlasPreloadPending = true;
        if (firstPersonPass != null) {
            firstPersonPass.clearPending();
        }
        if (blockOutlinePass != null) {
            blockOutlinePass.clear();
        }
        particles.clearResources();
        if (weatherRenderer != null) {
            weatherRenderer.cleanup();
        }
        if (skyRenderer != null) {
            skyRenderer.cleanup();
        }
        if (cloudRenderer != null) {
            try {
                cloudRenderer.cleanup();
            } catch (RuntimeException e) {
                LOGGER.error("Failed to release cloud resources during world cleanup", e);
            }
        }
        LOGGER.info("World resources cleared");
    }
    
    /**
     * Shuts down the renderer
     */
    public void shutdown() {
        LOGGER.info("Shutting down Kavora renderer...");
        initialized = false;
        pendingTextureReload = false;
        pendingTerrainRecaptureAfterTextureReload = false;
        List<RuntimeException> failures = new ArrayList<>();

        runShutdownStep(failures, AtlasAnimationForwarder::clear);
        runShutdownStep(failures, particles::discardInFlight);
        runShutdownStep(failures, particles::discardPendingFrame);
        runShutdownStep(failures, particles::shutdownWorker);
        runShutdownStep(failures, this::doClearWorld);
        if (skyRenderer != null) {
            var resource = skyRenderer;
            skyRenderer = null;
            runShutdownStep(failures, resource::shutdown);
        }
        if (confLayerDriver != null) {
            var resource = confLayerDriver;
            confLayerDriver = null;
            runShutdownStep(failures, resource::releaseAll);
        }
        if (chunkManager != null) {
            var resource = chunkManager;
            chunkManager = null;
            runShutdownStep(failures, resource::destroy);
        }
        if (entitySubmitter != null) {
            var resource = entitySubmitter;
            entitySubmitter = null;
            runShutdownStep(failures, resource::close);
        }
        runShutdownStep(failures, particles::shutdownRenderer);
        if (weatherRenderer != null) {
            var resource = weatherRenderer;
            weatherRenderer = null;
            runShutdownStep(failures, resource::cleanup);
        }
        if (particleAtlasManager != null) {
            var resource = particleAtlasManager;
            particleAtlasManager = null;
            runShutdownStep(failures, resource::cleanup);
        }
        if (textureManager != null) {
            var resource = textureManager;
            textureManager = null;
            runShutdownStep(failures, resource::cleanup);
        }
        if (firstPersonPass != null) {
            var resource = firstPersonPass;
            firstPersonPass = null;
            runShutdownStep(failures, resource::close);
        }
        if (blockOutlinePass != null) {
            var resource = blockOutlinePass;
            blockOutlinePass = null;
            runShutdownStep(failures, resource::close);
        }
        runShutdownStep(failures, this::shutdownAssetStores);
        if (presentation != null) {
            var resource = presentation;
            presentation = null;
            runShutdownStep(failures, resource::close);
        }
        if (interop != null) {
            var resource = interop;
            interop = null;
            runShutdownStep(failures, resource::close);
        }
        // Presentation outlives scene capture and is released with the host Vulkan device.
        remixApi = null;
        LOGGER.info("Kavora renderer shut down");
        if (!failures.isEmpty()) {
            RuntimeException failure = failures.getFirst();
            for (int i = 1; i < failures.size(); i++) {
                failure.addSuppressed(failures.get(i));
            }
            throw failure;
        }
    }

    private static void runShutdownStep(List<RuntimeException> failures, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException failure) {
            failures.add(failure);
        }
    }

    private void shutdownAssetStores() {
        KavoraInstanceStore.shutdown();
        KavoraMeshStore.shutdown();
        KavoraLightStore.shutdown();
        KavoraMaterialStore.shutdown();
        KavoraTextureStore.shutdown();
    }

    public boolean isInitialized() {
        return initialized;
    }

    public boolean isSceneReady() {
        return initialized && interop != null && interop.isSceneReady();
    }
    
    public RemixAPI getRemixApi() {
        return remixApi;
    }

    public RenderBackend getBackend() {
        return backend;
    }
    
    public ChunkManager getChunkManager() {
        return chunkManager;
    }

    public ZoneRegistry getZoneRegistry() {
        return zoneRegistry;
    }

    /**
     * Null before initialize() acquires the layers and after shutdown. Can
     * also be non-null but inactive ({@code isActive() == false}) after a
     * failed reload; a later successful {@link ConfLayerDriver#reload()}
     * reactivates it.
     */
    public ConfLayerDriver getConfLayerDriver() {
        return confLayerDriver;
    }

    public LightManager getLightManager() {
        return lightManager;
    }

    public BlockTextureManager getTextureManager() {
        return textureManager;
    }

    public KavoraWeatherRenderer getWeatherRenderer() {
        return weatherRenderer;
    }
    
    public PerformanceTimer getPerfTimer() {
        return perfTimer;
    }

    public BenchmarkController getBenchmarkController() {
        return benchmarkController;
    }

    public CameraController getCameraController() {
        return cameraController;
    }

    public SkyRenderer getSkyRenderer() {
        return skyRenderer;
    }

    public CloudRenderer getCloudRenderer() {
        return cloudRenderer;
    }

    public PostProcessingSettings getPostProcessingSettings() {
        return postProcessingSettings;
    }

    public KavoraEntityPass getEntityPass() {
        return entityPass;
    }

    public void captureFirstPersonModel(
            FirstPersonHandsAndItemsRenderer heldItemRenderer,
            float tickDelta,
            PoseStack matrices,
            LocalPlayer player,
            PlayerRenderState playerState,
            Matrix4fc modelView) {
        if (firstPersonPass != null && initialized && KavoraMod.isRtxEnabled()) {
            firstPersonPass.capture(heldItemRenderer, tickDelta, matrices, player, playerState, modelView);
        }
    }
}
