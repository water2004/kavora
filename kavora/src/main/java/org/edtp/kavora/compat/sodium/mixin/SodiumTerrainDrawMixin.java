/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.compat.sodium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import org.edtp.kavora.KavoraMod;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.util.SodiumChunkSection;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.oit.OitStage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Replaces Sodium's terrain draws while preserving the caller's render-pass lifetime. */
@Mixin(SodiumChunkSection.class)
public abstract class SodiumTerrainDrawMixin {
    @WrapOperation(method = {"renderGroup", "renderOit"}, at = @At(value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/render/SodiumWorldRenderer;drawChunkLayer(Lcom/mojang/renderpearl/api/commands/RenderPass;Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderMatrices;DDDLcom/mojang/renderpearl/api/textures/GpuSampler;Lnet/minecraft/client/renderer/oit/OitStage;)V"))
    private void kavora$skipReplacedTerrain(SodiumWorldRenderer renderer, RenderPass pass,
            ChunkSectionLayerGroup group, ChunkRenderMatrices matrices, double x, double y, double z,
            GpuSampler sampler, OitStage stage, Operation<Void> original) {
        if (!KavoraMod.isRemixWorldRendering()) {
            original.call(renderer, pass, group, matrices, x, y, z, sampler, stage);
        }
    }
}
