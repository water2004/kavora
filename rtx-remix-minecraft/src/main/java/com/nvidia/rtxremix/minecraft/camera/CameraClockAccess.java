/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.camera;

/** Render-thread camera playback writes only the clock's pose, preserving its rate. */
public interface CameraClockAccess {
    void rtxremix$setTotalTicks(long ticks);
    void rtxremix$setPartialTick(float partialTick);
}
