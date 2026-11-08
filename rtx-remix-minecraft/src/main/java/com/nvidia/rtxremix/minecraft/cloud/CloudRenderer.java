/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.cloud;

import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshSurface;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMesh;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterialStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterial;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import java.nio.ByteBuffer;
import com.mojang.blaze3d.platform.NativeImage;
import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MaterialInfoOpaqueEXT;
import com.nvidia.rtxremix.MaterialInfoOpaqueSubsurfaceEXT;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.Transform;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/** Owns classic cloud meshes and publishes the host state for native volumetric clouds. */
public final class CloudRenderer {
    private static final Identifier VANILLA_CLOUD_TEXTURE = Identifier.withDefaultNamespace("textures/environment/clouds.png");
    private static final float DEFAULT_VANILLA_EDGE_SMOOTHNESS = 1.5f;
    private static final float VANILLA_BASE_THICKNESS = 4.0f;
    private static final float VANILLA_LAYER_LIFT = 0.33f;
    private static final float DEFAULT_CLOUD_SIZE = 1.0f;
    private static final float DEFAULT_CLOUDS_HEIGHT = 0.0f;
    private static final int INSTANCE_CATEGORIES = InstanceCategoryBit.IGNORE_BAKED_LIGHTING;
    private static final float CLOUD_ALBEDO = 0.99f;
    private static final double VANILLA_COLOR_INFLUENCE = 0.2;
    private static final float SSS_RADIUS_SCALE = 5000.0f;
    private static final float SSS_MAX_SAMPLE_RADIUS = 1.0f;
    private final RemixRenderer renderer;
    private final MeshCreator meshCreator;
    private final VolumetricCloudState volumetricState;
    private final Map<Long, PaletteEntry> vanillaChunks = new HashMap<>();
    private final Set<Long> emptyVanillaChunks = new HashSet<>();
    private final Set<Long> pendingVanillaChunks = new HashSet<>();
    private final ConcurrentLinkedQueue<VanillaChunkResult> completedVanillaChunks = new ConcurrentLinkedQueue<>();
    private final Transform vanillaTransform = new Transform();
    private final com.nvidia.rtxremix.minecraft.sky.EnvironmentSnapshot environment =
            new com.nvidia.rtxremix.minecraft.sky.EnvironmentSnapshot();
    private ClientLevel currentWorld;
    private RemixMaterial material;
    private int cloudColor = 0x00ff_ffff;
    private int materialColor = -1;
    private boolean[] vanillaOccupancy;
    private int vanillaTextureWidth;
    private int vanillaTextureHeight;
    private RuntimeException vanillaFailure;
    private long vanillaGenerationEpoch;
    private boolean proceduralClouds;
    private float vanillaEdgeSmoothness = DEFAULT_VANILLA_EDGE_SMOOTHNESS;
    private float cloudSize = DEFAULT_CLOUD_SIZE;
    private float cloudsHeight = DEFAULT_CLOUDS_HEIGHT;
    private int lastCloudCount;
    private int lastInstanceCount;
    private int lastPaletteCreateCount;
    private int lastDrawCount;
    private int lastVertexCount;
    private int lastTriangleCount;
    private long lastGenerationNanos;

    public CloudRenderer(RemixRenderer renderer) {
        this(renderer, null);
    }

    CloudRenderer(RemixRenderer renderer, MeshCreator meshCreator) {
        this.renderer = renderer;
        this.meshCreator = meshCreator;
        this.volumetricState = new VolumetricCloudState((key, value) -> renderer.getRemixApi().setConfigVariable(key, value));
    }

    public void render(ClientLevel world, Vec3 cameraPos, float tickDelta) {
        resetFrameStats();
        if (world != currentWorld) {
            releaseResources();
            currentWorld = world;
        }
        Minecraft client = Minecraft.getInstance();
        environment.capture(world, client.gameRenderer.mainCamera(), tickDelta);
        double frameGameTime = renderer.getBenchmarkController().animationGameTime(environment.gameTime);
        float cloudHeight = environment.cloudHeight;
        boolean visible = client.options.getCloudStatus() != CloudStatus.OFF
                && environment.cloudAlpha > 0 && Float.isFinite(cloudHeight);
        if (proceduralClouds && visible) {
            volumetricState.update(frameGameTime / 20.0, cloudHeight + cloudsHeight, environment.rain, environment.thunder);
            return;
        }
        volumetricState.disable();
        if (!visible) {
            releaseResources();
            return;
        }
        if (!shouldSuppressVanillaClouds()) return;
        updateCloudColor(environment.cloudRed, environment.cloudGreen, environment.cloudBlue);
        drawVanillaClouds(cameraPos, cloudHeight, frameGameTime);
    }

