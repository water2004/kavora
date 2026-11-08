/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.gametest.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageBlit;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Keeps Fabric's virtual-window presentation inside the currently acquired swapchain image. */
@Mixin(targets = "com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface", remap = false)
public abstract class KavoraTestSurfaceBoundsMixin {
    @Shadow private int swapchainWidth;
    @Shadow private int swapchainHeight;
    private boolean kavora$reportedResize;

    @WrapOperation(method = "blitFromTexture", at = @At(value = "INVOKE", target =
            "Lorg/lwjgl/vulkan/VK12;vkCmdBlitImage(Lorg/lwjgl/vulkan/VkCommandBuffer;JIJILorg/lwjgl/vulkan/VkImageBlit$Buffer;I)V"))
    private void kavora$boundTestPresentation(VkCommandBuffer commands, long source, int sourceLayout,
            long destination, int destinationLayout, VkImageBlit.Buffer regions, int filter, Operation<Void> original) {
        for (int regionIndex = regions.position(); regionIndex < regions.limit(); regionIndex++) {
            var region = regions.get(regionIndex);
            for (int corner = 0; corner < 2; corner++) {
                var offset = region.dstOffsets(corner);
                int x = Math.clamp(offset.x(), 0, swapchainWidth);
                int y = Math.clamp(offset.y(), 0, swapchainHeight);
                if (!kavora$reportedResize && (x != offset.x() || y != offset.y())) {
                    LoggerFactory.getLogger(KavoraTestSurfaceBoundsMixin.class).warn(
                            "Fabric test-window blit exceeds acquired Vulkan swapchain: ({},{}) outside {}x{}",
                            offset.x(), offset.y(), swapchainWidth, swapchainHeight);
                    kavora$reportedResize = true;
                }
                offset.x(x).y(y);
            }
        }
        original.call(commands, source, sourceLayout, destination, destinationLayout, regions, filter);
    }
}
