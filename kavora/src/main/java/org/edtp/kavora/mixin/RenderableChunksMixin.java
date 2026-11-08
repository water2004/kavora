/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import org.edtp.kavora.terrain.visibility.VisibilityGraphBuffers;
import org.edtp.kavora.terrain.visibility.RenderableChunksBuffers;
import org.edtp.kavora.terrain.visibility.ChunkInfoPool;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.client.renderer.SectionOcclusionGraph.GraphStorage;
import net.minecraft.client.renderer.SectionOcclusionGraph.SectionToNodeMap;

/** Reuses the graph's per-slot node map and waiting lists while vanilla owns its octree. */
@Mixin(GraphStorage.class)
public abstract class RenderableChunksMixin implements RenderableChunksBuffers {

    private ChunkInfoPool kavora$infoPool;
    private org.edtp.kavora.terrain.visibility.ReachedChunkSet kavora$reached;

    @Redirect(
            method = "<init>",
            at = @At(value = "NEW", target = "(I)Lnet/minecraft/client/renderer/SectionOcclusionGraph$SectionToNodeMap;"))
    private SectionToNodeMap kavora$reusedInfoList(int chunkCount) {
        VisibilityGraphBuffers.Buffers buffers = VisibilityGraphBuffers.current();
        kavora$infoPool = buffers.infoPool;
        kavora$reached = buffers.chunks;
        return buffers.infos;
    }

    @Redirect(
            method = "<init>",
            at = @At(value = "NEW", target = "()Lit/unimi/dsi/fastutil/longs/Long2ObjectOpenHashMap;"))
    private Long2ObjectOpenHashMap<?> kavora$reusedUnbuiltNeighbours() {
        return VisibilityGraphBuffers.current().unbuiltNeighbours;
    }

    @Override
    public org.edtp.kavora.terrain.visibility.ReachedChunkSet kavora$reachedChunks() { return kavora$reached; }

    @Override
    public ChunkInfoPool kavora$infoPool() {
        return kavora$infoPool;
    }
}
