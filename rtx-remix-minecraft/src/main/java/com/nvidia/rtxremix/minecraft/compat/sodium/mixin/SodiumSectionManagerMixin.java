/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.compat.sodium.mixin;
import com.nvidia.rtxremix.minecraft.compat.sodium.SodiumCapture;
import com.nvidia.rtxremix.minecraft.compat.sodium.SodiumTerrainRecapture;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.estimation.UploadResourceBudget;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Unique;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.List;
@Mixin(RenderSectionManager.class)
public abstract class SodiumSectionManagerMixin implements SodiumTerrainRecapture {
    @Unique private final LongLinkedOpenHashSet rtxremix$recaptureSections = new LongLinkedOpenHashSet();

    @Override
    public boolean rtxremix$queueRecapture(int x, int y, int z) {
        long key = SectionPos.asLong(x, y, z);
        var access = (SodiumSectionManagerAccessor) this;
        RenderSection section = access.rtxremix$sections().getConsistent(key);
        if (section == null || section.isDisposed()) return false;
        ((RenderSectionManager) (Object) this).scheduleRebuild(x, y, z, false);
        // Fresh sections already carry INITIAL_BUILD; scheduleRebuild only changes built ones.
        if (section.getPendingUpdate() == 0) return false;
        rtxremix$recaptureSections.add(key);
        return true;
    }

    @Inject(method = "submitDeferredSectionTasks", at = @At("RETURN"))
    private void rtxremix$submitRecapture(ChunkJobCollector collector, UploadResourceBudget uploadBudget,
                                        CallbackInfo ci) {
        var access = (SodiumSectionManagerAccessor) this;
        while (!rtxremix$recaptureSections.isEmpty() && collector.hasBudgetRemaining()
                && uploadBudget.isAvailable()) {
            RenderSection section = access.rtxremix$sections().getConsistent(
                    rtxremix$recaptureSections.removeFirstLong());
            if (section != null && !section.isDisposed()) {
                access.rtxremix$submitRecaptureTask(collector, section, uploadBudget);
            }
        }
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void rtxremix$discardRecapture(CallbackInfo ci) {
        rtxremix$recaptureSections.clear();
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void rtxremix$reset(CallbackInfo ci) { SodiumCapture.reset(); }
    @Inject(method = "updateWithResult", at = @At("HEAD"))
    private void rtxremix$publish(Viewport viewport, RenderSection section, ChunkBuildOutput output,
            List<RenderSection> patches, CallbackInfoReturnable<Integer> ci) {
        SodiumCapture.accepted(section, output);
    }
    @Inject(method = "onSectionRemoved", at = @At("HEAD"))
    private void rtxremix$removed(int x, int y, int z, CallbackInfo ci) {
        rtxremix$recaptureSections.remove(SectionPos.asLong(x, y, z));
        SodiumCapture.removed(x, y, z);
    }
}
