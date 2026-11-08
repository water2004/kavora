/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.platform.Window;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.vulkan.NativeVulkanSurface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records the SDL window used by Minecraft's graphics surface. */
@Mixin(Window.class)
public abstract class WindowMixin {
    @Shadow @Final
    private long handle;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void rtxremix$onWindowCreated(CallbackInfo ci) {
        RtxRemixMod.LOGGER.info("Window created with SDL handle: {}", handle);
    }

    @Inject(method = "setMode", at = @At("HEAD"))
    private void rtxremix$retirePresentations(CallbackInfo callback) {
        NativeVulkanSurface.beforeWindowModeChange();
    }
}
