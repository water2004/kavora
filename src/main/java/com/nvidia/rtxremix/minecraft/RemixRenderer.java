/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.CameraInfo;
import com.nvidia.rtxremix.CameraInfoParameterizedEXT;
import com.nvidia.rtxremix.CameraType;
import com.nvidia.rtxremix.PresentInfo;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.RemixException;
import com.nvidia.rtxremix.SubmitInfo;
import com.nvidia.rtxremix.minecraft.asset.RemixInstanceStore;
import com.nvidia.rtxremix.minecraft.asset.RemixLightStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterial;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterialStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshStore;
import com.nvidia.rtxremix.minecraft.asset.RemixTexture;
import com.nvidia.rtxremix.minecraft.asset.RemixTextureStore;
import com.nvidia.rtxremix.minecraft.benchmark.BenchmarkController;
import com.nvidia.rtxremix.minecraft.render.RemixInterop;
import com.nvidia.rtxremix.minecraft.render.RenderBackend;
import com.nvidia.rtxremix.minecraft.render.NativeMenuOverlay;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.nvidia.rtxremix.minecraft.light.LightManager;
import com.nvidia.rtxremix.minecraft.logic.ViewDistanceFade;
import com.nvidia.rtxremix.minecraft.profiling.PerformanceTimer;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.settings.PostProcessingSettings;
import com.nvidia.rtxremix.minecraft.sky.SkyRenderer;
import com.nvidia.rtxremix.minecraft.terrain.ChunkManager;
import com.nvidia.rtxremix.minecraft.texture.AtlasAnimationForwarder;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import com.nvidia.rtxremix.minecraft.texture.ParticleAtlasManager;
import com.nvidia.rtxremix.minecraft.particle.RemixParticleRenderer.PreparedFrame;
import com.nvidia.rtxremix.minecraft.particle.RemixParticleRenderer;
import com.nvidia.rtxremix.minecraft.weather.RemixWeatherRenderer;
import com.nvidia.rtxremix.minecraft.camera.CameraController;
import com.nvidia.rtxremix.minecraft.logic.ConfLayerDriver;
import com.nvidia.rtxremix.minecraft.logic.ZoneRegistry;
import com.nvidia.rtxremix.minecraft.entity.EntityRenderContext;
import com.nvidia.rtxremix.minecraft.entity.RemixBlockDamagePass;
import com.nvidia.rtxremix.minecraft.entity.RemixBlockEntityPass;
import com.nvidia.rtxremix.minecraft.entity.RemixBlockOutlinePass;
import com.nvidia.rtxremix.minecraft.entity.RemixEntityPass;
import com.nvidia.rtxremix.minecraft.entity.RemixEntitySubmitter;
import com.nvidia.rtxremix.minecraft.entity.RemixFirstPersonPass;
import com.nvidia.rtxremix.minecraft.cloud.CloudRenderer;
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
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Render-thread bridge between Minecraft's world and the RTX Remix runtime.
 *
 * <p>Owns Remix initialization against the Minecraft window, per-frame capture
 * of the scene (terrain, entities, block entities, particles, weather, sky,
 * clouds and lights) into Remix meshes, materials and instances, and camera and
 * frame submission. All of it runs once per rendered frame on the render
 * thread, not once per client tick.
 *
 * <p>{@link RemixInterop} imports the runtime's dedicated Vulkan allocations
 * onto Minecraft's device. Timeline semaphores and external queue-family
 * transfers protect the shared images; Minecraft owns the final presentation.
 */
