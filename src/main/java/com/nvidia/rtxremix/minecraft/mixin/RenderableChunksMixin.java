/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.visibility.VisibilityGraphBuffers;
import com.nvidia.rtxremix.minecraft.terrain.visibility.RenderableChunksBuffers;
import com.nvidia.rtxremix.minecraft.terrain.visibility.ChunkInfoPool;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.client.renderer.SectionOcclusionGraph.GraphStorage;
import net.minecraft.client.renderer.SectionOcclusionGraph.SectionToNodeMap;

/** Reuses the graph's per-slot node map and waiting lists while vanilla owns its octree. */
@Mixin(GraphStorage.class)
public abstract class RenderableChunksMixin implements RenderableChunksBuffers {

    private ChunkInfoPool rtxremix$infoPool;
    private com.nvidia.rtxremix.minecraft.terrain.visibility.ReachedChunkSet rtxremix$reached;

    @Redirect(
            method = "<init>",
            at = @At(value = "NEW", target = "(I)Lnet/minecraft/client/renderer/SectionOcclusionGraph$SectionToNodeMap;"))
    private SectionToNodeMap rtxremix$reusedInfoList(int chunkCount) {
        VisibilityGraphBuffers.Buffers buffers = VisibilityGraphBuffers.current();
        rtxremix$infoPool = buffers.infoPool;
        rtxremix$reached = buffers.chunks;
        return buffers.infos;
    }

    @Redirect(
            method = "<init>",
            at = @At(value = "NEW", target = "()Lit/unimi/dsi/fastutil/longs/Long2ObjectOpenHashMap;"))
    private Long2ObjectOpenHashMap<?> rtxremix$reusedUnbuiltNeighbours() {
        return VisibilityGraphBuffers.current().unbuiltNeighbours;
    }

    @Override
    public com.nvidia.rtxremix.minecraft.terrain.visibility.ReachedChunkSet rtxremix$reachedChunks() { return rtxremix$reached; }

    @Override
    public ChunkInfoPool rtxremix$infoPool() {
        return rtxremix$infoPool;
    }
}
