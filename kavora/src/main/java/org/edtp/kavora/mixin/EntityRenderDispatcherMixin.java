/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import org.edtp.kavora.KavoraRenderer;
import org.edtp.kavora.KavoraMod;
import org.edtp.kavora.entity.KavoraRenderStateOwner;
import org.edtp.kavora.entity.KavoraSubmitNodeCollector;
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
    private void kavora$retainOwner(Entity entity, float partialTick,
                                     CallbackInfoReturnable<EntityRenderState> cir) {
        ((KavoraRenderStateOwner) cir.getReturnValue()).kavora$setOwner(entity, partialTick);
    }

    @WrapMethod(method = "submit")
    private void kavora$capture(EntityRenderState state, CameraRenderState camera,
            double x, double y, double z, PoseStack matrices, SubmitNodeCollector collector,
            Operation<Void> original) {
        if (!(collector instanceof KavoraSubmitNodeCollector)) {
            KavoraRenderStateOwner metadata = (KavoraRenderStateOwner) state;
            KavoraMod mod = KavoraMod.getInstance();
            KavoraRenderer renderer = mod == null ? null : mod.getRenderer();
            if (renderer != null && metadata.kavora$owner() instanceof Entity entity
                    && renderer.renderEntityInPlace(entity, state,
                            (EntityRenderDispatcher) (Object) this, metadata.kavora$partialTick())) return;
        }
        original.call(state, camera, x, y, z, matrices, collector);
    }
}
