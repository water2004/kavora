/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.compat.sodium;

import org.edtp.kavora.terrain.capture.BlockDataQueue;

/** One CPU payload follows the same acceptance/disposal decision as Sodium's mesh output. */
public interface SodiumCaptureOutput {
    void kavora$setCapture(BlockDataQueue.QueuedCapture capture);
    BlockDataQueue.QueuedCapture kavora$takeCapture();
    boolean kavora$wasCaptured();
}
