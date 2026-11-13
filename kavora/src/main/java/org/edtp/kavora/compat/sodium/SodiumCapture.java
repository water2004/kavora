/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.compat.sodium;

import org.edtp.kavora.KavoraMod;
import org.edtp.kavora.terrain.VanillaSectionBuilds;
import org.edtp.kavora.terrain.capture.BlockDataQueue;
import org.edtp.kavora.terrain.capture.BlockQuadEmitter;
import org.edtp.kavora.terrain.capture.SectionBlockData;
import org.edtp.kavora.terrain.ChunkManager;
import org.edtp.kavora.texture.BlockTextureManager;
import org.edtp.kavora.compat.sodium.mixin.SodiumWorldRendererAccessor;
import org.edtp.kavora.compat.sodium.mixin.SodiumSectionManagerAccessor;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.texture.SpriteFinderCache;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Captures Sodium's evaluated CPU quads while preserving its scheduler and renderer. */
public final class SodiumCapture {
    private static final ThreadLocal<State> WORK = ThreadLocal.withInitial(State::new);
    private static final LongOpenHashSet BUILT = new LongOpenHashSet();
    private static final class State {
        boolean capturing;
        SectionPos section;
        BlockDataQueue.CaptureToken token;
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final float[] positions = new float[12];
        final float[] uv = new float[8];
        final int[] colors = new int[4];
        Material material;
        String glassLayer;
        LevelSlice world;
        BlockState block;
    }

    public static void begin(RenderSection section, ChunkBuildContext context) {
        State state = WORK.get();
        state.capturing = VanillaSectionBuilds.beginBuild(KavoraMod::isRtxEnabled);
        state.material = null;
        state.glassLayer = null;
        state.world = null;
        state.block = null;
        if (!state.capturing) return;
        state.section = section.getPosition();
        state.token = BlockDataQueue.beginCapture(state.section.x(), state.section.z());
        var renderer = KavoraMod.renderer;
        BlockQuadEmitter.threadLocal().beginSection(state.section,
                renderer == null ? null : renderer.getTextureManager(), context.cache.getBlockModels());
    }

    public static void block(LevelSlice world, int x, int y, int z, BlockState block) {
        State state = WORK.get();
        if (!state.capturing) return;
        BlockQuadEmitter emitter = BlockQuadEmitter.threadLocal();
        emitter.finishEvaluatedBlock();
        state.glassLayer = BlockTextureManager.glassLayerOverride(block);
        state.world = world;
        state.block = block;
        state.material = null;
        emitter.processBlock(block, world, state.pos.set(x, y, z), block.isSolidRender());
    }

    public static void beginAppenders() {
        State state = WORK.get();
        if (!state.capturing) return;
        BlockQuadEmitter.threadLocal().finishEvaluatedBlock();
        state.glassLayer = null;
        state.world = null;
        state.block = null;
        state.material = null;
    }

    public static void material(Material material) {
        State state = WORK.get();
        if (state.capturing) state.material = material;
    }

    public static void quad(ChunkVertexEncoder.Vertex[] vertices, int materialBits) {
        State state = WORK.get();
        if (!state.capturing) return;
        float u = 0, v = 0;
        for (int i = 0; i < 4; i++) {
            var vertex = vertices[i];
            state.positions[i * 3] = vertex.x;
            state.positions[i * 3 + 1] = vertex.y;
            state.positions[i * 3 + 2] = vertex.z;
            state.uv[i * 2] = vertex.u;
            state.uv[i * 2 + 1] = vertex.v;
            int abgr = vertex.color;
            state.colors[i] = (abgr & 0xff00ff00) | ((abgr >>> 16) & 255) | ((abgr & 255) << 16);
            u += vertex.u; v += vertex.v;
        }
        var sprite = SpriteFinderCache.forBlockAtlas().find(u * 0.25f, v * 0.25f);
        Material material = state.material;
        String layer = state.glassLayer;
        if (layer == null) {
            layer = material != null && material.isTranslucent() ? "translucent"
                    : (materialBits >>> 1) != 0 ? "cutout" : "solid";
        }
        BlockQuadEmitter.threadLocal().captureEvaluatedQuad(state.positions, state.uv, state.colors, sprite, layer,
                state.world, state.pos, state.block, state.glassLayer != null);
    }

