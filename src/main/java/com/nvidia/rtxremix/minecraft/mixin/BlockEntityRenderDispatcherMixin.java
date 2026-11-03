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
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Captures the extracted block-entity pose where vanilla submits it. */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {
    @Inject(method = "tryExtractRenderState", at = @At("RETURN"))
    private void rtxremix$retainOwner(BlockEntity entity, float partialTick,
            ModelFeatureRenderer.CrumblingOverlay breaking, boolean outline,
            CallbackInfoReturnable<BlockEntityRenderState> cir) {
        if (cir.getReturnValue() != null) {
            ((RemixRenderStateOwner) cir.getReturnValue()).rtxremix$setOwner(entity, partialTick);
        }
    }

    @WrapMethod(method = "submit")
    private void rtxremix$capture(BlockEntityRenderState state, PoseStack matrices,
            SubmitNodeCollector collector, CameraRenderState camera, Operation<Void> original) {
        if (!(collector instanceof RemixSubmitNodeCollector)) {
            RemixRenderStateOwner metadata = (RemixRenderStateOwner) state;
            RtxRemixMod mod = RtxRemixMod.getInstance();
            RemixRenderer renderer = mod == null ? null : mod.getRenderer();
            if (renderer != null && metadata.rtxremix$owner() instanceof BlockEntity entity
                    && entity.hasLevel() && renderer.renderBlockEntityInPlace(entity,
                    (captureMatrices, captureCollector) ->
                            original.call(state, captureMatrices, captureCollector, camera))) return;
        }
        original.call(state, matrices, collector, camera);
    }
}
