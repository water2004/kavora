/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.sdk;

/** Host requests and the resulting shared logical device. All addresses are native Vulkan addresses. */
public final class HostVulkanDeviceInfo {
    public long instance;
    public long physicalDevice;
    public int instanceApiVersion;
    public int instanceExtensionCount;
    public long instanceExtensionsAddress;
    public int graphicsQueueFamily;
    public long deviceCreateInfoAddress;
    public long device;
    public int enabledExtensionCount;
    /** Native-owned char**; valid until the API shuts down. */
    public long enabledExtensionsAddress;
}
