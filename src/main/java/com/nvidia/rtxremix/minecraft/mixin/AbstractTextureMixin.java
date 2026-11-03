/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.nvidia.rtxremix.minecraft.texture.TextureSourceRegistry;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractTexture.class)
public abstract class AbstractTextureMixin implements com.nvidia.rtxremix.minecraft.texture.TextureSourceOwner {
    @Override public GpuTexture rtxremix$gpuTexture() { return texture; }
    @Shadow protected GpuTexture texture;

    @Inject(method = {"getTexture", "getTextureView"}, at = @At("RETURN"))
    private void rtxremix$bindCpuSource(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<?> ci) {
        TextureSourceRegistry.bind((AbstractTexture) (Object) this, texture);
    }

    @Inject(method = "releaseTextures", at = @At("HEAD"))
    private void rtxremix$releaseCpuSource(CallbackInfo ci) {
        if (texture != null) TextureSourceRegistry.release(texture);
    }
}
