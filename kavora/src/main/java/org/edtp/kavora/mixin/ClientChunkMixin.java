/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import org.edtp.kavora.terrain.capture.RenderRegionStoragePool;
import net.minecraft.client.renderer.chunk.SectionCopy;
import net.minecraft.world.level.chunk.LevelChunk;
import org.edtp.kavora.terrain.capture.RegionSnapshotRelease;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Marks the render thread as taking a region snapshot while a {@code RenderedChunk} is
 * constructed, so its block-state copies draw pooled storage, and takes the builder's
 * reference on the new snapshot; see {@link RenderedChunkMixin} and
 * {@link ChunkRendererRegionBuilderMixin}.
 */
@Mixin(net.minecraft.client.renderer.chunk.RenderRegionCache.class)
public abstract class ClientChunkMixin {

    @WrapOperation(
            method = "lambda$getSectionDataCopy$0",
            at = @At(value = "NEW", target = "(Lnet/minecraft/world/level/chunk/LevelChunk;I)Lnet/minecraft/client/renderer/chunk/SectionCopy;")
    )
    private static SectionCopy kavora$snapshotWithPooledStorage(LevelChunk chunk, int sectionY, Operation<SectionCopy> original) {
        RenderRegionStoragePool.beginCopying();
        SectionCopy snapshot;
        try {
            snapshot = original.call(chunk, sectionY);
        } finally {
            RenderRegionStoragePool.endCopying();
        }
        ((RegionSnapshotRelease) snapshot).kavora$retain();
        return snapshot;
    }
}
