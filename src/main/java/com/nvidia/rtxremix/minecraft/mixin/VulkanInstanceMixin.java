/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.vulkan.VulkanInstance;
import com.nvidia.rtxremix.minecraft.NativeRuntime;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(value = VulkanInstance.class, remap = false)
public abstract class VulkanInstanceMixin {
    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VkApplicationInfo;apiVersion(I)Lorg/lwjgl/vulkan/VkApplicationInfo;"))
    private int rtxremix$instanceVersion(int version) {
        return org.lwjgl.vulkan.VK14.VK_API_VERSION_1_4;
    }

    @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK12;vkCreateInstance(Lorg/lwjgl/vulkan/VkInstanceCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Lorg/lwjgl/PointerBuffer;)I"))
    private int rtxremix$prepareRuntime(VkInstanceCreateInfo info, VkAllocationCallbacks allocator,
                                      PointerBuffer output, Operation<Integer> original) {
        NativeRuntime.prepareVulkan();
        // Keep the extension names on the constructor's stack until its LWJGL dispatch table has read them.
        MemoryStack stack = MemoryStack.stackGet();
        String surfaceCapabilities = org.lwjgl.vulkan.KHRGetSurfaceCapabilities2.VK_KHR_GET_SURFACE_CAPABILITIES_2_EXTENSION_NAME;
        PointerBuffer requested = info.ppEnabledExtensionNames();
        boolean present = false;
        for (int i = 0; i < info.enabledExtensionCount(); i++) present |= surfaceCapabilities.equals(requested.getStringUTF8(i));
        if (!present) {
            PointerBuffer extensions = stack.mallocPointer(info.enabledExtensionCount() + 1);
            for (int i = 0; i < info.enabledExtensionCount(); i++) extensions.put(requested.get(i));
            extensions.put(stack.UTF8(surfaceCapabilities)).flip();
            info.ppEnabledExtensionNames(extensions);
        }
        int result = original.call(info, allocator, output);
        if (result == org.lwjgl.vulkan.VK10.VK_SUCCESS) NativeRuntime.recordInstance(output.get(0), info);
        return result;
    }

    @WrapOperation(method = "close", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK12;vkDestroyInstance(Lorg/lwjgl/vulkan/VkInstance;Lorg/lwjgl/vulkan/VkAllocationCallbacks;)V"))
    private void rtxremix$closeRuntime(VkInstance instance, VkAllocationCallbacks allocator, Operation<Void> original) {
        NativeRuntime.beforeInstanceDestroy(instance.address());
        original.call(instance, allocator);
    }
}
