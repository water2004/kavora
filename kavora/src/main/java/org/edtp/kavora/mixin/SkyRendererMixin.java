/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import org.edtp.kavora.KavoraMod;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Skips replaced Vulkan draws; extraction and outer world traversal remain intact. */
@Mixin(SkyRenderer.class)
public abstract class SkyRendererMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void kavora$skipReplacedDraw(CallbackInfo ci) {
        if (KavoraMod.isRemixWorldRendering()) ci.cancel();
    }
}
