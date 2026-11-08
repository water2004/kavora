/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.minecraft.vulkan.VulkanInterop;

/** Minecraft uses Vulkan; Remix shares images through explicit external synchronization. */
public enum RenderBackend {
    VULKAN;

    public static RenderBackend current() {
        RenderSystem.assertOnRenderThread();
        String name = RenderSystem.getDevice().getDeviceInfo().backendName();
        if (!"Vulkan".equals(name)) {
            throw new IllegalStateException("RTX Remix requires Minecraft's Vulkan backend; active backend: " + name);
        }
        return VULKAN;
    }

    public RemixInterop createInterop(RemixAPI api) {
        return new VulkanInterop(api);
    }
}
