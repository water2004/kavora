/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import org.edtp.kavora.KavoraMod;
import org.edtp.kavora.terrain.NeighbourReflagFilter;
import org.edtp.kavora.terrain.capture.BlockQuadEmitter;
import org.edtp.kavora.terrain.visibility.BuiltChunkStorageSectionAccess;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Invalidation runs beside vanilla's extraction-owned dirty tracker. */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {
    @Shadow @Final private LevelRenderer levelRenderer;

    @Inject(method = "setBlocksDirty(IIIIII)V", at = @At("HEAD"), cancellable = true)
    private void kavora$expandWaterOcclusionRebuildMargin(
            int minX, int minY, int minZ, int maxX, int maxY, int maxZ, CallbackInfo ci) {
        if (!KavoraMod.isRtxEnabled()) return;
        int radius = Math.max(1, BlockQuadEmitter.getWaterTopOcclusionRadius());
        LevelExtractor extractor = (LevelExtractor) (Object) this;
        for (int z = SectionPos.blockToSectionCoord(minZ - radius); z <= SectionPos.blockToSectionCoord(maxZ + radius); z++) {
            for (int x = SectionPos.blockToSectionCoord(minX - radius); x <= SectionPos.blockToSectionCoord(maxX + radius); x++) {
                for (int y = SectionPos.blockToSectionCoord(minY - 1); y <= SectionPos.blockToSectionCoord(maxY + 1); y++) {
                    extractor.setSectionDirty(x, y, z);
                }
            }
        }
        ci.cancel();
    }

    @Inject(method = "setSectionDirtyWithNeighbors(III)V", at = @At("HEAD"), cancellable = true)
    private void kavora$filterChunkLoadReflags(int cx, int sy, int cz, CallbackInfo ci) {
        if (!KavoraMod.isRtxEnabled() || !NeighbourReflagFilter.ENABLED || levelRenderer.viewArea() == null) return;
        ((BuiltChunkStorageSectionAccess) levelRenderer.viewArea()).kavora$flagLoadedSection(cx, sy, cz);
        ci.cancel();
    }
}
