/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

/**
 * Present information structure.
 */
public class PresentInfo {
    public long hwndOverride; // Can be NULL
    public int skipVulkanSwapchain;
    public int interopFlipY;
    public long clientDoneSemaphore_ValueToWait;
    public int disableCpuWaitForExternalSemaphores;
    /** Optional final client image for the native Vulkan presentation surface. */
    public VulkanClientFrameInfo vulkanFrame;

    public PresentInfo() {}
}
