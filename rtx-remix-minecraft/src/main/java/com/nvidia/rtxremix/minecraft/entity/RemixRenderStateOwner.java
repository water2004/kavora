/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

/** Associates an extracted vanilla render state with its retained Remix owner. */
public interface RemixRenderStateOwner {
    void rtxremix$setOwner(Object owner, float partialTick);
    Object rtxremix$owner();
    float rtxremix$partialTick();
}