    private static void destroyEntries(Iterable<PaletteEntry> entries) {
        RuntimeException failure = null;
        for (PaletteEntry entry : entries) {
            try {
                if (entry != null && !entry.mesh.isDeleted()) {
                    entry.mesh.destroy();
                }
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void ensureVanillaCloudMask() {
        if (vanillaFailure != null) {
            throw vanillaFailure;
        }
        if (vanillaOccupancy != null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        try {
            var resource = client.getResourceManager().getResource(VANILLA_CLOUD_TEXTURE)
                    .orElseThrow(() -> new IOException(
                            "Missing texture " + VANILLA_CLOUD_TEXTURE));
            try (NativeImage image = NativeImage.read(resource.open())) {
                vanillaTextureWidth = image.getWidth();
                vanillaTextureHeight = image.getHeight();
                vanillaOccupancy =
                        new boolean[vanillaTextureWidth * vanillaTextureHeight];
                for (int y = 0; y < vanillaTextureHeight; y++) {
                    for (int x = 0; x < vanillaTextureWidth; x++) {
                        vanillaOccupancy[y * vanillaTextureWidth + x] =
                                ((image.getPixel(x, y) >>> 24) & 0xff) >= 128;
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            vanillaFailure = new IllegalStateException(
                    "Failed to load vanilla cloud occupancy", e);
            throw vanillaFailure;
        }
    }

    private void scheduleVanillaChunk(VanillaCloudPlacement.Placement placement) {
        long textureKey = placement.textureKey();
        if (vanillaChunks.containsKey(textureKey)
                || emptyVanillaChunks.contains(textureKey)
                || !pendingVanillaChunks.add(textureKey)) {
            return;
        }

        long epoch = vanillaGenerationEpoch;
        boolean[] occupancy = vanillaOccupancy;
        int textureWidth = vanillaTextureWidth;
        int textureHeight = vanillaTextureHeight;
        float height = VANILLA_BASE_THICKNESS * cloudSize;
        float edgeSmoothness = vanillaEdgeSmoothness;
        CompletableFuture.supplyAsync(() -> VanillaCloudMeshGenerator.generate(
                        occupancy,
                        textureWidth,
                        textureHeight,
                        placement.textureX(),
                        placement.textureZ(),
                        placement.windowWidth(),
                        placement.windowHeight(),
                        placement.cellSizeX(),
                        placement.cellSizeZ(),
                        height,
                        edgeSmoothness))
                .whenComplete((generated, failure) -> completedVanillaChunks.add(
                        failure == null
                                ? new GeneratedVanillaChunk(epoch, textureKey, generated)
                                : new FailedVanillaChunk(epoch, textureKey, failure)));
    }

    private void publishVanillaChunks() {
        VanillaChunkResult completed;
        while ((completed = completedVanillaChunks.poll()) != null) {
            publishVanillaResult(completed);
        }
    }

    void publishVanillaResult(VanillaChunkResult completed) {
        if (completed.epoch() != vanillaGenerationEpoch) {
            return;
        }
        pendingVanillaChunks.remove(completed.textureKey());
        if (completed instanceof FailedVanillaChunk failed) {
            vanillaFailure = new IllegalStateException(
                    "Failed to generate vanilla cloud chunk", failed.failure());
            throw vanillaFailure;
        }
        GeneratedVanillaChunk generatedChunk = (GeneratedVanillaChunk) completed;
        if (vanillaChunks.containsKey(generatedChunk.textureKey())) {
            return;
        }

        VanillaCloudMeshGenerator.GeneratedMesh generated = generatedChunk.mesh();
        lastGenerationNanos += generated.generationNanos();
        if (generated.indices().length == 0) {
            emptyVanillaChunks.add(generatedChunk.textureKey());
            return;
        }
        RemixMesh mesh;
        try {
            mesh = createMesh(generated.vertices(), generated.indices());
        } catch (RuntimeException e) {
            vanillaFailure = new IllegalStateException(
                    "Failed to publish vanilla cloud chunk", e);
            throw vanillaFailure;
        }
        vanillaChunks.put(
                generatedChunk.textureKey(),
                new PaletteEntry(mesh, generated.vertexCount(), generated.triangleCount()));
        lastPaletteCreateCount++;
    }

    private RemixMesh createMesh(float[] packed, int[] indices) {
        if (meshCreator != null) {
            return meshCreator.create(packed, indices);
        }
        ensureMaterial();
        int vertexCount = packed.length / 6;
        ByteBuffer vertices = DirectBufferPool.acquire(vertexCount * HardcodedVertex.STRIDE);
        for (int i = 0; i < vertexCount; i++) {
            int base = i * 6;
            RemixMeshSurface.putVertex(vertices,
                    packed[base], packed[base + 1], packed[base + 2],
                    packed[base + 3], packed[base + 4], packed[base + 5],
                    0.0f, 0.0f,
                    0xffff_ffff, 0, 0);
        }
        vertices.flip();
        RemixMeshSurface surface = new RemixMeshSurface(material)
                .setVertexData(vertices, vertexCount)
                .setIndexDataFrom(indices, indices.length);
        RemixMesh mesh = new RemixMesh(new RemixMeshSurface[]{surface});
        RemixMeshStore.get().create(mesh);
        return mesh;
    }

    private void ensureMaterial() {
        if (material != null && !material.isDeleted()) {
            return;
        }

        RemixMaterial created = new RemixMaterial(createMaterialInfo(cloudColor));
        RemixMaterialStore.get().create(created);
        material = created;
        materialColor = cloudColor;
    }

    private void updateCloudColor(float red, float green, float blue) {
        int packedColor = packCloudColor(red, green, blue);
        cloudColor = packedColor;
        if (material == null || material.isDeleted() || materialColor == packedColor) {
            return;
        }

        try {
            RemixMaterialStore.get().update(material, createMaterialInfo(packedColor));
            materialColor = packedColor;
        } catch (RuntimeException e) {
            RuntimeException failure =
                    new IllegalStateException("Failed to update cloud material color", e);
            vanillaFailure = failure;
            throw failure;
        }
    }

    private static MaterialInfo createMaterialInfo(int packedColor) {
        float red = CLOUD_ALBEDO * ((packedColor >>> 16) & 0xff) / 255.0f;
        float green = CLOUD_ALBEDO * ((packedColor >>> 8) & 0xff) / 255.0f;
        float blue = CLOUD_ALBEDO * (packedColor & 0xff) / 255.0f;
        MaterialInfoOpaqueSubsurfaceEXT subsurface =
                MaterialInfoOpaqueSubsurfaceEXT.builder()
                        .diffusionProfile(true)
                        .transmittanceColor(0.999f, 0.999f, 0.999f)
                        .volumetricAnisotropy(0.0f)
                        .radius(0.98f, 0.98f, 0.98f)
                        .radiusScale(SSS_RADIUS_SCALE)
                        .maxSampleRadius(SSS_MAX_SAMPLE_RADIUS)
                        .build();
        MaterialInfoOpaqueEXT opaque = MaterialInfoOpaqueEXT.builder()
                .albedoConstant(red, green, blue)
                .roughnessConstant(1.0f)
                .metallicConstant(0.0f)
                .subsurfaceExt(subsurface)
                .build();
        return MaterialInfo.builder().opaqueExt(opaque).build();
    }

    static int packCloudColor(double red, double green, double blue) {
        return quantizeCloudColor(red) << 16
                | quantizeCloudColor(green) << 8
                | quantizeCloudColor(blue);
    }

    private static int quantizeCloudColor(double value) {
        if (Double.isNaN(value)) {
            value = 1.0;
        }
        double vanillaColor = Math.max(0.0, Math.min(1.0, value));
        double adjustedColor = 1.0 - VANILLA_COLOR_INFLUENCE * (1.0 - vanillaColor);
        return (int) Math.round(adjustedColor * 255.0);
    }

    private void drawVanillaClouds(Vec3 cameraPos, float cloudHeight, double worldTime) {
        ensureVanillaCloudMask();

        publishVanillaChunks();
        RemixAPI api = renderer.getRemixApi();
        VanillaCloudPlacement.forEachVisible(
                cameraPos.x,
                cameraPos.z,
                worldTime,
                vanillaTextureWidth,
                vanillaTextureHeight,
                placement -> {
                    PaletteEntry entry = vanillaChunks.get(placement.textureKey);
                    if (entry == null) {
                        if (!emptyVanillaChunks.contains(placement.textureKey)) {
                            scheduleVanillaChunk(placement.snapshot());
                        }
                        return;
                    }

                    float[] matrix = vanillaTransform.matrix;
                    matrix[0] = 1.0f;
                    matrix[1] = 0.0f;
                    matrix[2] = 0.0f;
                    matrix[3] = (float) placement.x;
                    matrix[4] = 0.0f;
                    matrix[5] = 1.0f;
                    matrix[6] = 0.0f;
                    matrix[7] = cloudHeight + cloudsHeight + VANILLA_LAYER_LIFT;
                    matrix[8] = 0.0f;
                    matrix[9] = 0.0f;
                    matrix[10] = 1.0f;
                    matrix[11] = (float) placement.z;
                    api.drawInstance(
                            entry.mesh.getHandle(),
                            matrix,
                            INSTANCE_CATEGORIES,
                            0,
                            0L);
                    lastDrawCount++;
                    lastInstanceCount++;
                    lastVertexCount += entry.vertexCount;
                    lastTriangleCount += entry.triangleCount;
                });
        lastCloudCount = lastInstanceCount;
    }

    public void cleanup() {
        try {
            releaseResources();
        } finally {
            currentWorld = null;
        }
    }

    public void disable() {
        releaseResources();
    }

    private void releaseResources() {
        volumetricState.disable();
        resetFrameStats();
        try {
            invalidateVanillaChunks();
        } finally {
            vanillaOccupancy = null;
            vanillaTextureWidth = 0;
            vanillaTextureHeight = 0;
            completedVanillaChunks.clear();
            destroyMaterial();
        }
    }

    private void destroyMaterial() {
        try {
            if (material != null && !material.isDeleted()) {
                material.destroy();
            }
        } finally {
            material = null;
            materialColor = -1;
        }
    }

    public boolean isProceduralClouds() { return proceduralClouds; }

    public boolean shouldSuppressVanillaClouds() {
        return proceduralClouds || vanillaFailure == null;
    }

    long vanillaGenerationEpoch() { return vanillaGenerationEpoch; }

    public void setProceduralClouds(boolean enabled) {
        if (proceduralClouds == enabled) {
            return;
        }
        proceduralClouds = enabled;
        releaseResources();
    }

    public float getVanillaEdgeSmoothness() {
        return vanillaEdgeSmoothness;
    }

    public void setVanillaEdgeSmoothness(float edgeSmoothness) {
        if (!Float.isFinite(edgeSmoothness) || edgeSmoothness < 0.0f) {
            throw new IllegalArgumentException("Vanilla cloud edge smoothness must be nonnegative");
        }
        if (Float.compare(vanillaEdgeSmoothness, edgeSmoothness) == 0) {
            return;
        }
        vanillaEdgeSmoothness = edgeSmoothness;
        invalidateVanillaChunks();
    }

    public float getCloudSize() {
        return cloudSize;
    }

    public void setCloudSize(float size) {
        if (!Float.isFinite(size) || size <= 0.0f) {
            throw new IllegalArgumentException("Cloud size must be positive");
        }
        if (Float.compare(cloudSize, size) == 0) {
            return;
        }
        cloudSize = size;
        invalidateVanillaChunks();
    }

    public float getCloudsHeight() {
        return cloudsHeight;
    }

    public void setCloudsHeight(float height) {
        if (!Float.isFinite(height)) {
            throw new IllegalArgumentException("Clouds height must be finite");
        }
        cloudsHeight = height;
    }

    private void invalidateVanillaChunks() {
        vanillaGenerationEpoch++;
        pendingVanillaChunks.clear();
        try {
            destroyEntries(vanillaChunks.values());
        } finally {
            vanillaChunks.clear();
            emptyVanillaChunks.clear();
            vanillaFailure = null;
        }
    }

    private void resetFrameStats() {
        lastCloudCount = 0;
        lastInstanceCount = 0;
        lastPaletteCreateCount = 0;
        lastDrawCount = 0;
        lastVertexCount = 0;
        lastTriangleCount = 0;
        lastGenerationNanos = 0L;
    }

    public int getLastCloudCount() { return lastCloudCount; }
    public int getLastInstanceCount() { return lastInstanceCount; }
    public int getLastPaletteCreateCount() { return lastPaletteCreateCount; }
    public int getLastDrawCount() { return lastDrawCount; }
    public int getLastVertexCount() { return lastVertexCount; }
    public int getLastTriangleCount() { return lastTriangleCount; }
    public long getLastGenerationNanos() { return lastGenerationNanos; }

    sealed interface VanillaChunkResult
            permits GeneratedVanillaChunk, FailedVanillaChunk {
        long epoch();
        long textureKey();
    }

    record GeneratedVanillaChunk(
            long epoch,
            long textureKey,
            VanillaCloudMeshGenerator.GeneratedMesh mesh) implements VanillaChunkResult {
    }

    record FailedVanillaChunk(
            long epoch,
            long textureKey,
            Throwable failure) implements VanillaChunkResult {
    }

    private record PaletteEntry(
            RemixMesh mesh,
            int vertexCount,
            int triangleCount) {
    }

    @FunctionalInterface
    interface MeshCreator {
        RemixMesh create(float[] vertices, int[] indices);
    }
}
