/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import org.edtp.kavora.KavoraMod;
import org.edtp.kavora.KavoraRenderer;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Completes the shared-image frame before Minecraft presents its selected graphics surface. */
@Mixin(Minecraft.class)
public abstract class RenderSystemMixin {
    @Inject(method = "renderFrame", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/device/GpuSurface;blitFromTexture(Lcom/mojang/renderpearl/api/commands/CommandEncoder;Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V"))
    private void kavora$compositeNativeMenus(boolean tick, CallbackInfo ci) {
        KavoraRenderer renderer = KavoraMod.renderer;
        if (renderer != null && renderer.isInitialized()) {
            renderer.compositeRemixMenus();
        }
    }

    @WrapOperation(method = "renderFrame", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;submit()V"))
    private void kavora$completeRemixFrame(CommandEncoder encoder, Operation<Void> original) {
        KavoraRenderer renderer = KavoraMod.renderer;
        if (renderer != null && renderer.isInitialized()) {
            renderer.preparePresentFrame();
            original.call(encoder);
            org.edtp.kavora.vulkan.NativeVulkanSurface.completeUnpresentedFrame();
        } else {
            original.call(encoder);
        }
    }
}
