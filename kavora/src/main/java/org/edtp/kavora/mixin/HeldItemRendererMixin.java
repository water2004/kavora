/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import org.edtp.kavora.entity.EntityRenderContext;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Associates every first-person part with its vanilla hand. */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public class HeldItemRendererMixin {
    @Inject(method = "submitArmWithItem", at = @At("HEAD"))
    private void kavora$beginFirstPersonHand(PlayerRenderState player,
            FirstPersonHandsAndItemsRenderState hands, float partialTick, float pitch,
            InteractionHand hand, float swing, ItemStack stack, float equip,
            PoseStack poses, SubmitNodeCollector collector, int light, CallbackInfo ci) {
        EntityRenderContext context = EntityRenderContext.get();
        if (context.isRemixPass()) context.setActiveHand(hand);
    }

    @Inject(method = "submitArmWithItem", at = @At("RETURN"))
    private void kavora$endFirstPersonHand(PlayerRenderState player,
            FirstPersonHandsAndItemsRenderState hands, float partialTick, float pitch,
            InteractionHand hand, float swing, ItemStack stack, float equip,
            PoseStack poses, SubmitNodeCollector collector, int light, CallbackInfo ci) {
        EntityRenderContext context = EntityRenderContext.get();
        if (context.isRemixPass()) context.clearActiveHand();
    }
}
