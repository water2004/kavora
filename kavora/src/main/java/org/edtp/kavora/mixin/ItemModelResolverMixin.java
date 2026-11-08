/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import org.edtp.kavora.entity.KavoraItemRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemModelResolver.class)
public class ItemModelResolverMixin {
    @Inject(method = "updateForTopItem", at = @At("RETURN"))
    private void kavora$retainTopItem(ItemStackRenderState state, ItemStack stack,
            ItemDisplayContext mode, Level level, ItemOwner owner, int seed, CallbackInfo ci) {
        ((KavoraItemRenderState) state).kavora$setItem(stack,
                owner instanceof LivingEntity living ? living : null);
    }

    @Inject(method = "updateForLiving", at = @At("RETURN"))
    private void kavora$retainItem(ItemStackRenderState state, ItemStack stack,
                                   ItemDisplayContext mode, LivingEntity holder, CallbackInfo ci) {
        ((KavoraItemRenderState) state).kavora$setItem(stack, holder);
    }
}
