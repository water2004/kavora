/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora;

import org.edtp.kavora.asset.KavoraTextureStore;
import org.edtp.kavora.cloud.CloudRenderer;
import org.edtp.kavora.entity.KavoraBlockEntityPass;
import org.edtp.kavora.entity.KavoraEntityPass;
import org.edtp.kavora.profiling.PerformanceTimer;
import org.edtp.kavora.profiling.ProfileScope;
import org.edtp.kavora.render.KavoraInterop;
import org.edtp.kavora.vulkan.NativeVulkanSurface;

/** Render-thread measurements for capture, submission and interop. Owns metric IDs and presentation deltas. */
final class KavoraFrameMetrics extends PerformanceTimer {
    int perfCamera = -1;
    int perfLights = -1;
    int perfChunkGen = -1;
    int perfChunkDraw = -1;
    int perfEntities = -1;
    int perfSky = -1;
    int perfClouds = -1;
    private int perfCloudGen = -1;
    int perfAtlas = -1;
    int perfSubmit = -1;
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

    void registerFrameMetrics() {
        perfCamera = registerSection("Camera");
        perfLights = registerSection("Lights");
        perfChunkGen = registerSection("Chunk.Gen");
        perfChunkDraw = registerSection("Chunk.Draw");
        perfEntities = registerSection("Entities");
        perfSky = registerSection("Sky");
        perfClouds = registerSection("Clouds");
        perfCloudGen = registerSection("Cloud.Gen");
        cntTextureSharedSavedMB = registerCounter("Texture.SharedSavedMiB");
        cntPresentedFrames = registerCounter("DLSS.RenderedPresents");
        cntGeneratedFrames = registerCounter("DLSS.GeneratedPresents");
        cntDeviceLocalUsedMB = registerCounter("Vulkan.DeviceLocalUsedMiB");
        cntDeviceLocalBudgetMB = registerCounter("Vulkan.DeviceLocalBudgetMiB");
        cntRenderWidth = registerCounter("Vulkan.RenderWidth");
        cntRenderHeight = registerCounter("Vulkan.RenderHeight");
        cntInteropSnapshots = registerCounter("Interop.SnapshotBytes");
        cntInteropSnapshotCopies = registerCounter("Interop.SnapshotCopiedBytes");
        cntEntities = registerCounter("Ent.Count");
        cntEntLayers = registerCounter("Ent.Layers");
        cntEntDraws = registerCounter("Ent.Draws");
        cntEntBones = registerCounter("Ent.Bones");
        cntEntMeshCreates = registerCounter("Ent.MeshCreates");
        cntCloudVisible = registerCounter("Cloud.Visible");
        cntCloudInstances = registerCounter("Cloud.Instances");
        cntCloudPaletteCreates = registerCounter("Cloud.PaletteCreates");
        cntCloudDraws = registerCounter("Cloud.Draws");
        cntCloudVertices = registerCounter("Cloud.Vertices");
        cntCloudTriangles = registerCounter("Cloud.Triangles");
        cntEntSkipped = registerCounter("Ent.Skipped");
        // Only the capture half is addressable from Java; the submit half is
        // almost entirely native, so judging them together hides which one moved.
        perfEntCapture = registerSection("Ent.Capture");
        perfEntSubmit = registerSection("Ent.Submit");
        // Selecting which off-screen entities to capture: the world walk and the
        // closest-first heap. The capture and submit work that follows is reported by
        // Ent.Capture and Ent.Submit, so these three do not overlap and can be summed.
        perfEntOffSweep = registerSection("Ent.OffSweep");
        cntEntOffFrustum = registerCounter("Ent.OffFrustum");
        cntEntOffDraws = registerCounter("Ent.OffDraws");
        cntEntOffReused = registerCounter("Ent.OffReused");
        cntEntPoolSize = registerCounter("Ent.PoolSize");
        // Block entities are captured and submitted in place during vanilla's
        // world render, so both halves are timed there and injected here.
        perfBlockEntityCapture = registerSection("BE.Capture");
        perfBlockEntitySubmit = registerSection("BE.Submit");
        perfSubmit = registerSection("Submit");
        cntBlockEntities = registerCounter("BE.Count");
        cntBlockEntityParts = registerCounter("BE.Parts");
        cntBlockEntityMeshCreates = registerCounter("BE.MeshCreates");
        perfAtlas = registerSection("Atlas");
    }

