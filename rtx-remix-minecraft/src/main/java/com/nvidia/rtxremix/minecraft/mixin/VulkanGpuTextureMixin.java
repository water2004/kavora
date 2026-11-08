/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.nvidia.rtxremix.minecraft.vulkan.NativeVulkanSurface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = VulkanGpuTexture.class, remap = false)
public abstract class VulkanGpuTextureMixin {
    @Inject(method = "destroy", at = @At("HEAD"))
    private void rtxremix$finishExternalReads(CallbackInfo callback) {
        NativeVulkanSurface.beforeImageDestroy(((VulkanGpuTexture) (Object) this).vkImage());
    }
}