    public static void end(ChunkBuildOutput output, boolean cancelled) {
        State state = WORK.get();
        try {
            if (!state.capturing) return;
            BlockQuadEmitter emitter = BlockQuadEmitter.threadLocal();
            if (output == null || cancelled) {
                emitter.discardSection();
            } else {
                SectionBlockData data = emitter.finishSection(state.section);
                ((SodiumCaptureOutput) output).kavora$setCapture(new BlockDataQueue.QueuedCapture(data, state.token));
            }
        } finally {
            VanillaSectionBuilds.endBuild(state.capturing);
            state.capturing = false;
            state.section = null;
            state.token = null;
            state.material = null;
            state.world = null;
            state.block = null;
        }
    }

    public static void accepted(RenderSection section, ChunkBuildOutput output) {
        BUILT.add(section.getPosition().asLong());
        var capturedOutput = (SodiumCaptureOutput) output;
        ((SodiumRasterSection) section).kavora$setRasterUploadOmitted(
                capturedOutput.kavora$wasCaptured() && KavoraMod.isRemixWorldRendering());
        var capture = capturedOutput.kavora$takeCapture();
        if (capture != null) {
            if (KavoraMod.isRtxEnabled()) BlockDataQueue.enqueue(capture.data(), capture.capture());
            else if (capture.data().meshData() != null) capture.data().meshData().release();
        } else if (KavoraMod.isRtxEnabled() && output.info == net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo.EMPTY) {
            var pos = section.getPosition();
            BlockDataQueue.enqueue(new SectionBlockData(pos, new org.edtp.kavora.terrain.capture.SectionMeshData(List.of()), List.of(), -1L, -1L, -1L, -1L, -1L, -1L,
                    new long[64]), BlockDataQueue.beginCapture(pos.x(), pos.z()));
        }
    }

    public static void reset() { BUILT.clear(); }
    public static void removed(int x, int y, int z) { BUILT.remove(SectionPos.asLong(x, y, z)); }
    public static LongSet builtSections() { return new LongOpenHashSet(BUILT); }

    public static boolean scheduleRebuild(int x, int y, int z) {
        var renderer = SodiumWorldRenderer.instanceNullable();
        if (renderer == null) return false;
        var manager = ((SodiumWorldRendererAccessor) renderer).kavora$sectionManager();
        if (manager == null || ((SodiumSectionManagerAccessor) manager).kavora$sections().getCurrent(x, y, z) == null) return false;
        renderer.scheduleRebuildForChunk(x, y, z, false);
        return true;
    }

    public static LongSet queueSections(LongSet requested) {
        var client = Minecraft.getInstance();
        LongOpenHashSet queued = new LongOpenHashSet();
        if (client.level == null) return queued;
        var renderer = SodiumWorldRenderer.instanceNullable();
        if (renderer == null) return queued;
        var manager = ((SodiumWorldRendererAccessor) renderer).kavora$sectionManager();
        if (manager == null) return queued;
        var recapture = (SodiumTerrainRecapture) manager;
        var camera = client.gameRenderer.mainCamera().position();
        ArrayList<SectionPos> sorted = new ArrayList<>(requested.size());
        for (long key : requested) sorted.add(SectionPos.of(key));
        sorted.sort(Comparator.comparingDouble(pos -> {
            double dx = pos.minBlockX() + 8.0 - camera.x, dy = pos.minBlockY() + 8.0 - camera.y,
                    dz = pos.minBlockZ() + 8.0 - camera.z;
            return dx * dx + dy * dy + dz * dz;
        }));
        for (SectionPos pos : sorted) {
            if (client.level.getLightEngine().lightOnInColumn(SectionPos.getZeroNode(pos.x(), pos.z()))
                    && recapture.kavora$queueRecapture(pos.x(), pos.y(), pos.z())) queued.add(pos.asLong());
        }
        return queued;
    }
    private SodiumCapture() {}
}
