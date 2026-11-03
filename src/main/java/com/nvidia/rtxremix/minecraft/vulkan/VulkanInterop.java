/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.VulkanInteropInfo;
import com.nvidia.rtxremix.minecraft.render.RemixInterop;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;


import static com.nvidia.rtxremix.minecraft.vulkan.VulkanCommands.check;
import static org.lwjgl.vulkan.VK12.*;

/** Shares runtime-owned images and timelines on Minecraft's logical device. */
public final class VulkanInterop implements RemixInterop {
    private final RemixAPI api;
    private final VulkanDevice backend;
    private final VkDevice device;
    private final VulkanInteropInfo info = new VulkanInteropInfo();
    private final Image color = new Image("Remix color", GpuFormat.RGBA8_UNORM);
    private final Image depth = new Image("Remix projected depth", GpuFormat.R32_FLOAT);
    private final Image uiOverlay = new Image("Remix native menus", GpuFormat.RGBA8_UNORM);
    private long runtimeDone;
    private long clientDone;
    private long clientDoneValue;
    private long acquiredValue;
    private boolean acquired;
    private long copiedSnapshotBytes;

    public VulkanInterop(RemixAPI api) {
        this.api = api;
        backend = VulkanCommands.device();
        device = backend.vkDevice();
        api.dxvkGetVulkanInteropInfo(info);
        validateDevice();
        if (info.semaphoreType != VK_SEMAPHORE_TYPE_TIMELINE) {
            throw new IllegalStateException("Remix interop requires timeline semaphores");
        }
        runtimeDone = info.dxvkDoneSemaphore_Handle;
        clientDone = info.clientDoneSemaphore_Handle;
        if (runtimeDone == 0 || clientDone == 0) throw new IllegalStateException("Missing shared Vulkan timeline");
    }

    private void validateDevice() {
        if (info.device != device.address() || info.queueFamilyIndex != backend.graphicsQueue().queueFamilyIndex()) {
            throw new IllegalStateException("Minecraft and Remix must share one logical Vulkan device and graphics queue family");
        }
    }

    public void refreshImages() {
        api.dxvkGetVulkanInteropInfo(info);
        boolean changed = color.changed(info.color) || depth.changed(info.depth) || uiOverlay.changed(info.uiOverlay);
        if (!changed) return;
        if (acquired) throw new IllegalStateException("Remix replaced an image while Minecraft owns it");
        // Resizing is rare; completion also protects all views and borrowed images being retired.
        RenderSystem.getDevice().createCommandEncoder().submit();
        check(vkDeviceWaitIdle(device), "vkDeviceWaitIdle(resize)");
        color.recreate(info.color);
        depth.recreate(info.depth);
        uiOverlay.recreate(info.uiOverlay);
    }

    /** Waits for the runtime release before any client reads of its images. */
    public void acquireImages() {
        if (acquired) {
            if (acquiredValue != info.dxvkDoneSemaphore_ValueToWait) {
                throw new IllegalStateException("Remix submitted before the client released its images");
            }
            return;
        }
        VulkanCommandEncoder encoder = VulkanCommands.encoder();
        encoder.waitSemaphore(runtimeDone, info.dxvkDoneSemaphore_ValueToWait, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
        copiedSnapshotBytes = 0;
        VkCommandBuffer commands = encoder.allocateAndBeginTransientCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            color.barrier(commands, true, stack);
            depth.barrier(commands, true, stack);
            uiOverlay.barrier(commands, true, stack);
        }
        check(vkEndCommandBuffer(commands), "vkEndCommandBuffer(acquire)");
        encoder.execute(commands);
        acquiredValue = info.dxvkDoneSemaphore_ValueToWait;
        acquired = true;
    }

    /** Records the release and timeline signal. The caller submits them before Remix Present. */
    public void releaseImages() {
        if (!acquired) throw new IllegalStateException("Remix image release requires client ownership");
        VulkanCommandEncoder encoder = VulkanCommands.encoder();
        VkCommandBuffer commands = encoder.allocateAndBeginTransientCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            color.barrier(commands, false, stack);
            depth.barrier(commands, false, stack);
            uiOverlay.barrier(commands, false, stack);
        }
        check(vkEndCommandBuffer(commands), "vkEndCommandBuffer(release)");
        encoder.execute(commands);
        encoder.signalSemaphore(clientDone, ++clientDoneValue, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
        acquired = false;
    }

    public boolean blitColor(GpuTexture destination) {
        requireAcquired();
        if (color.image == 0) return false;
        color.blitTo(destination);
        return true;
    }

    public GpuTextureView colorTexture() { return color.sampledView(); }
    public GpuTextureView depthTexture() { return depth.sampledView(); }
    public GpuTextureView menuTexture() { return uiOverlay.sampledView(); }
    public long snapshotTextureBytes() { return color.copyBytes() + depth.copyBytes() + uiOverlay.copyBytes(); }
    public long copiedSnapshotBytes() { return copiedSnapshotBytes; }

