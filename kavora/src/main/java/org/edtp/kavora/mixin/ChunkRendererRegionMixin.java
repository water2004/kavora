/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import org.edtp.kavora.terrain.capture.RegionSnapshotRelease;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.renderer.chunk.SectionCopy;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * A render region holds one reference on each of its 27 section snapshots from construction
 * until {@link #kavora$release}, which the rebuild task that consumed it calls exactly once;
 * see {@link RebuildTaskMixin}.
 */
@Mixin(RenderSectionRegion.class)
public abstract class ChunkRendererRegionMixin implements RegionSnapshotRelease, org.edtp.kavora.terrain.capture.RegionNeighbourhoodSnapshot {

    @Shadow
    @Final
    private SectionCopy[] sections;

    private int kavora$neighbourMask;

    @Override public int kavora$neighbourMask() { return kavora$neighbourMask; }

    private final AtomicBoolean kavora$released = new AtomicBoolean();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void kavora$retainSnapshots(ClientLevel world, int sectionX, int sectionY, int sectionZ, SectionCopy[] sectionCopies, CallbackInfo ci) {
        for (SectionCopy snapshot : sections) {
            ((RegionSnapshotRelease) snapshot).kavora$retain();
        }
        for (int k = 0; k < org.edtp.kavora.terrain.NeighbourReflagFilter.NEIGHBOUR_COUNT; k++) {
            if (world.hasChunk(sectionX + 1 + org.edtp.kavora.terrain.NeighbourReflagFilter.DX[k],
                    sectionZ + 1 + org.edtp.kavora.terrain.NeighbourReflagFilter.DZ[k])) {
                kavora$neighbourMask |= org.edtp.kavora.terrain.NeighbourReflagFilter.bit(k);
            }
        }
    }

    @Override
    public void kavora$retain() {
        throw new UnsupportedOperationException("regions are held by exactly one rebuild task");
    }

    @Override
    public void kavora$release() {
        if (!kavora$released.compareAndSet(false, true)) {
            return;
        }
        for (SectionCopy snapshot : sections) {
            ((RegionSnapshotRelease) snapshot).kavora$release();
        }
    }
}
