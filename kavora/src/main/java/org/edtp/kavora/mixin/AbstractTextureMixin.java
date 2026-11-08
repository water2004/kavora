/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import com.mojang.renderpearl.api.textures.GpuTexture;
import org.edtp.kavora.texture.TextureSourceRegistry;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractTexture.class)
public abstract class AbstractTextureMixin implements org.edtp.kavora.texture.TextureSourceOwner {
    @Override public GpuTexture kavora$gpuTexture() { return texture; }
    @Shadow protected GpuTexture texture;

    @Inject(method = {"getTexture", "getTextureView"}, at = @At("RETURN"))
    private void kavora$bindCpuSource(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<?> ci) {
        TextureSourceRegistry.bind((AbstractTexture) (Object) this, texture);
    }

    @Inject(method = "releaseTextures", at = @At("HEAD"))
    private void kavora$releaseCpuSource(CallbackInfo ci) {
        if (texture != null) TextureSourceRegistry.release(texture);
    }
}