    private void requireAcquired() {
        if (!acquired) throw new IllegalStateException("Client image reads require runtime ownership release");
    }
    public long clientDoneValue() { return clientDoneValue; }
    public boolean isSceneReady() { return info.frameStatus.isRaytraced(); }
    public int renderWidth() { return info.depth.width; }
    public int renderHeight() { return info.depth.height; }

    public void close() {
        if (acquired) releaseImages();
        RenderSystem.getDevice().createCommandEncoder().submit();
        check(vkDeviceWaitIdle(device), "vkDeviceWaitIdle(shutdown)");
        color.destroy();
        depth.destroy();
        uiOverlay.destroy();
        runtimeDone = clientDone = 0;
    }

    private final class Image {
        private final String label;
        private final GpuFormat copyFormat;
        private long generation, image;
        private int width, height;
        private GpuTexture copy;
        private GpuTextureView view;
        private long copiedValue = -1;

        private Image(String label, GpuFormat copyFormat) {
            this.label = label;
            this.copyFormat = copyFormat;
        }

        private boolean changed(VulkanInteropInfo.Image source) {
            return image != source.image || generation != source.generation;
        }

        private void recreate(VulkanInteropInfo.Image source) {
            if (!changed(source)) return;
            destroy();
            if (source.image == 0) return;
            validate(source);
            image = source.image;
            generation = source.generation;
            width = source.width;
            height = source.height;
        }

        private void validate(VulkanInteropInfo.Image source) {
            if (source.image == 0 || source.sharingMode != VK_SHARING_MODE_EXCLUSIVE
                    || source.externalLayout != VK_IMAGE_LAYOUT_GENERAL
                    || source.ownerQueueFamily != backend.graphicsQueue().queueFamilyIndex()
                    || source.imageType != VK_IMAGE_TYPE_2D || source.depth != 1 || source.mipLevels != 1
                    || source.arrayLayers != 1 || source.samples != VK_SAMPLE_COUNT_1_BIT
                    || source.width <= 0 || source.height <= 0
                    || source.viewFormatCount < 0 || source.viewFormatCount > source.viewFormats.length
                    || (source.usage & VK_IMAGE_USAGE_TRANSFER_SRC_BIT) == 0
) {
                throw new IllegalStateException("Unsupported shared Vulkan image contract for " + label);
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkFormatProperties properties = VkFormatProperties.calloc(stack);
                vkGetPhysicalDeviceFormatProperties(device.getPhysicalDevice(), source.format, properties);
                int features = source.tiling == VK_IMAGE_TILING_OPTIMAL
                        ? properties.optimalTilingFeatures() : properties.linearTilingFeatures();
                int required = VK_FORMAT_FEATURE_BLIT_SRC_BIT;
                if ((features & required) != required) {
                    throw new IllegalStateException("Shared Vulkan format lacks blit support for " + label);
                }
            }
        }

        private GpuTextureView sampledView() {
            requireAcquired();
            if (image == 0) return null;
            if (copy == null) {
                GpuTexture texture = RenderSystem.getDevice().createTexture(label,
                        GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                        copyFormat, width, height, 1, 1);
                try {
                    view = RenderSystem.getDevice().createTextureView(texture);
                    copy = texture;
                } catch (RuntimeException failure) {
                    texture.close();
                    throw failure;
                }
            }
            if (copiedValue != acquiredValue) {
                blitTo(copy);
                copiedSnapshotBytes += copyBytes();
                copiedValue = acquiredValue;
            }
            return view;
        }

        private long copyBytes() {
            // All three sampled snapshot formats use four bytes per texel.
            return copy == null ? 0 : (long) width * height * 4;
        }

        private void blitTo(GpuTexture destination) {
            VulkanCommandEncoder encoder = VulkanCommands.encoder();
            VkCommandBuffer commands = encoder.allocateAndBeginTransientCommandBuffer();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VulkanCommandEncoder.memoryBarrier(commands, stack);
                // RenderPearl targets are bottom-up; Remix exports top-down images.
                VulkanCommands.blit(commands, image, width, height,
                        ((VulkanGpuTexture) destination).vkImage(),
                        destination.getWidth(0), destination.getHeight(0), true, stack);
                VulkanCommandEncoder.memoryBarrier(commands, stack);
            }
            check(vkEndCommandBuffer(commands), "vkEndCommandBuffer(interop blit)");
            encoder.execute(commands);
        }

        private void barrier(VkCommandBuffer commands, boolean acquire, MemoryStack stack) {
            if (image == 0) return;
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(acquire ? 0 : VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT)
                    .dstAccessMask(acquire ? VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT : 0)
                    .oldLayout(VK_IMAGE_LAYOUT_GENERAL).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(image);
            barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(1);
            vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    0, null, null, barrier);
        }

        private void destroy() {
            if (view != null) view.close();
            if (copy != null) copy.close();
            view = null;
            copy = null;
            image = generation = 0;
            width = height = 0;
            copiedValue = -1;
        }
    }
}
