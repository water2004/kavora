/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.edtp.kavora.terrain.capture.RegionBuilderRelease;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Releases the extractor's cache ownership after regions have retained their section copies. */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorSnapshotsMixin {
    private RenderRegionCache kavora$snapshotCache;

    @WrapOperation(method = "extract", at = @At(value = "NEW",
            target = "()Lnet/minecraft/client/renderer/chunk/RenderRegionCache;"))
    private RenderRegionCache kavora$rememberCache(Operation<RenderRegionCache> original) {
        return kavora$snapshotCache = original.call();
    }

    @WrapMethod(method = "extract")
    private void kavora$releaseCache(DeltaTracker delta, Camera camera, float partialTick,
            Operation<Void> original) {
        try {
            original.call(delta, camera, partialTick);
        } finally {
            RenderRegionCache cache = kavora$snapshotCache;
            kavora$snapshotCache = null;
            if (cache != null) ((RegionBuilderRelease) cache).kavora$releaseSnapshots();
        }
    }
}
