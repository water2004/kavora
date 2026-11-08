/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gametest.mixin;

import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.gametest.RemixFrameProbe;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RemixRenderer.class, remap = false)
public abstract class RemixFrameProbeMixin {
    @Inject(method = "compositeRemixMenus", at = @At("RETURN"))
    private void rtxremix$readMenuFrame(CallbackInfo ci) {
        Runnable capture = RemixFrameProbe.afterNativeMenus;
        if (capture != null) {
            RemixFrameProbe.afterNativeMenus = null;
            capture.run();
        }
    }

    @Inject(method = "onMinecraftWorldRenderFrameStart", at = @At("HEAD"))
    private void prepareCamera(net.minecraft.client.Minecraft client, boolean tick, CallbackInfo ci) {
        if (RemixFrameProbe.frameAction != null) RemixFrameProbe.frameAction.accept(RemixFrameProbe.presents);
    }

    @WrapMethod(method = "submitFrame")
    private void submitted(Operation<Void> original) {
        try {
            original.call();
            RemixFrameProbe.submits++;
        } catch (RuntimeException | Error failure) {
            RemixFrameProbe.failures++;
            throw failure;
        }
    }

    @WrapMethod(method = "presentFrame")
    private void presented(com.nvidia.rtxremix.VulkanClientFrameInfo frame, Operation<Void> original) {
        try {
            original.call(frame);
            RemixFrameProbe.presents++;
        } catch (RuntimeException | Error failure) {
            RemixFrameProbe.failures++;
            throw failure;
        }
    }
}
