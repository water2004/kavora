/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import com.mojang.renderpearl.backend.vulkan.init.VulkanPNextStruct;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayQueryFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelineFeaturesKHR;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.HashSet;
import java.util.Set;

/** Filters adapters before creating the shared ray-tracing device. */
@Mixin(value = VulkanFeatureSets.class, remap = false)
public abstract class VulkanFeatureSetsMixin {
    @ModifyReturnValue(method = "requiredFeatureSets", at = @At("RETURN"))
    private static Set<FeatureSet> rtxremix$requireInterop(Set<FeatureSet> original) {
        Set<FeatureSet> features = new HashSet<>(original);
        features.add(new FeatureSet("RTX Remix ray tracing", Set.of(
                "VK_KHR_acceleration_structure", "VK_KHR_ray_tracing_pipeline", "VK_KHR_ray_query",
                "VK_KHR_deferred_host_operations"), Set.of(
                new VulkanFeature(new VulkanPNextStruct(VkPhysicalDeviceAccelerationStructureFeaturesKHR.class), "accelerationStructure"),
                new VulkanFeature(new VulkanPNextStruct(VkPhysicalDeviceRayTracingPipelineFeaturesKHR.class), "rayTracingPipeline"),
                new VulkanFeature(new VulkanPNextStruct(VkPhysicalDeviceRayQueryFeaturesKHR.class), "rayQuery"),
                new VulkanFeature(VulkanFeatureSets.VK10_FEATURES_STRUCT, "shaderInt64"),
                new VulkanFeature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "bufferDeviceAddress"),
                new VulkanFeature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "shaderFloat16"),
                new VulkanFeature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "shaderInt8"))));
        return features;
    }
}
