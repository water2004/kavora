/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.vulkan;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import org.edtp.kavora.sdk.PresentInfo;
import org.edtp.kavora.sdk.VulkanClientFrameInfo;
import org.edtp.kavora.NativeRuntime;
import org.edtp.kavora.KavoraMod;
import java.util.Collection;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK12.*;
import static org.edtp.kavora.vulkan.VulkanCommands.check;

/** The sole window surface delegates Vulkan WSI and presentation pacing to the runtime. */
public final class NativeVulkanSurface implements GpuSurfaceBackend {
    private static NativeVulkanSurface active;
    private final VulkanDevice backend;
    private final VkDevice device;
    private final VulkanClientFrameInfo frame = new VulkanClientFrameInfo();
    private final PresentInfo present = new PresentInfo();
    private final long readySemaphore;
    private GpuSurface.Configuration configuration;
    private VulkanGpuTexture source;
    private boolean presentPending;
    private int memorySampleFrame;
    private long deviceLocalUsageBytes, deviceLocalBudgetBytes;
    private boolean closed;

    public NativeVulkanSurface(VulkanDevice backend, long window) {
        if (active != null) throw new IllegalStateException("Only one native presentation surface is supported");
        NativeRuntime.startup(window);
        this.backend = backend;
        device = backend.vkDevice();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var timeline = VkSemaphoreTypeCreateInfo.calloc(stack).sType$Default().semaphoreType(VK_SEMAPHORE_TYPE_TIMELINE);
            var info = VkSemaphoreCreateInfo.calloc(stack).sType$Default().pNext(timeline.address());
            var result = stack.mallocLong(1);
            check(vkCreateSemaphore(device, info, null, result), "vkCreateSemaphore(client presentation)");
            readySemaphore = result.get(0);
        }
        frame.device = device.address();
        frame.sourceReadySemaphore = readySemaphore;
        frame.bottomUp = true;
        present.hwndOverride = NativeRuntime.windowHandle();
        present.vulkanFrame = frame;
        active = this;
    }

    @Override public void configure(GpuSurface.Configuration configuration) { this.configuration = configuration; }
    @Override public boolean isSuboptimal() { return false; }
    @Override public Collection<GpuSurface.PresentMode> supportedPresentModes() {
        return List.of(GpuSurface.PresentMode.IMMEDIATE, GpuSurface.PresentMode.FIFO);
    }

    @Override public void acquireNextTexture() {
        if (frame.sourceConsumedSemaphore != 0) {
            encoder().waitSemaphore(frame.sourceConsumedSemaphore, frame.sourceConsumedValue, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
        }
    }

    @Override public void blitFromTexture(CommandEncoderBackend encoder, GpuTextureView texture) {
        VulkanGpuTexture next = (VulkanGpuTexture) texture.texture();
        if (source != next) {
            releaseSource();
            source = next;
            frame.imageGeneration++;
        }
        frame.image = source.vkImage();
        frame.width = source.getWidth(0);
        frame.height = source.getHeight(0);
        frame.format = com.mojang.renderpearl.backend.vulkan.VulkanConst.toVk(source.getFormat());
        frame.vsync = configuration.presentMode() == GpuSurface.PresentMode.FIFO;
        var client = net.minecraft.client.Minecraft.getInstance();
        frame.allowFrameGeneration = client.level != null && !client.isPaused()
                && client.gui.screen() == null && client.gui.overlay() == null && KavoraMod.isRtxEnabled();
        ((VulkanCommandEncoder) encoder).signalSemaphore(readySemaphore, ++frame.sourceReadyValue, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
        presentPending = true;
        if ((++memorySampleFrame & 63) == 0) sampleMemoryBudget();
    }

    @Override public void present() {
        long previousSemaphore = frame.sourceConsumedSemaphore, previousValue = frame.sourceConsumedValue;
        var renderer = KavoraMod.renderer;
        if (renderer != null && renderer.isInitialized()) renderer.presentFrame(frame);
        else NativeRuntime.api().present(present);
        if (frame.sourceConsumedSemaphore == 0 || frame.sourceConsumedValue == 0
                || (frame.sourceConsumedSemaphore == previousSemaphore && frame.sourceConsumedValue <= previousValue)) {
            throw new IllegalStateException("Runtime did not acknowledge the client presentation image");
        }
        presentPending = false;
    }

    public static long presentationEpoch() { return active == null ? 0 : active.frame.presentationEpoch; }
    public static long renderedFrames() { return active == null ? 0 : active.frame.renderedFrames; }
    public static long generatedFrames() { return active == null ? 0 : active.frame.generatedFrames; }
    public static long deviceLocalUsageBytes() { return active == null ? 0 : active.deviceLocalUsageBytes; }
    public static long deviceLocalBudgetBytes() { return active == null ? 0 : active.deviceLocalBudgetBytes; }

    /** Retires queued presentation before SDL changes the window's display mode. */
    public static void beforeWindowModeChange() {
        NativeVulkanSurface surface = active;
        if (surface == null) return;
        com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder().submit();
        if (surface.presentPending) surface.present();
        surface.releaseSource();
    }

    private void sampleMemoryBudget() {
        if (!device.getCapabilities().VK_EXT_memory_budget) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var budget = VkPhysicalDeviceMemoryBudgetPropertiesEXT.calloc(stack).sType$Default();
            var properties = VkPhysicalDeviceMemoryProperties2.calloc(stack).sType$Default().pNext(budget.address());
            vkGetPhysicalDeviceMemoryProperties2(device.getPhysicalDevice(), properties);
            var memory = properties.memoryProperties();
            long used = 0, available = 0;
            for (int i = 0; i < memory.memoryHeapCount(); i++) {
                if ((memory.memoryHeaps(i).flags() & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) {
                    used += budget.heapUsage(i);
                    available += budget.heapBudget(i);
                }
            }
            deviceLocalUsageBytes = used;
            deviceLocalBudgetBytes = available;
        }
    }

    /** Protects a borrowed image even when Minecraft destroys it before closing its surface. */
    public static void beforeImageDestroy(long image) {
        NativeVulkanSurface surface = active;
        if (surface != null && surface.frame.image == image) surface.releaseSource();
    }

    public static boolean hasPendingPresent() {
        return active != null && active.presentPending;
    }

    private VulkanCommandEncoder encoder() { return (VulkanCommandEncoder) backend.createCommandEncoder(); }

    private void waitForConsumer() {
        if (!NativeRuntime.isStarted() || frame.sourceConsumedSemaphore == 0) return;
        waitFor(frame.sourceConsumedSemaphore, frame.sourceConsumedValue);
    }

    private void releaseSource() {
        if (source == null) return;
        waitForConsumer();
        frame.image = 0;
        if (NativeRuntime.isStarted()) NativeRuntime.api().present(present);
        source = null;
    }

    private void waitFor(long semaphore, long value) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var wait = VkSemaphoreWaitInfo.calloc(stack).sType$Default()
                    .semaphoreCount(1).pSemaphores(stack.longs(semaphore)).pValues(stack.longs(value));
            check(vkWaitSemaphores(device, wait, 5_000_000_000L), "vkWaitSemaphores(client presentation)");
        }
    }

    @Override public void close() {
        if (closed) return;
        releaseSource();
        if (frame.sourceReadyValue != 0) waitFor(readySemaphore, frame.sourceReadyValue);
        vkDestroySemaphore(device, readySemaphore, null);
        closed = true;
        active = null;
        source = null;
    }
}
