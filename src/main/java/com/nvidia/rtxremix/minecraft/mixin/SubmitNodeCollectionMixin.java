/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.entity.RemixSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps item/hand context live until each collected feature has emitted its geometry. */
@Mixin(SubmitNodeCollection.class)
public abstract class SubmitNodeCollectionMixin {
    @Inject(method = {"submitShadow", "submitNameTag", "submitText", "submitTextBackground",
            "submitFlame", "submitLeash", "submitModel", "submitCrumblingOverlay",
            "submitMovingBlock", "submitBlockModel", "submitBreakingBlockModel", "submitShapeOutline",
            "submitItem", "submitCustomGeometry", "submitQuadParticleGroup", "submitGizmoPrimitives"},
            at = @At("RETURN"))
    private void rtxremix$finishCaptureSubmit(CallbackInfo ci) {
        if ((Object) this instanceof RemixSubmitNodeCollector.CaptureCollection capture) capture.capture();
    }
}
