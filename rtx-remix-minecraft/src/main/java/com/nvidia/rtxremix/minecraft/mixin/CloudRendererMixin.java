/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import net.minecraft.client.renderer.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Skips replaced Vulkan draws; extraction and outer world traversal remain intact. */
@Mixin(CloudRenderer.class)
public abstract class CloudRendererMixin {
    @Inject(method = {"render", "renderOit"}, at = @At("HEAD"), cancellable = true)
    private void rtxremix$skipReplacedDraw(CallbackInfo ci) {
        if (RtxRemixMod.isRemixWorldRendering() && RtxRemixMod.getInstance().getRenderer().getCloudRenderer().shouldSuppressVanillaClouds()) ci.cancel();
    }
}
