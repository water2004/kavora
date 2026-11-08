/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Fails at device initialization instead of falling back to an unsupported renderer. */
@Mixin(PreferredGraphicsApi.class)
public abstract class VulkanBackendSelectionMixin {
    @Inject(method = "getBackendsToTry", at = @At("HEAD"), cancellable = true)
    private void kavora$vulkanOnly(CallbackInfoReturnable<GpuBackend[]> cir) {
        cir.setReturnValue(new GpuBackend[]{new VulkanBackend()});
    }
}
