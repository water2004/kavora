/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.sdk;

/** Reusable source-built Remix Vulkan interop metadata. Image generations identify borrowed resources. */
public final class VulkanInteropInfo {
    /** Exact VkDevice identity, shared by host and runtime. */
    public long device;
    public final byte[] deviceUUID = new byte[16];
    public final byte[] driverUUID = new byte[16];
    public final byte[] deviceLUID = new byte[8];
    public int deviceNodeMask;
    public int deviceLUIDValid;
    public int queueFamilyIndex;
    public int semaphoreType;
    public long dxvkDoneSemaphore_Handle;
    public long dxvkDoneSemaphore_ValueToWait;
    public long clientDoneSemaphore_Handle;
    public final Image color = new Image();
    public final Image depth = new Image();
    /** Transparent premultiplied runtime UI, composited over the client UI. */
    public final Image uiOverlay = new Image();
    /** Status of the frame paired with the exported done-semaphore value. */
    public final InteropFrameStatus frameStatus = new InteropFrameStatus();

    public static final class Image {
        /** Read-only borrowed VkImage. The runtime retains ownership; honor both frame timelines. */
        public long image;
        public long memoryHandle;
        public long allocationSize;
        public long bindOffset;
        public long generation;
        public int memoryTypeIndex;
        public int allocationFlags;
        public int allocationDeviceMask;
        public int flags;
        public int imageType;
        public int format;
        public int width;
        public int height;
        public int depth;
        public int mipLevels;
        public int arrayLayers;
        public int samples;
        public int tiling;
        public int usage;
        public int sharingMode;
        public int initialLayout;
        public int externalLayout;
        public int ownerQueueFamily;
        public int externalMemoryHandleType;
        public int dedicatedAllocation;
        public int viewFormatCount;
        public final int[] viewFormats = new int[16];
    }
}
