/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.nvidia.rtxremix.minecraft.vulkan.VulkanCommands;
import java.nio.ByteBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK10.*;

/** Owns the compute pipeline and persistently mapped, fence-protected sky readbacks. */
final class AtmosphericSkyVulkanCompute implements AtmosphericSkyCompute {
    private static final int RING_SIZE = 3;
    private final VulkanDevice backend = VulkanCommands.device();
    private final VkDevice device = backend.vkDevice();
    private final int width;
    private final int height;
    private final int bytes;
    private final Image[] images = new Image[4];
    private final Slot[] slots = new Slot[RING_SIZE];
    private long sampler;
    private long descriptorLayout;
    private long descriptorPool;
    private long pipelineLayout;
    private long pipeline;
    private long commandPool;
    private int writeIndex;
    private int readIndex;
    private int pending;

    AtmosphericSkyVulkanCompute(int width, int height, String source) {
        this.width = width;
        this.height = height;
        bytes = width * (height + 1) * 8;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var out = stack.mallocLong(1);
            check(vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .queueFamilyIndex(backend.graphicsQueue().queueFamilyIndex())
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT), null, out), "create sky command pool");
            commandPool = out.get(0);
            images[0] = image(AtmosphericSkyGenerator.LUT_WIDTH, AtmosphericSkyGenerator.LUT_HEIGHT, VK_FORMAT_R32G32B32A32_SFLOAT);
            images[1] = image(width, height / 2, VK_FORMAT_R32G32B32A32_SFLOAT);
            images[2] = image(width, height / 2, VK_FORMAT_R32G32B32A32_SFLOAT);
            images[3] = image(width, height + 1, VK_FORMAT_R16G16B16A16_SFLOAT);
            check(vkCreateSampler(device, VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK_FILTER_LINEAR).minFilter(VK_FILTER_LINEAR)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE), null, out), "create sky LUT sampler");
            sampler = out.get(0);
            var bindings = VkDescriptorSetLayoutBinding.calloc(6, stack);
            for (int i = 0; i < 6; i++) {
                bindings.get(i).binding(i).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
                        .descriptorType(i < 4 ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
                                : i == 4 ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER : VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
            }
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
                    .pBindings(bindings), null, out), "create sky descriptor layout");
            descriptorLayout = out.get(0);
            var sizes = VkDescriptorPoolSize.calloc(3, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(4 * RING_SIZE);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(RING_SIZE);
            sizes.get(2).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(RING_SIZE);
            check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(RING_SIZE).pPoolSizes(sizes), null, out), "create sky descriptor pool");
            descriptorPool = out.get(0);
            var push = VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(4);
            check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(descriptorLayout)).pPushConstantRanges(push), null, out), "create sky pipeline layout");
            pipelineLayout = out.get(0);
            createPipeline(source);
            for (int i = 0; i < RING_SIZE; i++) slots[i] = createSlot();
            var first = slots[0];
            begin(first);
            transitionImages(first.command, stack);
            bind(first.command, first);
            dispatch(first.command, 0, AtmosphericSkyGenerator.LUT_WIDTH, AtmosphericSkyGenerator.LUT_HEIGHT);
            barrier(first.command, stack, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);
            submit(first);
            check(vkWaitForFences(device, first.fence, true, Long.MAX_VALUE), "wait for sky LUT");
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    public boolean enqueue(ByteBuffer parameters) {
        if (pending == RING_SIZE) return false;
        Slot slot = slots[writeIndex];
        if (vkGetFenceStatus(device, slot.fence) != VK_SUCCESS) return false;
        slot.parameters.mapped.clear().put(parameters.duplicate()).clear();
        Vma.vmaFlushAllocation(backend.vma(), slot.parameters.allocation, 0, 80);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            begin(slot);
            bind(slot.command, slot);
            barrier(slot.command, stack, VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT);
            dispatch(slot.command, 1, width, height / 2);
            barrier(slot.command, stack, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);
            dispatch(slot.command, 2, 1, 1);
            barrier(slot.command, stack, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT);
            var copy = VkBufferImageCopy.calloc(1, stack);
            copy.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            copy.imageExtent().set(width, height + 1, 1);
            vkCmdCopyImageToBuffer(slot.command, images[3].image, VK_IMAGE_LAYOUT_GENERAL, slot.readback.buffer, copy);
            var host = VkMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT);
            vkCmdPipelineBarrier(slot.command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, host, null, null);
            submit(slot);
        }
        pending++;
        writeIndex = (writeIndex + 1) % RING_SIZE;
        return true;
    }

    public ByteBuffer consume() {
        if (pending == 0) return null;
        Slot slot = slots[readIndex];
        int status = vkGetFenceStatus(device, slot.fence);
        if (status == VK_NOT_READY) return null;
        check(status, "read sky fence");
        Vma.vmaInvalidateAllocation(backend.vma(), slot.readback.allocation, 0, bytes);
        readIndex = (readIndex + 1) % RING_SIZE;
        pending--;
        return slot.readback.mapped.clear();
    }

    public void readLut(ByteBuffer destination) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int length = AtmosphericSkyGenerator.LUT_WIDTH * AtmosphericSkyGenerator.LUT_HEIGHT * 16;
            Buffer staging = buffer(length, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            Slot slot = slots[writeIndex];
            check(vkWaitForFences(device, slot.fence, true, Long.MAX_VALUE), "wait for LUT readback slot");
            begin(slot);
            barrier(slot.command, stack, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT);
            var copy = VkBufferImageCopy.calloc(1, stack);
            copy.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            copy.imageExtent().set(AtmosphericSkyGenerator.LUT_WIDTH, AtmosphericSkyGenerator.LUT_HEIGHT, 1);
            vkCmdCopyImageToBuffer(slot.command, images[0].image, VK_IMAGE_LAYOUT_GENERAL, staging.buffer, copy);
            submit(slot);
            check(vkWaitForFences(device, slot.fence, true, Long.MAX_VALUE), "wait for sky LUT readback");
            Vma.vmaInvalidateAllocation(backend.vma(), staging.allocation, 0, length);
            destination.clear().put(staging.mapped).flip();
            Vma.vmaDestroyBuffer(backend.vma(), staging.buffer, staging.allocation);
        }
    }

    private void bind(VkCommandBuffer command, Slot slot) {
        vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(slot.descriptorSet), null);
        }
    }

    private void dispatch(VkCommandBuffer command, int pass, int x, int y) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdPushConstants(command, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, stack.ints(pass));
        }
        vkCmdDispatch(command, (x + 7) / 8, (y + 7) / 8, 1);
    }

    private static void barrier(VkCommandBuffer command, MemoryStack stack, int src, int dst) {
        var memory = VkMemoryBarrier.calloc(1, stack).sType$Default().srcAccessMask(src).dstAccessMask(dst);
        vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, memory, null, null);
    }

    private void begin(Slot slot) {
        check(vkResetFences(device, slot.fence), "reset sky fence");
        check(vkResetCommandBuffer(slot.command, 0), "reset sky command buffer");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            check(vkBeginCommandBuffer(slot.command, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin sky command buffer");
        }
    }

    private void submit(Slot slot) {
        check(vkEndCommandBuffer(slot.command), "end sky command buffer");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            check(vkQueueSubmit(backend.graphicsQueue().vkQueue(), VkSubmitInfo.calloc(stack).sType$Default()
                    .pCommandBuffers(stack.pointers(slot.command)), slot.fence), "submit sky computation");
        }
    }

    private void transitionImages(VkCommandBuffer command, MemoryStack stack) {
        var barriers = VkImageMemoryBarrier.calloc(images.length, stack);
        for (int i = 0; i < images.length; i++) {
            var barrier = barriers.get(i).sType$Default().srcAccessMask(0)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT)
                    .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(images[i].image);
            barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
        }
        vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, null, barriers);
    }

    private Slot createSlot() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var out = stack.mallocLong(1);
            check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(descriptorPool).pSetLayouts(stack.longs(descriptorLayout)), out), "allocate sky descriptors");
            long descriptor = out.get(0);
            Buffer parameters = buffer(80, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
            Buffer readback = buffer(bytes, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            var writes = VkWriteDescriptorSet.calloc(6, stack);
            for (int i = 0; i < 5; i++) {
                var info = VkDescriptorImageInfo.calloc(1, stack).imageLayout(VK_IMAGE_LAYOUT_GENERAL)
                        .imageView(images[i < 4 ? i : 0].view).sampler(i == 4 ? sampler : 0);
                writes.get(i).sType$Default().dstSet(descriptor).dstBinding(i).descriptorCount(1)
                        .descriptorType(i < 4 ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE : VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(info);
            }
            var info = VkDescriptorBufferInfo.calloc(1, stack).buffer(parameters.buffer).offset(0).range(80);
            writes.get(5).sType$Default().dstSet(descriptor).dstBinding(5).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).pBufferInfo(info);
            vkUpdateDescriptorSets(device, writes, null);
            PointerBuffer command = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), command), "allocate sky command buffer");
            check(vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default().flags(VK_FENCE_CREATE_SIGNALED_BIT), null, out), "create sky fence");
            return new Slot(new VkCommandBuffer(command.get(0), device), out.get(0), descriptor, parameters, readback);
        }
    }

    private Image image(int w, int h, int format) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var create = VkImageCreateInfo.calloc(stack).sType$Default().imageType(VK_IMAGE_TYPE_2D)
                    .format(format).mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL).usage(VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            create.extent().set(w, h, 1);
            var image = stack.mallocLong(1);
            var allocation = stack.mallocPointer(1);
            check(Vma.vmaCreateImage(backend.vma(), create, VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE), image, allocation, null), "allocate sky image");
            var viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default().image(image.get(0)).viewType(VK_IMAGE_VIEW_TYPE_2D).format(format);
            viewInfo.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            var view = stack.mallocLong(1);
            check(vkCreateImageView(device, viewInfo, null, view), "create sky image view");
            return new Image(image.get(0), allocation.get(0), view.get(0));
        }
    }

    private Buffer buffer(long size, int usage) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var buffer = stack.mallocLong(1);
            var allocation = stack.mallocPointer(1);
            var allocationInfo = VmaAllocationInfo.calloc(stack);
            check(Vma.vmaCreateBuffer(backend.vma(), VkBufferCreateInfo.calloc(stack).sType$Default().size(size).usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE), VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO).flags(Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT
                            | Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT), buffer, allocation, allocationInfo), "allocate sky buffer");
            return new Buffer(buffer.get(0), allocation.get(0), MemoryUtil.memByteBuffer(allocationInfo.pMappedData(), (int) size));
        }
    }

    private void createPipeline(String source) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        long result = Shaderc.shaderc_compile_into_spv(compiler, source, Shaderc.shaderc_compute_shader, "sky_atmosphere.comp", "main", 0);
        long module = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
                throw new IllegalStateException("Atmospheric sky shader: " + Shaderc.shaderc_result_get_error_message(result));
            }
            var out = stack.mallocLong(1);
            check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default()
                    .pCode(Shaderc.shaderc_result_get_bytes(result)), null, out), "create sky shader module");
            module = out.get(0);
            var create = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default().layout(pipelineLayout);
            create.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            check(vkCreateComputePipelines(device, VK_NULL_HANDLE, create, null, out), "create sky compute pipeline");
            pipeline = out.get(0);
        } finally {
            if (module != 0) vkDestroyShaderModule(device, module, null);
            Shaderc.shaderc_result_release(result);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    @Override
    public void close() {
        for (Slot slot : slots) if (slot != null) {
            vkWaitForFences(device, slot.fence, true, Long.MAX_VALUE);
            vkDestroyFence(device, slot.fence, null);
            Vma.vmaDestroyBuffer(backend.vma(), slot.parameters.buffer, slot.parameters.allocation);
            Vma.vmaDestroyBuffer(backend.vma(), slot.readback.buffer, slot.readback.allocation);
        }
        if (pipeline != 0) vkDestroyPipeline(device, pipeline, null);
        if (pipelineLayout != 0) vkDestroyPipelineLayout(device, pipelineLayout, null);
        if (descriptorPool != 0) vkDestroyDescriptorPool(device, descriptorPool, null);
        if (descriptorLayout != 0) vkDestroyDescriptorSetLayout(device, descriptorLayout, null);
        if (sampler != 0) vkDestroySampler(device, sampler, null);
        for (Image image : images) if (image != null) {
            vkDestroyImageView(device, image.view, null);
            Vma.vmaDestroyImage(backend.vma(), image.image, image.allocation);
        }
        if (commandPool != 0) vkDestroyCommandPool(device, commandPool, null);
    }

    private static void check(int status, String operation) {
        if (status != VK_SUCCESS) throw new IllegalStateException(operation + " failed: VkResult " + status);
    }
    private record Image(long image, long allocation, long view) {}
    private record Buffer(long buffer, long allocation, ByteBuffer mapped) {}
    private record Slot(VkCommandBuffer command, long fence, long descriptorSet, Buffer parameters, Buffer readback) {}
}
