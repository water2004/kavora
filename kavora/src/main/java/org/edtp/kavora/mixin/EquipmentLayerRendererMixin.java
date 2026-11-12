/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.EquipmentAsset;
import org.edtp.kavora.entity.KavoraSubmitNodeCollector;
import org.edtp.kavora.sdk.MaterialInfo.GlintMode;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(EquipmentLayerRenderer.class)
public abstract class EquipmentLayerRendererMixin {
    @WrapMethod(method = "renderLayers(Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;"
            + "Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;"
            + "Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;II)V")
    private void kavora$captureTrimmedArmorGlint(EquipmentClientInfo.LayerType layer, ResourceKey<EquipmentAsset> asset,
                                                Model<?> model, Object state, ItemStack stack, PoseStack pose,
                                                SubmitNodeCollector collector, int baseOrder, Identifier playerTexture,
                                                int light, int outline, Operation<Void> original) {
        if (collector instanceof KavoraSubmitNodeCollector capture
                && stack.has(DataComponents.TRIM) && stack.hasFoil()) {
            capture.withEquipmentGlint(GlintMode.ARMOR, () ->
                    original.call(layer, asset, model, state, stack, pose, collector, baseOrder, playerTexture, light, outline));
        } else {
            original.call(layer, asset, model, state, stack, pose, collector, baseOrder, playerTexture, light, outline);
        }
    }
}
