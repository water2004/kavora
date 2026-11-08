/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import org.edtp.kavora.terrain.capture.RegionSnapshotRelease;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;

import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult;

/**
 * Releases the task's region after its only worker reader finishes. Cancellation claims
 * a task that has not begun; an executing task retains ownership through its return.
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask")
public abstract class RebuildTaskMixin {

    @Shadow @Final
    private RenderSectionRegion region;

    private RenderSectionRegion kavora$consumed;
    private final java.util.concurrent.atomic.AtomicBoolean kavora$claimed = new java.util.concurrent.atomic.AtomicBoolean();

    @WrapMethod(method = "doTask")
    private SectionTaskResult kavora$withOwnedRegion(SectionBufferBuilderPack buffers, Operation<SectionTaskResult> original) {
        if (kavora$claimed.compareAndSet(false, true)) kavora$consumed = region;
        try {
            return original.call(buffers);
        } finally {
            RenderSectionRegion consumed = kavora$consumed;
            kavora$consumed = null;
            if (consumed != null) ((RegionSnapshotRelease) consumed).kavora$release();
        }
    }
    @Inject(method = "cancel", at = @At("RETURN"))
    private void kavora$releaseCancelledRegion(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (kavora$claimed.compareAndSet(false, true)) {
            ((RegionSnapshotRelease) region).kavora$release();
        }
    }

}