public class RemixRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger(RemixRenderer.class);

    // Remix API instance
    private RemixAPI remixApi;
    // Publish the runtime's Vulkan signal before Minecraft waits on it during presentation.
    private final SubmitInfo vulkanSubmit = new SubmitInfo();
    private boolean initialized = false;
    private long windowHandle;
    
    private RemixInterop interop = null;
    private RenderBackend backend;
    private final NativeMenuOverlay nativeMenuOverlay = new NativeMenuOverlay();
    
    private boolean submitFrameCalled = false;
    private final PresentInfo presentInfo = new PresentInfo();

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
    private RemixEntityPass entityPass;
    private RemixEntitySubmitter entitySubmitter;
    private RemixFirstPersonPass firstPersonPass;

    // Block entity rendering: captured in place from vanilla's dispatcher and
    // submitted through the entity submitter with the block entity as owner
    private RemixBlockEntityPass blockEntityPass;
    private boolean entityAtlasPreloadPending = true;

    // Block-breaking cracks: captured in place from vanilla's damage render and
    // submitted through the entity submitter as terrain decals
    private RemixBlockDamagePass blockDamagePass;

    // Targeted-block outline: Fabric event capture + dynamic Remix submission
    private RemixBlockOutlinePass blockOutlinePass;

    // Particle rendering: atlas manager + per-frame renderer
    private ParticleAtlasManager particleAtlasManager;
    private RemixParticleRenderer particleRenderer;
    private RemixWeatherRenderer weatherRenderer;
    private final ExecutorService particleExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "RTX Remix Particles");
        thread.setDaemon(true);
        return thread;
    });
    private volatile CompletableFuture<ParticleRenderResult> particleRenderFuture;
    // Double-buffer: holds frame N's prepared particles for submission during frame N+1.
    private PreparedFrame pendingParticleFrame;
    private long particleNanosThisFrame;
    /** World the pending payload was prepared against, so a swap cannot publish it. */
    private ClientLevel pendingParticleFrameWorld;
    
    // Deferred operations: flags are set from any thread, executed on the render thread.
    private volatile boolean pendingTextureReload = false;
    private volatile boolean pendingTerrainRecaptureAfterTextureReload = false;
    
    // Performance timing & benchmarking
    private final PerformanceTimer perfTimer = new PerformanceTimer();
    private final CameraController cameraController = new CameraController();
    private final BenchmarkController benchmarkController = new BenchmarkController(cameraController);
    private int perfCamera = -1;
    private int perfLights = -1;
    private int perfChunkGen = -1;
    private int perfChunkDraw = -1;
    private int perfEntities = -1;
    private int perfSky = -1;
    private int perfClouds = -1;
    private int perfCloudGen = -1;
    private int perfAtlas = -1;
    private int perfSubmit = -1;
    private int cntTextureSharedSavedMB = -1;
    private int cntPresentedFrames = -1, cntGeneratedFrames = -1;
    private int cntDeviceLocalUsedMB = -1, cntDeviceLocalBudgetMB = -1;
    private int cntRenderWidth = -1, cntRenderHeight = -1;
    private int cntInteropSnapshots = -1, cntInteropSnapshotCopies = -1;
    private long presentationEpoch, lastPresentedFrames, lastGeneratedFrames;
    private int cntEntities = -1;
    private int cntEntLayers = -1;
    private int cntEntDraws = -1;
    private int cntEntBones = -1;
    private int cntEntMeshCreates = -1;
    private int cntCloudVisible = -1;
    private int cntCloudInstances = -1;
    private int cntCloudPaletteCreates = -1;
    private int cntCloudDraws = -1;
    private int cntCloudVertices = -1;
    private int cntCloudTriangles = -1;
    private int cntEntSkipped = -1;
    private int perfEntCapture = -1;
    private int perfEntSubmit = -1;
    private int perfEntOffSweep = -1;
    private int cntEntOffFrustum = -1;
    private int cntEntOffDraws = -1;
    private int cntEntOffReused = -1;
    private int cntEntPoolSize = -1;
    private int perfBlockEntityCapture = -1;
    private int perfBlockEntitySubmit = -1;
    private int cntBlockEntities = -1;
    private int cntBlockEntityParts = -1;
    private int cntBlockEntityMeshCreates = -1;
    private long lastCameraMediumHandle = Long.MIN_VALUE;
    private final ViewDistanceFade viewDistanceFade = new ViewDistanceFade();
    private int perfParticles = -1;
    private int cntParticles = -1;
    private int cntParticleDraws = -1;
    private int cntParticleDynamicQuads = -1;
    private int cntParticleNewSlots = -1;
    private int cntParticleRemovedSlots = -1;
    private int cntParticleCapacityGrows = -1;
    private int cntParticleCapacity = -1;
    private int cntParticleMeshUpdates = -1;
    
    /**
     * Initializes the RTX Remix runtime with the Minecraft window
     */
    public void initialize() {
        LOGGER.info("Initializing RTX Remix renderer...");

        try {
            lastCameraMediumHandle = Long.MIN_VALUE;

            Minecraft client = Minecraft.getInstance();
            remixApi = NativeRuntime.startup(client.getWindow().handle());
            windowHandle = NativeRuntime.windowHandle();

            postProcessingSettings = new PostProcessingSettings(remixApi::setConfigVariable);
            
            backend = RenderBackend.current();
            interop = backend.createInterop(remixApi);
            LOGGER.info("{} runtime interop initialized", backend);
            
            textureManager = new BlockTextureManager(this);
            chunkManager = new ChunkManager(this);
            lightManager = new LightManager(this);
            skyRenderer = new SkyRenderer(this);
            cloudRenderer = new CloudRenderer(this);
            
            particleAtlasManager = new ParticleAtlasManager(this);
            entityPass = new RemixEntityPass(lightManager::ownsLightThisFrame);
            entitySubmitter = new RemixEntitySubmitter(remixApi, particleAtlasManager);
            firstPersonPass = new RemixFirstPersonPass();

            blockEntityPass = new RemixBlockEntityPass();
            blockDamagePass = new RemixBlockDamagePass();

            particleRenderer = new RemixParticleRenderer(this, particleAtlasManager);
            weatherRenderer = new RemixWeatherRenderer();
            
            perfCamera = perfTimer.registerSection("Camera");
            perfLights = perfTimer.registerSection("Lights");
            perfChunkGen = perfTimer.registerSection("Chunk.Gen");
            perfChunkDraw = perfTimer.registerSection("Chunk.Draw");
            perfEntities = perfTimer.registerSection("Entities");
            perfSky = perfTimer.registerSection("Sky");
            perfClouds = perfTimer.registerSection("Clouds");
            perfCloudGen = perfTimer.registerSection("Cloud.Gen");
            cntTextureSharedSavedMB = perfTimer.registerCounter("Texture.SharedSavedMiB");
            cntPresentedFrames = perfTimer.registerCounter("DLSS.RenderedPresents");
            cntGeneratedFrames = perfTimer.registerCounter("DLSS.GeneratedPresents");
            cntDeviceLocalUsedMB = perfTimer.registerCounter("Vulkan.DeviceLocalUsedMiB");
            cntDeviceLocalBudgetMB = perfTimer.registerCounter("Vulkan.DeviceLocalBudgetMiB");
            cntRenderWidth = perfTimer.registerCounter("Vulkan.RenderWidth");
            cntRenderHeight = perfTimer.registerCounter("Vulkan.RenderHeight");
            cntInteropSnapshots = perfTimer.registerCounter("Interop.SnapshotBytes");
            cntInteropSnapshotCopies = perfTimer.registerCounter("Interop.SnapshotCopiedBytes");
            cntEntities = perfTimer.registerCounter("Ent.Count");
            cntEntLayers = perfTimer.registerCounter("Ent.Layers");
            cntEntDraws = perfTimer.registerCounter("Ent.Draws");
            cntEntBones = perfTimer.registerCounter("Ent.Bones");
            cntEntMeshCreates = perfTimer.registerCounter("Ent.MeshCreates");
            cntCloudVisible = perfTimer.registerCounter("Cloud.Visible");
            cntCloudInstances = perfTimer.registerCounter("Cloud.Instances");
            cntCloudPaletteCreates = perfTimer.registerCounter("Cloud.PaletteCreates");
            cntCloudDraws = perfTimer.registerCounter("Cloud.Draws");
            cntCloudVertices = perfTimer.registerCounter("Cloud.Vertices");
            cntCloudTriangles = perfTimer.registerCounter("Cloud.Triangles");
            cntEntSkipped = perfTimer.registerCounter("Ent.Skipped");
            // Only the capture half is addressable from Java; the submit half is
            // almost entirely native, so judging them together hides which one moved.
            perfEntCapture = perfTimer.registerSection("Ent.Capture");
            perfEntSubmit = perfTimer.registerSection("Ent.Submit");
            // Selecting which off-screen entities to capture: the world walk and the
            // closest-first heap. The capture and submit work that follows is reported by
            // Ent.Capture and Ent.Submit, so these three do not overlap and can be summed.
            perfEntOffSweep = perfTimer.registerSection("Ent.OffSweep");
            cntEntOffFrustum = perfTimer.registerCounter("Ent.OffFrustum");
            cntEntOffDraws = perfTimer.registerCounter("Ent.OffDraws");
            cntEntOffReused = perfTimer.registerCounter("Ent.OffReused");
            cntEntPoolSize = perfTimer.registerCounter("Ent.PoolSize");
            // Block entities are captured and submitted in place during vanilla's
            // world render, so both halves are timed there and injected here.
            perfBlockEntityCapture = perfTimer.registerSection("BE.Capture");
            perfBlockEntitySubmit = perfTimer.registerSection("BE.Submit");
            perfSubmit = perfTimer.registerSection("Submit");
            cntBlockEntities = perfTimer.registerCounter("BE.Count");
            cntBlockEntityParts = perfTimer.registerCounter("BE.Parts");
            cntBlockEntityMeshCreates = perfTimer.registerCounter("BE.MeshCreates");
            perfAtlas = perfTimer.registerSection("Atlas");
            perfParticles = perfTimer.registerSection("Particles");
            cntParticles = perfTimer.registerCounter("Ptcl.Count");
            cntParticleDraws = perfTimer.registerCounter("Ptcl.MeshBuckets");
            cntParticleDynamicQuads = perfTimer.registerCounter("Ptcl.DynamicQuads");
            cntParticleNewSlots = perfTimer.registerCounter("Ptcl.NewSlots");
            cntParticleRemovedSlots = perfTimer.registerCounter("Ptcl.RemovedSlots");
            cntParticleCapacityGrows = perfTimer.registerCounter("Ptcl.CapacityGrows");
            cntParticleCapacity = perfTimer.registerCounter("Ptcl.Capacity");
            cntParticleMeshUpdates = perfTimer.registerCounter("Ptcl.MeshUpdates");
            
            initAssetStores();
            blockOutlinePass = new RemixBlockOutlinePass(remixApi);
            
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
            LOGGER.info("RTX Remix renderer initialized successfully!");

        } catch (RemixException e) {
            LOGGER.error("Failed to initialize RTX Remix: {} (error code: {})",
                e.getMessage(), e.getErrorCode());
            releasePartialInitialization(e);
            throw e;
        } catch (Exception e) {
            LOGGER.error("Failed to initialize RTX Remix", e);
            releasePartialInitialization(e);
            throw new RuntimeException("RTX Remix initialization failed", e);
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
            LOGGER.error("Error tearing down a failed RTX Remix initialization", e);
        }
    }

    private void initAssetStores() {
        RemixMeshStore.init(remixApi);
        RemixInstanceStore.init(remixApi);
        RemixTextureStore.init(remixApi);
        RemixMaterialStore.init(remixApi);
        RemixLightStore.init(remixApi);
    }

    private void gcAssetStores() {
        if (RemixMeshStore.get() != null)      RemixMeshStore.get().gc();
        if (RemixInstanceStore.get() != null)  RemixInstanceStore.get().gc();
        if (RemixTextureStore.get() != null)   RemixTextureStore.get().gc();
        if (RemixMaterialStore.get() != null)  RemixMaterialStore.get().gc();
        if (RemixLightStore.get() != null)     RemixLightStore.get().gc();
    }

    /**
     * The single location of the RTX Remix runtime. The extractor installs the
     * packaged runtime here and development launches stage it here, so no other
     * path is searched.
     */
    static Path runtimeDll(Path gameDir) {
        return gameDir.resolve("remix-sdk").resolve("d3d9.dll");
    }
    
    /**
     * Applies pending world/resource invalidation and preloads the large
     * block-entity atlases. Call before vanilla's world render, which is where
     * block entities are captured in place.
     */
    public void beginWorldRenderFrame() {
        if (!initialized) return;

        applyPendingWorldResourceChanges();
        if (RtxRemixMod.isBlockEntitiesEnabled()) {
            preloadEntityAtlasesForCapture();
        }
    }

    private void applyPendingWorldResourceChanges() {
        if (!pendingTextureReload) return;

        boolean recaptureTerrain = pendingTerrainRecaptureAfterTextureReload;
        pendingTextureReload = false;
        LOGGER.info("Reloading textures, materials, and terrain");
        joinInFlightParticleRenderTask();
        discardPendingParticleFrame();
        AtlasAnimationForwarder.clear();
        // Retained meshes hold material handles. Release them before any of the
        // materials they reference are destroyed or rebuilt: the submitter's own,
        // the block atlas material it borrows from BlockTextureManager, and the
        // particle atlas material it borrows from ParticleAtlasManager.
        if (entitySubmitter != null) entitySubmitter.reloadMaterials();
        if (entityPass != null) entityPass.discardPoseSnapshots();
        if (particleRenderer != null) particleRenderer.cleanup();
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
                LongSet built = RtxRemixMod.builtSections(client);
                client.levelExtractor.allChanged();
                RtxRemixMod.recaptureTerrainForRemix(client, built);
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
        RtxRemixMod mod = RtxRemixMod.getInstance();
        RemixRenderer renderer = mod == null ? null : mod.getRenderer();
        if (renderer == null || !renderer.initialized || !RtxRemixMod.isRtxEnabled()) {
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
        if (initialized && RtxRemixMod.isRtxEnabled() && RtxRemixMod.isEntitiesEnabled()) {
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
        if (!RtxRemixMod.isRtxEnabled() || !RtxRemixMod.isEntitiesEnabled()) {
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
                || !RtxRemixMod.isRtxEnabled() || !RtxRemixMod.isEntitiesEnabled()) return false;
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
                                            RemixBlockEntityPass.BlockEntityRenderCall call) {
        if (!initialized || remixApi == null || blockEntityPass == null || entitySubmitter == null) {
            return false;
        }
        if (!RtxRemixMod.isRtxEnabled() || !RtxRemixMod.isBlockEntitiesEnabled()) {
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
        if (!RtxRemixMod.isRtxEnabled() || !RtxRemixMod.isWorldEnabled()) {
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

        perfTimer.beginFrame();
        long epoch = com.nvidia.rtxremix.minecraft.vulkan.NativeVulkanSurface.presentationEpoch();
        long rendered = com.nvidia.rtxremix.minecraft.vulkan.NativeVulkanSurface.renderedFrames();
        long generated = com.nvidia.rtxremix.minecraft.vulkan.NativeVulkanSurface.generatedFrames();
        if (epoch == presentationEpoch) {
            perfTimer.count(cntPresentedFrames, Math.max(0, rendered - lastPresentedFrames));
            perfTimer.count(cntGeneratedFrames, Math.max(0, generated - lastGeneratedFrames));
        }
        presentationEpoch = epoch;
        lastPresentedFrames = rendered;
        lastGeneratedFrames = generated;
        perfTimer.count(cntDeviceLocalUsedMB,
                com.nvidia.rtxremix.minecraft.vulkan.NativeVulkanSurface.deviceLocalUsageBytes() / (1024L * 1024L));
        perfTimer.count(cntDeviceLocalBudgetMB,
                com.nvidia.rtxremix.minecraft.vulkan.NativeVulkanSurface.deviceLocalBudgetBytes() / (1024L * 1024L));
        perfTimer.count(cntRenderWidth, interop.renderWidth());
        perfTimer.count(cntRenderHeight, interop.renderHeight());
        perfTimer.count(cntInteropSnapshots, interop.snapshotTextureBytes());
        perfTimer.count(cntInteropSnapshotCopies, interop.copiedSnapshotBytes());

        CompletableFuture<ParticleRenderResult> particleFuture = null;
        boolean entityFrameClosed = false;
        try (ProfileScope frameScope = ProfileScope.begin("renderAndSubmitFrame")) {
            Minecraft client = Minecraft.getInstance();
            ClientLevel world = client.level;
            
            if (world == null || client.gameRenderer == null) {
                if (chunkManager != null) chunkManager.hideRetainedInstances();
                discardPendingParticleFrame();
                // Closed by the finally, like the RTX-disabled branch below. endFrame is not
                // idempotent: it re-adds elapsed from the same frame start and refolds min,
                // max and the frame count.
                return;
            }

            // RTX disabled: hand all rendering back to vanilla's GL path. Skip every
            // scene-submission step below (particle task, camera, lights, chunks,
            // entities, block entities, sky, atlas flush, Submit). The present path
            // (RenderSystemMixin -> preparePresentFrame -> releaseInteropFrame) still runs
            // unconditionally and composites vanilla's Vulkan frame, exactly like the
            // no-world case above. The finally block closes the frame (gc, endFrame,
            // benchmark). Vulkan shared-image semaphore signaling lives in the present path and is
            // intentionally left untouched so off<->on transitions don't desync.
            if (!RtxRemixMod.isRtxEnabled()) {
                if (chunkManager != null) chunkManager.hideRetainedInstances();
                // A payload prepared before the toggle holds world-space vertices from the
                // old camera position; publishing it on re-enable would draw stale geometry.
                discardPendingParticleFrame();
                return;
            }

            // Submit the prepared frame from the previous render call before dispatching
            // the next prepare task. The render thread never blocks on particles here:
            // the work is already done and waiting. A world swap that keeps the connection
            // alive, such as a portal, replaces client.world without a disconnect or a null
            // frame, so the payload is checked against the world it was prepared for rather
            // than relying on a teardown path to have discarded it.
            if (pendingParticleFrame != null && pendingParticleFrameWorld != world) {
                discardPendingParticleFrame();
            }
            submitPendingParticleFrame();

            Camera camera = client.gameRenderer.mainCamera();
            float tickDelta = client.getDeltaTracker().getGameTimeDeltaPartialTick(true);
            particleFuture = startParticleRenderTask(tickDelta, ProfileScope.currentFrameIndex());
            
            perfTimer.begin(perfCamera);
            try (ProfileScope cameraScope = ProfileScope.begin("Camera")) {
                updateCamera(camera, tickDelta);
            } finally {
                perfTimer.end(perfCamera);
            }
            
            Vec3 cameraPos = camera.position();

            perfTimer.begin(perfLights);
            try (ProfileScope lightsScope = ProfileScope.begin("Lights")) {
                // Entity lights first: updateLights() draws them, so updating
                // after it would submit last frame's positions. Visible as a
                // one-frame lag between a spinning beacon and its beam.
                lightManager.updateEntityLights(world, camera, tickDelta);
                lightManager.updateLights(world);
            } finally {
                perfTimer.end(perfLights);
            }

            if (RtxRemixMod.isWorldEnabled()) {
                try {
                    chunkManager.updateVisibleChunks(world, cameraPos, perfTimer, perfChunkGen, perfChunkDraw);
                } catch (Exception e) {
                    LOGGER.error("Error updating chunks", e);
                }
            } else {
                chunkManager.hideRetainedInstances();
            }

            entitySubmitter.beginFrame();
            try {
                if (RtxRemixMod.isEntitiesEnabled()) {
                    perfTimer.begin(perfEntities);
                    try (ProfileScope entitiesScope = ProfileScope.begin("Entities")) {
                        RemixEntityPass.FrameStats stats =
                                entityPass.finishFrame(
                                        world,
                                        cameraPos,
                                        tickDelta,
                                        lightManager.getFirstPersonShadowModelOffset(),
                                        entitySubmitter);
                        entitiesScope.entityCount(stats.entityCount())
                                .layerCount(stats.layerCount())
                                .boneCount(stats.boneCount())
                                .drawCalls(stats.drawCount())
                                .meshCreates(stats.meshCreates());
                        perfTimer.count(cntEntities, stats.entityCount());
                        perfTimer.count(cntEntLayers, stats.layerCount());
                        perfTimer.count(cntEntBones, stats.boneCount());
                        perfTimer.count(cntEntDraws, stats.drawCount());
                        perfTimer.count(cntEntMeshCreates, stats.meshCreates());
                        perfTimer.count(cntEntSkipped, stats.capSkipped());
                        perfTimer.injectNanos(perfEntCapture, stats.captureMicros() * 1000L);
                        perfTimer.injectNanos(perfEntSubmit, stats.submitMicros() * 1000L);
                        perfTimer.count(cntEntOffFrustum, stats.offFrustumCount());
                        perfTimer.injectNanos(perfEntOffSweep, stats.offSweepMicros() * 1000L);
                        perfTimer.count(cntEntOffDraws, stats.offFrustumDraws());
                        perfTimer.count(cntEntOffReused, stats.offFrustumReused());
                        perfTimer.count(cntEntPoolSize, stats.poolSize());
                    } catch (Exception e) {
                        LOGGER.error("Error rendering entities", e);
                    } finally {
                        perfTimer.end(perfEntities);
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

            // Render sky (dome, sun, moon)
            if (RtxRemixMod.isSkyEnabled()) {
                perfTimer.begin(perfSky);
                try (ProfileScope skyScope = ProfileScope.begin("Sky")) {
                    skyRenderer.render(world, cameraPos, tickDelta);
                } catch (Exception e) {
                    LOGGER.error("Error rendering sky", e);
                } finally {
                    perfTimer.end(perfSky);
                }
            }

            if (RtxRemixMod.isWorldActive()) {
                perfTimer.begin(perfClouds);
                try (ProfileScope cloudScope = ProfileScope.begin("Clouds")) {
                    cloudRenderer.render(world, cameraPos, tickDelta);
                    perfTimer.injectNanos(perfCloudGen, cloudRenderer.getLastGenerationNanos());
                    perfTimer.count(cntCloudVisible, cloudRenderer.getLastCloudCount());
                    perfTimer.count(cntCloudInstances, cloudRenderer.getLastInstanceCount());
                    perfTimer.count(
                            cntCloudPaletteCreates,
                            cloudRenderer.getLastPaletteCreateCount());
                    perfTimer.count(cntCloudDraws, cloudRenderer.getLastDrawCount());
                    perfTimer.count(cntCloudVertices, cloudRenderer.getLastVertexCount());
                    perfTimer.count(cntCloudTriangles, cloudRenderer.getLastTriangleCount());
                    cloudScope
                            .drawCalls(cloudRenderer.getLastDrawCount())
                            .vertexCount(cloudRenderer.getLastVertexCount());
                } catch (Exception e) {
                    LOGGER.error("Error rendering clouds", e);
                } finally {
                    perfTimer.end(perfClouds);
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
                perfTimer.begin(perfAtlas);
                try (ProfileScope atlasScope = ProfileScope.begin("Atlas")) {
                    RemixTexture albedoTex = textureManager.getBlockAtlasTexture();
                    RemixTexture emissiveTex = textureManager.getEmissiveAtlasTexture();
                    RemixTexture roughnessTex = textureManager.getRoughnessAtlasTexture();
                    RemixTexture metallicTex = textureManager.getMetallicAtlasTexture();
                    RemixTexture sssTex = textureManager.getSssAtlasTexture();
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
                    perfTimer.end(perfAtlas);
                }
            }

            perfTimer.begin(perfSubmit);
            try (ProfileScope submitScope = ProfileScope.begin("Submit")) {
                submitFrame();
                submitFrameCalled = true;
            } finally {
                perfTimer.end(perfSubmit);
            }

            // Join after present: if the particle worker is slow, the delay lands in
            // the inter-frame gap rather than pushing out this frame's submit.
            joinAndStoreParticleFrame(particleFuture, world);
            particleFuture = null;

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
            // Error path: future wasn't joined in the try block, so discard without submitting.
            if (particleFuture != null) {
                discardParticleRenderTask(particleFuture);
            }
            flushParticleFrameTiming();
            try (ProfileScope gcScope = ProfileScope.begin("gcAssetStores")) {
                gcAssetStores();
            }
            perfTimer.count(cntTextureSharedSavedMB, RemixTextureStore.get().getSharedImageSavingsBytes() / (1024L * 1024L));
            perfTimer.endFrame();
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
        RemixBlockEntityPass.FrameStats stats = blockEntityPass.finishFrame();
        if (!RtxRemixMod.isBlockEntitiesEnabled()) {
            return;
        }
        perfTimer.count(cntBlockEntities, stats.count());
        perfTimer.count(cntBlockEntityParts, stats.parts());
        perfTimer.count(cntBlockEntityMeshCreates, stats.meshCreates());
        perfTimer.injectNanos(perfBlockEntityCapture, stats.captureMicros() * 1000L);
        perfTimer.injectNanos(perfBlockEntitySubmit, stats.submitMicros() * 1000L);
    }

    private CompletableFuture<ParticleRenderResult> startParticleRenderTask(float tickDelta, long frameIndex) {
        if (!RtxRemixMod.isParticlesEnabled() || particleRenderer == null || particleAtlasManager == null) {
            return null;
        }

        if (!particleAtlasManager.isInitialized()) {
            particleAtlasManager.initialize();
        }
        if (!particleAtlasManager.isInitialized()) {
            return null;
        }

        CompletableFuture<ParticleRenderResult> future = CompletableFuture.supplyAsync(
                () -> renderParticlesOnWorker(tickDelta, frameIndex),
                particleExecutor);
        particleRenderFuture = future;
        return future;
    }

    private ParticleRenderResult renderParticlesOnWorker(float tickDelta, long frameIndex) {
        RemixAPI.tracyThreadName("Particles");
        long previousFrameIndex = ProfileScope.setFrameIndexForCurrentThread(frameIndex);
        PreparedFrame preparedFrame = null;
        // The scope records the worker's own duration on the worker thread, which is where it
        // belongs. It is deliberately not returned to the render thread: it is off the critical
        // path, so charging it to a render-thread section overstates that section.
        try (ProfileScope particleScope = ProfileScope.begin("Particles")) {
            preparedFrame = particleRenderer.prepare(tickDelta);
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
     * moments before {@code particleRenderer.cleanup()} would leak them.
     */
    private void discardParticleRenderTask(CompletableFuture<ParticleRenderResult> future) {
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
            if (particleRenderFuture == future) {
                particleRenderFuture = null;
            }
        }
    }

    /** Submits the frame prepared by the previous render call. No blocking: the work is already done. */
    private void submitPendingParticleFrame() {
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
            LOGGER.error("Error submitting particles", e);
        } finally {
            frame.close();
            particleNanosThisFrame += System.nanoTime() - submitStart;
        }
    }

    /**
     * Joins the particle worker after {@code submitFrame()} and stores the result for the
     * next frame's submit. Never submits native Remix calls, which happen in
     * {@link #submitPendingParticleFrame()}.
     *
     * <p>Only the wait itself is charged to the frame, not the worker's own run time. Since the
     * prepare task is dispatched at the top of the frame and joined after Submit, it has
     * normally finished long before this runs, so its duration is not on the critical path.
     * Charging it made the section report several times what the render thread actually paid,
     * and made it appear to grow on slow frames it had not contributed to. Timing the wait
     * keeps a worker that genuinely does stall the frame visible.
     *
     * @param preparedFor the world the prepare task was dispatched against, recorded with the
     *                    payload so the next frame can refuse it after a world swap
     */
    private void joinAndStoreParticleFrame(CompletableFuture<ParticleRenderResult> future,
                                           ClientLevel preparedFor) {
        if (future == null) return;
        try {
            long waitStart = System.nanoTime();
            ParticleRenderResult result = future.join();
            particleNanosThisFrame += System.nanoTime() - waitStart;

            if (result.error() != null) {
                LOGGER.error("Error rendering particles", result.error());
            } else {
                pendingParticleFrame = result.preparedFrame();
                pendingParticleFrameWorld = preparedFor;
            }
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            LOGGER.error("Error rendering particles", cause);
        } finally {
            if (particleRenderFuture == future) particleRenderFuture = null;
        }
    }

    /**
     * Publishes the frame's total particle cost as one sample. {@code injectNanos} overwrites
     * rather than accumulates, so the double-buffer's two contributions (the previous frame's
     * submit and this frame's prepare) must be summed before a single injection.
     */
    private void flushParticleFrameTiming() {
        perfTimer.injectNanos(perfParticles, particleNanosThisFrame);
        particleNanosThisFrame = 0L;
    }

    /** Closes the pending particle frame without submitting it. Used during cleanup and world changes. */
    private void discardPendingParticleFrame() {
        PreparedFrame frame = pendingParticleFrame;
        pendingParticleFrame = null;
        pendingParticleFrameWorld = null;
        if (frame != null) frame.close();
    }

    private void joinInFlightParticleRenderTask() {
        CompletableFuture<ParticleRenderResult> future = particleRenderFuture;
        if (future != null) {
            discardParticleRenderTask(future);
        }
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

    /** Exactly one component is set; a prepare that failed has already closed its frame. */
    private record ParticleRenderResult(
            PreparedFrame preparedFrame,
            Exception error) {}

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
     * Recomputes the band that {@code /rtx viewdistance} and the settings
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
                RemixMaterial cameraMediumMaterial = textureManager.getWaterCameraMediumMaterial();
                if (cameraMediumMaterial != null && !cameraMediumMaterial.isDeleted()) {
                    materialHandle = cameraMediumMaterial.getHandle();
                }
            }
    
            remixApi.setCameraMediumMaterial(materialHandle);
            // Conf layers own the volumetric profile and the view-distance
            // fade. A driver left inactive by a failed reload drives nothing
            // until the next successful /rtx conf reload.
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
    
    private void submitFrame() {
        remixApi.submit(vulkanSubmit);
        submitFrameCalled = true;
    }

    /** Draws the runtime's independent menu layer above Minecraft screens, immediately before the surface blit. */
    public void compositeRemixMenus() {
        if (!RtxRemixMod.getRemixInputGate().isCaptureSessionOpen()) return;
        if (!submitFrameCalled) submitFrame();
        interop.refreshImages();
        interop.acquireImages();
        nativeMenuOverlay.render(interop.menuTexture());
    }
    
    /** Records the shared-image release in Minecraft's frame submission. */
    public void preparePresentFrame() {
        try (ProfileScope ignored = ProfileScope.begin("Interop.ClientToRemix")) {
            releaseInteropFrame();
        }
    }

    /** Completes the runtime frame after Minecraft submits the shared-image release. */
    public void presentFrame(com.nvidia.rtxremix.VulkanClientFrameInfo frame) {
        presentInfo.hwndOverride = windowHandle;
        presentInfo.vulkanFrame = frame;
        presentInfo.clientDoneSemaphore_ValueToWait = interop.clientDoneValue();
        try (ProfileScope ignored = ProfileScope.begin("Remix.Present")) {
            remixApi.present(presentInfo);
        }
        submitFrameCalled = false;
    }

    /** Acquires runtime images even when disabled, then composites the world before screen effects and HUD. */
    public boolean compositeFrameColor() {
        try (ProfileScope ignored = ProfileScope.begin("Interop.RemixToClient")) {
            if (!submitFrameCalled) submitFrame();
            interop.refreshImages();
            interop.acquireImages();
            return RtxRemixMod.isRtxEnabled() && interop.blitColor(
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

    private void releaseInteropFrame() {
        if (!submitFrameCalled) {
            submitFrame();
        }

        interop.refreshImages();
        interop.acquireImages();
        interop.releaseImages();
    }

    /** Runs on the client executor so the clear lands during teardown, before the next world initializes. */
    public void clearWorld() {
        LOGGER.info("World clear requested (will execute on render thread)");
        Minecraft.getInstance().execute(() -> {
            if (initialized) {
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
        joinInFlightParticleRenderTask();
        discardPendingParticleFrame();
        
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
        if (particleRenderer != null) {
            particleRenderer.cleanup();
        }
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
        LOGGER.info("Shutting down RTX Remix renderer...");
        initialized = false;
        pendingTextureReload = false;
        pendingTerrainRecaptureAfterTextureReload = false;
        List<RuntimeException> failures = new ArrayList<>();

        runShutdownStep(failures, AtlasAnimationForwarder::clear);
        runShutdownStep(failures, this::joinInFlightParticleRenderTask);
        runShutdownStep(failures, this::discardPendingParticleFrame);
        runShutdownStep(failures, this::shutdownParticleExecutor);
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
        if (particleRenderer != null) {
            var resource = particleRenderer;
            particleRenderer = null;
            runShutdownStep(failures, resource::cleanup);
        }
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
        runShutdownStep(failures, nativeMenuOverlay::close);
        if (interop != null) {
            var resource = interop;
            interop = null;
            runShutdownStep(failures, resource::close);
        }
        // Presentation outlives scene capture and is released with the host Vulkan device.
        remixApi = null;
        LOGGER.info("RTX Remix renderer shut down");
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
        RemixInstanceStore.shutdown();
        RemixMeshStore.shutdown();
        RemixLightStore.shutdown();
        RemixMaterialStore.shutdown();
        RemixTextureStore.shutdown();
    }

    private void shutdownParticleExecutor() {
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

    public RemixWeatherRenderer getWeatherRenderer() {
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

    public RemixEntityPass getEntityPass() {
        return entityPass;
    }

    /** Returns the current captured first-person held-beacon emitter anchor, if available. */
    public Vec3 getFirstPersonHeldLightPosition(EquipmentSlot slot) {
        return firstPersonPass != null
                ? firstPersonPass.getPendingHeldLightPosition(slot)
                : null;
    }

    public void captureFirstPersonModel(
            FirstPersonHandsAndItemsRenderer heldItemRenderer,
            float tickDelta,
            PoseStack matrices,
            LocalPlayer player,
            PlayerRenderState playerState,
            Matrix4fc modelView) {
        if (firstPersonPass != null && initialized && RtxRemixMod.isRtxEnabled()) {
            firstPersonPass.capture(heldItemRenderer, tickDelta, matrices, player, playerState, modelView);
        }
    }
}
