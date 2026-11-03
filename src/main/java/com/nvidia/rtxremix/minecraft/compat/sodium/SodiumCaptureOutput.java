/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.compat.sodium;

import com.nvidia.rtxremix.minecraft.terrain.capture.BlockDataQueue;

/** One CPU payload follows the same acceptance/disposal decision as Sodium's mesh output. */
public interface SodiumCaptureOutput {
    void rtxremix$setCapture(BlockDataQueue.QueuedCapture capture);
    BlockDataQueue.QueuedCapture rtxremix$takeCapture();
}
