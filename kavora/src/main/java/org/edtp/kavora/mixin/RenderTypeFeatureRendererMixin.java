/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.edtp.kavora.entity.KavoraSubmitNodeCollector;
import java.util.List;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Redirects only capture-owned CPU replays; vanilla's frame builder stays untouched. */
@Mixin(RenderTypeFeatureRenderer.class)
public abstract class RenderTypeFeatureRendererMixin {


    @Inject(method = "getVertexBuilder", at = @At("HEAD"), cancellable = true)
    private void kavora$captureBuilder(RenderType layer, CallbackInfoReturnable<VertexConsumer> cir) {
        var provider = KavoraSubmitNodeCollector.activeProvider();
        if (provider != null) cir.setReturnValue(provider.getBuffer(layer));
    }
}
