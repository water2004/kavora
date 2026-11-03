/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import com.nvidia.rtxremix.minecraft.NativeRuntime;
import org.lwjgl.PointerBuffer;
import org.lwjgl.vulkan.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = VulkanBackend.class, remap = false)
public abstract class VulkanBackendMixin {
    @WrapOperation(method = "createDevice(Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;)Lorg/lwjgl/vulkan/VkDevice;",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VK12;vkCreateDevice(Lorg/lwjgl/vulkan/VkPhysicalDevice;Lorg/lwjgl/vulkan/VkDeviceCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Lorg/lwjgl/PointerBuffer;)I"))
    private static int rtxremix$createSharedDevice(VkPhysicalDevice physical, VkDeviceCreateInfo info,
            VkAllocationCallbacks allocator, PointerBuffer output, Operation<Integer> original,
            @Local(argsOnly = true) VulkanPhysicalDevice selected) {
        return NativeRuntime.createVulkanDevice(physical, info, allocator, output, selected.graphicsQueueFamilyAndIndex().leftInt());
    }

    @Inject(method = "createDevice(Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;)Lorg/lwjgl/vulkan/VkDevice;",
            at = @At("RETURN"))
    private static void rtxremix$adoptSharedDevice(CallbackInfoReturnable<VkDevice> callback) {
        NativeRuntime.adoptVulkanDevice(callback.getReturnValue());
    }

    @WrapOperation(method = "createDevice(Lcom/mojang/renderpearl/api/device/GpuDebugOptions;)Lcom/mojang/renderpearl/api/device/GpuDevice;",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VK12;vkDestroyDevice(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkAllocationCallbacks;)V"))
    private void rtxremix$failedDevice(VkDevice device, VkAllocationCallbacks allocator, Operation<Void> original) {
        NativeRuntime.beforeDeviceDestroy(device.address());
        original.call(device, allocator);
    }
}
