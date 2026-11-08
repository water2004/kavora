/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Options;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Selects Vulkan before Minecraft creates its window and graphics device. */
@Mixin(Options.class)
public abstract class VulkanOptionsMixin {
    @Shadow private PreferredGraphicsApi preferredGraphicsBackendFromStartup;

    @ModifyExpressionValue(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/PreferredGraphicsApi;values()[Lnet/minecraft/client/PreferredGraphicsApi;"))
    private PreferredGraphicsApi[] rtxremix$availableBackends(PreferredGraphicsApi[] original) {
        return new PreferredGraphicsApi[]{PreferredGraphicsApi.VULKAN};
    }

    @ModifyExpressionValue(method = "<init>", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/PreferredGraphicsApi;DEFAULT:Lnet/minecraft/client/PreferredGraphicsApi;"))
    private PreferredGraphicsApi rtxremix$defaultBackend(PreferredGraphicsApi original) {
        return PreferredGraphicsApi.VULKAN;
    }

    @Inject(method = "load", at = @At("RETURN"))
    private void rtxremix$selectVulkan(CallbackInfo ci) {
        ((Options) (Object) this).preferredGraphicsBackend().set(PreferredGraphicsApi.VULKAN);
        preferredGraphicsBackendFromStartup = PreferredGraphicsApi.VULKAN;
    }
}
