/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.entity.EntityRenderContext;
import com.nvidia.rtxremix.minecraft.entity.RemixItemRenderState;
import com.nvidia.rtxremix.minecraft.terrain.capture.BlockQuadEmitter;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
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
public class ItemRendererMixin implements RemixItemRenderState {
    @Unique private ItemStack rtxremix$stack = ItemStack.EMPTY;
    @Unique private LivingEntity rtxremix$holder;
    @Unique private boolean rtxremix$effectsClaimed;

    @Override
    public void rtxremix$setItem(ItemStack stack, LivingEntity holder) {
        rtxremix$stack = stack;
        rtxremix$holder = holder;
    }

    @Override
    public ItemStack rtxremix$item() { return rtxremix$stack; }

    @Override
    public boolean rtxremix$claimEffects() {
        if (rtxremix$effectsClaimed) return false;
        rtxremix$effectsClaimed = true;
        return true;
    }

    @Inject(method = "clear", at = @At("HEAD"))
    private void rtxremix$clear(CallbackInfo ci) {
        rtxremix$stack = ItemStack.EMPTY;
        rtxremix$holder = null;
    }

    @Inject(method = "submit", at = @At("HEAD"))
    private void rtxremix$beginItem(PoseStack poses, SubmitNodeCollector collector,
                                  int light, int overlay, int outline, CallbackInfo ci) {
        EntityRenderContext context = EntityRenderContext.get();
        if (!context.isRemixPass()) return;
        rtxremix$effectsClaimed = false;
        context.setHeldItemHolder(rtxremix$holder);
        context.setBlockItemLayer(rtxremix$stack.getItem() instanceof BlockItem blockItem
                ? BlockTextureManager.layerType(BlockQuadEmitter.classifyBlockLayer(
                        blockItem.getBlock().defaultBlockState()))
                : EntityRenderContext.NO_BLOCK_ITEM);
    }

    @Inject(method = "submit", at = @At("RETURN"))
    private void rtxremix$endItem(PoseStack poses, SubmitNodeCollector collector,
                                int light, int overlay, int outline, CallbackInfo ci) {
        EntityRenderContext context = EntityRenderContext.get();
        if (!context.isRemixPass()) return;
        context.clearHeldItemHolder();
        context.setBlockItemLayer(EntityRenderContext.NO_BLOCK_ITEM);
    }
}
