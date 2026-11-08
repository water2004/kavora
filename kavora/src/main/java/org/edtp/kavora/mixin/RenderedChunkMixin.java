/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import org.edtp.kavora.terrain.capture.RenderRegionStoragePool;
import org.edtp.kavora.terrain.capture.RegionSnapshotRelease;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * A section snapshot for a render region whose block-state copy draws its storage from
 * {@link RenderRegionStoragePool} and give it back when the last region holding the snapshot
 * has been consumed. Snapshots are shared between the regions one frame's rebuild scheduling
 * builds, hence the count. The pool flag is set around the constructor by
 * {@link ClientChunkMixin}; vanilla's own snapshot code is untouched.
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionCopy")
public abstract class RenderedChunkMixin implements RegionSnapshotRelease {

    @Shadow
    @Final
    @org.spongepowered.asm.mixin.Mutable
    private PalettedContainer<BlockState> section;

    private final AtomicInteger kavora$regions = new AtomicInteger();

    @Override
    public void kavora$retain() {
        kavora$regions.incrementAndGet();
    }

    @Override
    public void kavora$release() {
        int remaining = kavora$regions.decrementAndGet();
        if (remaining < 0) throw new IllegalStateException("section snapshot released twice");
        if (remaining != 0 || section == null) return;
        if (section.data.storage() instanceof SimpleBitStorage packed) {
            RenderRegionStoragePool.release(packed.getRaw());
        }
        section = null;
    }
}
