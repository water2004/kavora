/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.gametest.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.edtp.kavora.gametest.KavoraFrameProbe;
import org.edtp.kavora.entity.KavoraSubmitNodeCollector;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = KavoraSubmitNodeCollector.class, remap = false)
public abstract class KavoraEntityCaptureProbeMixin {
    @WrapMethod(method = "capture")
    private void kavora$recordCaptureFailure(Operation<Void> original) {
        try {
            original.call();
        } catch (RuntimeException | Error failure) {
            KavoraFrameProbe.failures++;
            throw failure;
        }
    }
}
