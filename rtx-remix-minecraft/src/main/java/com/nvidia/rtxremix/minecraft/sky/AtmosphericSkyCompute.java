/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import java.nio.ByteBuffer;

/** Backend-owned atmospheric compute resources and asynchronous readback. */
interface AtmosphericSkyCompute extends AutoCloseable {
    boolean enqueue(ByteBuffer parameters);
    ByteBuffer consume();
    void readLut(int index, ByteBuffer destination);
    @Override void close();
}
