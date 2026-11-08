/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.render;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;

/** Render-thread ownership of the runtime's scene, depth and transparent menu images. */
public interface KavoraInterop extends AutoCloseable {
    void refreshImages();
    void acquireImages();
    /** Records ownership release. Submit the client encoder before completing the runtime frame. */
    void releaseImages();
    /** Blits the acquired runtime color directly into Minecraft's composition target. */
    boolean blitColor(GpuTexture destination);
    /** Sampled snapshots are copied at most once per acquired frame, on first request. */
    GpuTextureView colorTexture();
    GpuTextureView depthTexture();
    GpuTextureView menuTexture();
    long snapshotTextureBytes();
    long copiedSnapshotBytes();
    long clientDoneValue();
    boolean isSceneReady();
    int renderWidth();
    int renderHeight();
    @Override void close();
}
