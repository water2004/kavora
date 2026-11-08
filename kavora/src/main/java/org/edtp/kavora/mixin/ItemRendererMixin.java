/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import org.edtp.kavora.entity.EntityRenderContext;
import org.edtp.kavora.entity.KavoraItemRenderState;
import org.edtp.kavora.terrain.capture.BlockQuadEmitter;
import org.edtp.kavora.texture.BlockTextureManager;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Restores item identity while the extracted layers are submitted to capture. */
@Mixin(ItemStackRenderState.class)
public class ItemRendererMixin implements KavoraItemRenderState {
    @Unique private ItemStack kavora$stack = ItemStack.EMPTY;
    @Unique private LivingEntity kavora$holder;
    @Unique private boolean kavora$effectsClaimed;

    @Override
    public void kavora$setItem(ItemStack stack, LivingEntity holder) {
        kavora$stack = stack;
        kavora$holder = holder;
    }

    @Override
    public ItemStack kavora$item() { return kavora$stack; }

    @Override
    public boolean kavora$claimEffects() {
        if (kavora$effectsClaimed) return false;
        kavora$effectsClaimed = true;
        return true;
    }

    @Inject(method = "clear", at = @At("HEAD"))
    private void kavora$clear(CallbackInfo ci) {
        kavora$stack = ItemStack.EMPTY;
        kavora$holder = null;
    }

    @Inject(method = "submit", at = @At("HEAD"))
    private void kavora$beginItem(PoseStack poses, SubmitNodeCollector collector,
                                  int light, int overlay, int outline, CallbackInfo ci) {
        EntityRenderContext context = EntityRenderContext.get();
        if (!context.isRemixPass()) return;
        kavora$effectsClaimed = false;
        context.setHeldItemHolder(kavora$holder);
        context.setBlockItemLayer(kavora$stack.getItem() instanceof BlockItem blockItem
                ? BlockTextureManager.layerType(BlockQuadEmitter.classifyBlockLayer(
                        blockItem.getBlock().defaultBlockState()))
                : EntityRenderContext.NO_BLOCK_ITEM);
    }

    @Inject(method = "submit", at = @At("RETURN"))
    private void kavora$endItem(PoseStack poses, SubmitNodeCollector collector,
                                int light, int overlay, int outline, CallbackInfo ci) {
        EntityRenderContext context = EntityRenderContext.get();
        if (!context.isRemixPass()) return;
        context.clearHeldItemHolder();
        context.setBlockItemLayer(EntityRenderContext.NO_BLOCK_ITEM);
    }
}
