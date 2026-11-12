/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.special.ShieldSpecialRenderer;
import net.minecraft.core.component.DataComponentMap;
import org.edtp.kavora.entity.KavoraSubmitNodeCollector;
import org.edtp.kavora.sdk.MaterialInfo.GlintMode;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ShieldSpecialRenderer.class)
public abstract class ShieldSpecialRendererMixin {
    @WrapMethod(method = "submit(Lnet/minecraft/core/component/DataComponentMap;Lcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/SubmitNodeCollector;IIZI)V")
    private void kavora$captureShieldGlint(DataComponentMap components, PoseStack pose, SubmitNodeCollector collector,
                                          int light, int overlay, boolean foil, int outline, Operation<Void> original) {
        if (foil && collector instanceof KavoraSubmitNodeCollector capture) {
            capture.withEquipmentGlint(GlintMode.ENTITY, () ->
                    original.call(components, pose, collector, light, overlay, foil, outline));
        } else {
            original.call(components, pose, collector, light, overlay, foil, outline);
        }
    }
}
