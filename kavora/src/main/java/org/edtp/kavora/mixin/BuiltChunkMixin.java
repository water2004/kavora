/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import org.edtp.kavora.terrain.BuiltChunkNeighbourhoodAccess;
import org.edtp.kavora.terrain.ChunkBuilderBlockEntityAccess;
import org.edtp.kavora.terrain.NeighbourReflagFilter;
import org.edtp.kavora.terrain.SectionLeftRenderSetQueue;
import org.edtp.kavora.terrain.visibility.ChunkSlotMoveLog;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks into vanilla's BuiltChunk lifecycle.
 *
 * <ul>
 *   <li><b>setOrigin:</b> Resets per-slot block-entity and snapshot state when
 *       vanilla reuses a BuiltChunk for a new section, and logs the move so the
 *       frustum index can re-bucket exactly the slots that moved.
 *   <li><b>createRebuildTask:</b> Records which neighbour columns the region
 *       snapshot contained, so a later chunk-load re-flag from a column the
 *       capture already read can be dropped.
 *   <li><b>setSectionMesh:</b> Tracks whether the section contains block
 *       entities and flags empty render data as a removal candidate. The
 *       render thread validates the live world section before removing
 *       Remix terrain because vanilla can publish temporary empty data.
 * </ul>
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class BuiltChunkMixin implements ChunkBuilderBlockEntityAccess, BuiltChunkNeighbourhoodAccess {

    @Shadow
    @Final
    public int index;

    @Shadow
    @Final
    private BlockPos.MutableBlockPos renderOrigin;

    @Shadow public abstract net.minecraft.client.renderer.chunk.SectionMesh getSectionMesh();

    private boolean kavora$hasBlockEntities;

    /** Bit k set iff neighbour column (DX[k], DZ[k]) was loaded when the last region snapshot was taken. */
    private int kavora$snapshotMask;

    @Override
    public boolean kavora$hasBlockEntities() {
        return kavora$hasBlockEntities;
    }

    @Override
    public int kavora$snapshotMask() {
        return kavora$snapshotMask;
    }

    @Override
    public void kavora$clearSnapshotBit(int bit) {
        kavora$snapshotMask &= ~bit;
    }

    @Inject(method = "setSectionNode", at = @At("HEAD"))
    private void kavora$onSetOrigin(long sectionNode, CallbackInfo ci) {
        kavora$hasBlockEntities = false;
        kavora$snapshotMask = 0;
    }

    @Inject(method = "setSectionNode", at = @At("RETURN"))
    private void kavora$afterSetOrigin(long sectionNode, CallbackInfo ci) {
        ChunkSlotMoveLog.RENDER_THREAD.record(index);
    }

    /** Uses the extraction-time presence mask, before later chunk loads can change the world. */
    @Inject(
            method = "createCompileTask",
            at = @At("RETURN"))
    private void kavora$recordSnapshotNeighbourhood(
            net.minecraft.client.renderer.chunk.RenderSectionRegion region, CallbackInfoReturnable<?> cir) {
        // RETURN precedes the synchronous task.run in BuiltChunk.rebuild, so a first build still reads EMPTY here.
        boolean firstBuild = getSectionMesh() == net.minecraft.client.renderer.chunk.CompiledSectionMesh.UNCOMPILED;
        int mask = ((org.edtp.kavora.terrain.capture.RegionNeighbourhoodSnapshot) region)
                .kavora$neighbourMask();
        kavora$snapshotMask = mask;
        if (NeighbourReflagFilter.isIncompleteFirstBuild(firstBuild, kavora$distanceToCamera(), mask)) {
            NeighbourReflagFilter.recordIncompleteFirstBuild();
        }
    }

    @Inject(method = "setSectionMesh", at = @At("HEAD"))
    private void kavora$onChunkDataSet(net.minecraft.client.renderer.chunk.SectionMesh chunkData, CallbackInfoReturnable<?> cir) {
        kavora$hasBlockEntities = !chunkData.getRenderableBlockEntities().isEmpty();

        if (chunkData.hasRenderableLayers()) {
            return;
        }
        int x = renderOrigin.getX();
        int y = renderOrigin.getY();
        int z = renderOrigin.getZ();
        if (x == -1 && y == -1 && z == -1) {
            return;
        }
        SectionLeftRenderSetQueue.enqueueEmptyCandidate(x >> 4, y >> 4, z >> 4);
    }

    @Inject(method = "reset()V", at = @At("HEAD"))
    private void kavora$onClear(CallbackInfo ci) {
        kavora$hasBlockEntities = false;
        kavora$snapshotMask = 0;
    }
    private double kavora$distanceToCamera() {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player == null) return Double.POSITIVE_INFINITY;
        double dx = renderOrigin.getX() + 8.0 - player.getX();
        double dy = renderOrigin.getY() + 8.0 - player.getY();
        double dz = renderOrigin.getZ() + 8.0 - player.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

}
