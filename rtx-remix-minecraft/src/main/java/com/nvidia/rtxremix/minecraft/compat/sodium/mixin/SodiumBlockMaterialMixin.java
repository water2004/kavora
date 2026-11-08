/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.compat.sodium.mixin;
import com.nvidia.rtxremix.minecraft.compat.sodium.SodiumCapture;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.model.MutableQuadViewImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(BlockRenderer.class)
public abstract class SodiumBlockMaterialMixin {
    @Inject(method = "bufferQuad", at = @At("HEAD"))
    private void rtxremix$material(MutableQuadViewImpl quad, float[] brightness, Material material, CallbackInfo ci) {
        SodiumCapture.material(material);
    }
}
