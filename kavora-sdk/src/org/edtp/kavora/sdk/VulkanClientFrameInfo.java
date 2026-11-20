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
 * Shared-device RGBA16F contains display-referred extended-sRGB, with one at paper white
 * and values above one retaining highlight headroom. It is not linear scRGB. The runtime
 * converts this image to the negotiated display color space. GUI colors remain sRGB.
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
    /** Opt into foreground UI separation for this presenter. */
    public boolean provideHudlessColor;
    /** Optional borrowed pre-UI color with the same format, extent and lifetime as image. */
    public long hudlessImage;
    /** With a pre-UI image, the composited image alpha records this frame's UI coverage. */
    public boolean uiCoverageInAlpha;
    /** Set by the presenter when the next frame can benefit from a pre-UI image. */
    public boolean frameGenerationEnabled;
}
