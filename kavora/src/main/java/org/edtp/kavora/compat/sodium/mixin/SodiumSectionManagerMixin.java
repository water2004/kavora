/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.compat.sodium.mixin;
import org.edtp.kavora.compat.sodium.SodiumCapture;
import org.edtp.kavora.compat.sodium.SodiumTerrainRecapture;
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
    @Unique private final LongLinkedOpenHashSet kavora$recaptureSections = new LongLinkedOpenHashSet();

    @Override
    public boolean kavora$queueRecapture(int x, int y, int z) {
        long key = SectionPos.asLong(x, y, z);
        var access = (SodiumSectionManagerAccessor) this;
        RenderSection section = access.kavora$sections().getConsistent(key);
        if (section == null || section.isDisposed()) return false;
        ((RenderSectionManager) (Object) this).scheduleRebuild(x, y, z, false);
        // Fresh sections already carry INITIAL_BUILD; scheduleRebuild only changes built ones.
        if (section.getPendingUpdate() == 0) return false;
        kavora$recaptureSections.add(key);
        return true;
    }

    @Inject(method = "submitDeferredSectionTasks", at = @At("RETURN"))
    private void kavora$submitRecapture(ChunkJobCollector collector, UploadResourceBudget uploadBudget,
                                        CallbackInfo ci) {
        var access = (SodiumSectionManagerAccessor) this;
        while (!kavora$recaptureSections.isEmpty() && collector.hasBudgetRemaining()
                && uploadBudget.isAvailable()) {
            RenderSection section = access.kavora$sections().getConsistent(
                    kavora$recaptureSections.removeFirstLong());
            if (section != null && !section.isDisposed()) {
                access.kavora$submitRecaptureTask(collector, section, uploadBudget);
            }
        }
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void kavora$discardRecapture(CallbackInfo ci) {
        kavora$recaptureSections.clear();
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void kavora$reset(CallbackInfo ci) { SodiumCapture.reset(); }
    @Inject(method = "updateWithResult", at = @At("HEAD"))
    private void kavora$publish(Viewport viewport, RenderSection section, ChunkBuildOutput output,
            List<RenderSection> patches, CallbackInfoReturnable<Integer> ci) {
        SodiumCapture.accepted(section, output);
    }
    @Inject(method = "onSectionRemoved", at = @At("HEAD"))
    private void kavora$removed(int x, int y, int z, CallbackInfo ci) {
        kavora$recaptureSections.remove(SectionPos.asLong(x, y, z));
        SodiumCapture.removed(x, y, z);
    }
}
