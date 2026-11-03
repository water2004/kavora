/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.compat.sodium.mixin;
import com.nvidia.rtxremix.minecraft.compat.sodium.SodiumCaptureOutput;
import com.nvidia.rtxremix.minecraft.terrain.capture.BlockDataQueue;
import com.nvidia.rtxremix.minecraft.terrain.ChunkManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(ChunkBuildOutput.class)
public abstract class SodiumBuildOutputMixin implements SodiumCaptureOutput {
    private final com.nvidia.rtxremix.minecraft.compat.sodium.SodiumCapturePayload rtxremix$payload =
            new com.nvidia.rtxremix.minecraft.compat.sodium.SodiumCapturePayload();
    public void rtxremix$setCapture(BlockDataQueue.QueuedCapture capture) { rtxremix$payload.set(capture); }
    public BlockDataQueue.QueuedCapture rtxremix$takeCapture() { return rtxremix$payload.take(); }
    @Inject(method = "destroy", at = @At("HEAD"))
    private void rtxremix$releaseDiscarded(CallbackInfo ci) { rtxremix$payload.discard(); }
    @Inject(method = "calculateResultSize", at = @At("RETURN"), cancellable = true)
    private void rtxremix$accountCpuPayload(CallbackInfoReturnable<Long> ci) {
        ci.setReturnValue(ci.getReturnValue() + rtxremix$payload.estimatedBytes());
    }
}
