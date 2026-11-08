/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.entity.HeldItemEffectsRenderer;
import com.nvidia.rtxremix.minecraft.entity.RemixItemRenderState;
import com.nvidia.rtxremix.minecraft.entity.RemixSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemStackRenderState.LayerRenderState.class)
public class ItemLayerRenderStateMixin {
    @Shadow @Final private ItemStackRenderState this$0;
    @Shadow private ItemTransform itemTransform;

    @Inject(method = "submit", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/PoseStack;popPose()V"))
    private void rtxremix$heldEffects(PoseStack poses, SubmitNodeCollector collector,
                                    int light, int overlay, int outline, CallbackInfo ci) {
        if (!(collector instanceof RemixSubmitNodeCollector capture)) return;
        RemixItemRenderState state = (RemixItemRenderState) this$0;
        ItemDisplayContext mode = ((ItemStackRenderStateAccessor) this$0).rtxremix$displayContext();
        if (state.rtxremix$claimEffects()) {
            HeldItemEffectsRenderer.render(state.rtxremix$item(), mode, poses,
                    capture.provider(), itemTransform.scale().x());
        }
    }
}
