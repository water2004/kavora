/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import org.edtp.kavora.entity.HeldItemEffectsRenderer;
import org.edtp.kavora.entity.KavoraItemRenderState;
import org.edtp.kavora.entity.KavoraSubmitNodeCollector;
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
    private void kavora$heldEffects(PoseStack poses, SubmitNodeCollector collector,
                                    int light, int overlay, int outline, CallbackInfo ci) {
        if (!(collector instanceof KavoraSubmitNodeCollector capture)) return;
        KavoraItemRenderState state = (KavoraItemRenderState) this$0;
        ItemDisplayContext mode = ((ItemStackRenderStateAccessor) this$0).kavora$displayContext();
        if (state.kavora$claimEffects()) {
            HeldItemEffectsRenderer.render(state.kavora$item(), mode, poses,
                    capture.provider(), itemTransform.scale().x());
        }
    }
}
