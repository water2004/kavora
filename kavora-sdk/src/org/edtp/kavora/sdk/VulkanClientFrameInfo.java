/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.sdk;

/**
 * A borrowed final color image on the runtime's Vulkan device, in GENERAL layout.
 * The caller signals sourceReadyValue after rendering and keeps the image alive and unchanged
 * until the returned sourceConsumedValue is reached. Semaphore handles are native timeline
 * semaphores; the caller owns the ready semaphore and the runtime owns the consumed semaphore.
 * Presenting image zero synchronously releases the runtime's borrowed image views without
 * advancing a frame. Do this before destroying a previously presented image.
 */
public final class VulkanClientFrameInfo {
    public long device;
    public long image;
    public long imageGeneration;
    public int width;
    public int height;
    public int format;
    public boolean bottomUp;
    public boolean vsync;
    public boolean allowFrameGeneration;
    public long sourceReadySemaphore;
    public long sourceReadyValue;
    public long sourceConsumedSemaphore;
    public long sourceConsumedValue;
    /** Asynchronous successful presentation counters; restart comparisons when the epoch changes. */
    public long presentationEpoch;
    public long renderedFrames;
    public long generatedFrames;
}
