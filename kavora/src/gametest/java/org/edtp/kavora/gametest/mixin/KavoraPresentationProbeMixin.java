/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.gametest.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.edtp.kavora.gametest.KavoraFrameProbe;
import org.edtp.kavora.render.KavoraFramePresentation;
import org.edtp.kavora.sdk.VulkanClientFrameInfo;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = KavoraFramePresentation.class, remap = false)
public abstract class KavoraPresentationProbeMixin {
    @WrapMethod(method = "submitScene")
    private void submitted(Operation<Void> original) {
        try {
            original.call();
            KavoraFrameProbe.submits++;
        } catch (RuntimeException | Error failure) {
            KavoraFrameProbe.failures++;
            throw failure;
        }
    }

    @WrapMethod(method = "present")
    private void presented(VulkanClientFrameInfo frame, Operation<Void> original) {
        try {
            original.call(frame);
            KavoraFrameProbe.presents++;
        } catch (RuntimeException | Error failure) {
            KavoraFrameProbe.failures++;
            throw failure;
        }
    }
}
