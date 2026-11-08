/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.entity.RemixRenderStateOwner;
import com.nvidia.rtxremix.minecraft.entity.RemixSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
    @Inject(method = "extractEntity", at = @At("RETURN"))
    private void rtxremix$retainOwner(Entity entity, float partialTick,
                                     CallbackInfoReturnable<EntityRenderState> cir) {
        ((RemixRenderStateOwner) cir.getReturnValue()).rtxremix$setOwner(entity, partialTick);
    }

    @WrapMethod(method = "submit")
    private void rtxremix$capture(EntityRenderState state, CameraRenderState camera,
            double x, double y, double z, PoseStack matrices, SubmitNodeCollector collector,
            Operation<Void> original) {
        if (!(collector instanceof RemixSubmitNodeCollector)) {
            RemixRenderStateOwner metadata = (RemixRenderStateOwner) state;
            RtxRemixMod mod = RtxRemixMod.getInstance();
            RemixRenderer renderer = mod == null ? null : mod.getRenderer();
            if (renderer != null && metadata.rtxremix$owner() instanceof Entity entity
                    && renderer.renderEntityInPlace(entity, state,
                            (EntityRenderDispatcher) (Object) this, metadata.rtxremix$partialTick())) return;
        }
        original.call(state, camera, x, y, z, matrices, collector);
    }
}
