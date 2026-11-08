/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import org.edtp.kavora.entity.KavoraSubmitNodeCollector;
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
    private void kavora$finishCaptureSubmit(CallbackInfo ci) {
        if ((Object) this instanceof KavoraSubmitNodeCollector.CaptureCollection capture) capture.capture();
    }
}
