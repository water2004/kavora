/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.vulkan;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import org.edtp.kavora.mixin.FrontendGpuDeviceAccessor;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageBlit;

import static org.lwjgl.vulkan.VK12.*;

/** Raw commands share Minecraft's encoder, queue ordering and retirement fence. */
public final class VulkanCommands {
    private VulkanCommands() { }

    public static VulkanDevice device() {
        RenderSystem.assertOnRenderThread();
        if (RenderSystem.getDevice() instanceof FrontendGpuDeviceAccessor frontend
                && frontend.kavora$backend() instanceof VulkanDevice device) {
            return device;
        }
        throw new IllegalStateException("Vulkan commands require Minecraft's Vulkan backend; active backend is "
                + RenderSystem.getDevice().getDeviceInfo().backendName());
    }

    public static VulkanCommandEncoder encoder() {
        return (VulkanCommandEncoder) ((FrontendCommandEncoder)
                RenderSystem.getDevice().createCommandEncoder()).backend();
    }

    public static void check(int result, String operation) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException(operation + " failed with VkResult " + result);
        }
    }

    public static void blit(VkCommandBuffer commands, long source, int sourceWidth, int sourceHeight,
                            long destination, int destinationWidth, int destinationHeight,
                            boolean flipY, MemoryStack stack) {
        VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
        region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.srcOffsets(0).set(0, 0, 0);
        region.srcOffsets(1).set(sourceWidth, sourceHeight, 1);
        region.dstOffsets(0).set(0, flipY ? destinationHeight : 0, 0);
        region.dstOffsets(1).set(destinationWidth, flipY ? 0 : destinationHeight, 1);
        vkCmdBlitImage(commands, source, VK_IMAGE_LAYOUT_GENERAL, destination, VK_IMAGE_LAYOUT_GENERAL,
                region, VK_FILTER_NEAREST);
    }
}
