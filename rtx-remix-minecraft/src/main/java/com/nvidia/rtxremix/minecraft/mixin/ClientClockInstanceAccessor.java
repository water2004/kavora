/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.camera.CameraClockAccess;
import net.minecraft.client.ClientClockManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientClockManager.ClientClockInstance.class)
public interface ClientClockInstanceAccessor extends CameraClockAccess {
    @Override
    @Accessor("totalTicks")
    void rtxremix$setTotalTicks(long ticks);

    @Override
    @Accessor("partialTick")
    void rtxremix$setPartialTick(float partialTick);
}
