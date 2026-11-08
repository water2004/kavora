/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.compat.sodium.mixin;
import org.edtp.kavora.compat.sodium.SodiumCaptureOutput;
import org.edtp.kavora.terrain.capture.BlockDataQueue;
import org.edtp.kavora.terrain.ChunkManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(ChunkBuildOutput.class)
public abstract class SodiumBuildOutputMixin implements SodiumCaptureOutput {
    private final org.edtp.kavora.compat.sodium.SodiumCapturePayload kavora$payload =
            new org.edtp.kavora.compat.sodium.SodiumCapturePayload();
    @Unique private boolean kavora$captured;
    public void kavora$setCapture(BlockDataQueue.QueuedCapture capture) {
        kavora$payload.set(capture);
        kavora$captured = capture != null;
    }
    public boolean kavora$wasCaptured() { return kavora$captured; }
    public BlockDataQueue.QueuedCapture kavora$takeCapture() { return kavora$payload.take(); }
    @Inject(method = "destroy", at = @At("HEAD"))
    private void kavora$releaseDiscarded(CallbackInfo ci) { kavora$payload.discard(); }
    @Inject(method = "calculateResultSize", at = @At("RETURN"), cancellable = true)
    private void kavora$accountCpuPayload(CallbackInfoReturnable<Long> ci) {
        ci.setReturnValue(ci.getReturnValue() + kavora$payload.estimatedBytes());
    }
}
