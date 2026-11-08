/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import org.edtp.kavora.NativeRuntime;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import org.edtp.kavora.vulkan.NativeVulkanSurface;
import java.util.function.BooleanSupplier;

@Mixin(value = VulkanDevice.class, remap = false)
public abstract class VulkanDeviceMixin {
    @Inject(method = "createSurface", at = @At("HEAD"), cancellable = true)
    private void kavora$createNativeSurface(long window, BooleanSupplier windowState,
                                              CallbackInfoReturnable<GpuSurfaceBackend> callback) {
        callback.setReturnValue(new NativeVulkanSurface((VulkanDevice) (Object) this, window));
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void kavora$releaseRemixResources(CallbackInfo callback) {
        VulkanDevice backend = (VulkanDevice) (Object) this;
        if (NativeRuntime.isStarted()) {
            ((com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder) backend.createCommandEncoder()).submit();
        }
        NativeRuntime.beforeDeviceClose(backend.vkDevice().address());
    }

    @WrapOperation(method = "close", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK12;vkDestroyDevice(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkAllocationCallbacks;)V"))
    private void kavora$destroyHostDevice(VkDevice device, VkAllocationCallbacks allocator, Operation<Void> original) {
        NativeRuntime.beforeDeviceDestroy(device.address());
        original.call(device, allocator);
    }
}
