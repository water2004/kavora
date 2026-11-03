/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.compat.sodium.mixin;
import com.nvidia.rtxremix.minecraft.compat.sodium.SodiumCapture;
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
public abstract class SodiumSectionManagerMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void rtxremix$reset(CallbackInfo ci) { SodiumCapture.reset(); }
    @Inject(method = "updateWithResult", at = @At("HEAD"))
    private void rtxremix$publish(Viewport viewport, RenderSection section, ChunkBuildOutput output,
            List<RenderSection> patches, CallbackInfoReturnable<Integer> ci) {
        SodiumCapture.accepted(section, output);
    }
    @Inject(method = "onSectionRemoved", at = @At("HEAD"))
    private void rtxremix$removed(int x, int y, int z, CallbackInfo ci) { SodiumCapture.removed(x, y, z); }
}
