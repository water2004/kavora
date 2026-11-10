/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.gametest.mixin;

import org.edtp.kavora.KavoraRenderer;
import org.edtp.kavora.gametest.KavoraFrameProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = KavoraRenderer.class, remap = false)
public abstract class KavoraFrameProbeMixin {
    @Inject(method = "compositeRemixMenus", at = @At("RETURN"))
    private void kavora$readMenuFrame(CallbackInfo ci) {
        Runnable capture = KavoraFrameProbe.afterNativeMenus;
        if (capture != null) {
            KavoraFrameProbe.afterNativeMenus = null;
            capture.run();
        }
    }

    @Inject(method = "onMinecraftWorldRenderFrameStart", at = @At("HEAD"))
    private void prepareCamera(net.minecraft.client.Minecraft client, boolean tick, CallbackInfo ci) {
        if (KavoraFrameProbe.frameAction != null) KavoraFrameProbe.frameAction.accept(KavoraFrameProbe.presents);
    }
}
