/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.camera;

/** Render-thread camera playback writes only the clock's pose, preserving its rate. */
public interface CameraClockAccess {
    void kavora$setTotalTicks(long ticks);
    void kavora$setPartialTick(float partialTick);
}