    void beginFrame(KavoraInterop interop) {
        beginFrame();
        long epoch = NativeVulkanSurface.presentationEpoch();
        long rendered = NativeVulkanSurface.renderedFrames();
        long generated = NativeVulkanSurface.generatedFrames();
        if (epoch == presentationEpoch) {
            count(cntPresentedFrames, Math.max(0, rendered - lastPresentedFrames));
            count(cntGeneratedFrames, Math.max(0, generated - lastGeneratedFrames));
        }
        presentationEpoch = epoch;
        lastPresentedFrames = rendered;
        lastGeneratedFrames = generated;
        count(cntDeviceLocalUsedMB,
                NativeVulkanSurface.deviceLocalUsageBytes() / (1024L * 1024L));
        count(cntDeviceLocalBudgetMB,
                NativeVulkanSurface.deviceLocalBudgetBytes() / (1024L * 1024L));
        count(cntRenderWidth, interop.renderWidth());
        count(cntRenderHeight, interop.renderHeight());
        count(cntInteropSnapshots, interop.snapshotTextureBytes());
        count(cntInteropSnapshotCopies, interop.copiedSnapshotBytes());
    }

    void recordEntities(ProfileScope entitiesScope, KavoraEntityPass.FrameStats stats) {
        entitiesScope.entityCount(stats.entityCount())
                .layerCount(stats.layerCount())
                .boneCount(stats.boneCount())
                .drawCalls(stats.drawCount())
                .meshCreates(stats.meshCreates());
        count(cntEntities, stats.entityCount());
        count(cntEntLayers, stats.layerCount());
        count(cntEntBones, stats.boneCount());
        count(cntEntDraws, stats.drawCount());
        count(cntEntMeshCreates, stats.meshCreates());
        count(cntEntSkipped, stats.capSkipped());
        injectNanos(perfEntCapture, stats.captureMicros() * 1000L);
        injectNanos(perfEntSubmit, stats.submitMicros() * 1000L);
        count(cntEntOffFrustum, stats.offFrustumCount());
        injectNanos(perfEntOffSweep, stats.offSweepMicros() * 1000L);
        count(cntEntOffDraws, stats.offFrustumDraws());
        count(cntEntOffReused, stats.offFrustumReused());
        count(cntEntPoolSize, stats.poolSize());
    }

    void recordClouds(ProfileScope cloudScope, CloudRenderer cloudRenderer) {
        injectNanos(perfCloudGen, cloudRenderer.getLastGenerationNanos());
        count(cntCloudVisible, cloudRenderer.getLastCloudCount());
        count(cntCloudInstances, cloudRenderer.getLastInstanceCount());
        count(
                cntCloudPaletteCreates,
                cloudRenderer.getLastPaletteCreateCount());
        count(cntCloudDraws, cloudRenderer.getLastDrawCount());
        count(cntCloudVertices, cloudRenderer.getLastVertexCount());
        count(cntCloudTriangles, cloudRenderer.getLastTriangleCount());
        cloudScope
                .drawCalls(cloudRenderer.getLastDrawCount())
                .vertexCount(cloudRenderer.getLastVertexCount());
    }

    void recordBlockEntities(KavoraBlockEntityPass.FrameStats stats) {
        count(cntBlockEntities, stats.count());
        count(cntBlockEntityParts, stats.parts());
        count(cntBlockEntityMeshCreates, stats.meshCreates());
        injectNanos(perfBlockEntityCapture, stats.captureMicros() * 1000L);
        injectNanos(perfBlockEntitySubmit, stats.submitMicros() * 1000L);
    }

    void finishFrame() {
        count(cntTextureSharedSavedMB, KavoraTextureStore.get().getSharedImageSavingsBytes() / (1024L * 1024L));
        endFrame();
    }
}
