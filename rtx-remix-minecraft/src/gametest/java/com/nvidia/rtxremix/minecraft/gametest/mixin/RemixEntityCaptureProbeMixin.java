/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gametest.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.nvidia.rtxremix.minecraft.gametest.RemixFrameProbe;
import com.nvidia.rtxremix.minecraft.entity.RemixSubmitNodeCollector;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = RemixSubmitNodeCollector.class, remap = false)
public abstract class RemixEntityCaptureProbeMixin {
    @WrapMethod(method = "capture")
    private void rtxremix$recordCaptureFailure(Operation<Void> original) {
        try {
            original.call();
        } catch (RuntimeException | Error failure) {
            RemixFrameProbe.failures++;
            throw failure;
        }
    }
}
