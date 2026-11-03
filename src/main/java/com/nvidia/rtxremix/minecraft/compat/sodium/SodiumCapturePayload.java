/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.compat.sodium;

import com.nvidia.rtxremix.minecraft.terrain.capture.BlockDataQueue;
import com.nvidia.rtxremix.minecraft.terrain.ChunkManager;

/** Owns one build result until adoption transfers it or disposal releases it. */
public final class SodiumCapturePayload {
    private BlockDataQueue.QueuedCapture capture;
    public void set(BlockDataQueue.QueuedCapture value) {
        if (capture != null) throw new IllegalStateException("Sodium build result already owns a capture");
        capture = value;
    }
    public BlockDataQueue.QueuedCapture take() {
        var value = capture;
        capture = null;
        return value;
    }
    public void discard() {
        var value = take();
        if (value != null && value.data().meshData() != null) value.data().meshData().release();
    }
    public long estimatedBytes() {
        if (capture == null || capture.data().meshData() == null) return 0;
        long bytes = 0;
        for (int category = 0; category < ChunkManager.CAT_COUNT; category++)
            bytes += capture.data().meshData().categoryEstimatedMeshBytes(category);
        return bytes;
    }
}
