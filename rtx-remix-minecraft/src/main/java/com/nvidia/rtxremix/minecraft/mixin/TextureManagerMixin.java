/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.texture.DynamicTextureCache;
import com.nvidia.rtxremix.minecraft.texture.GlyphPageMirror;
import com.nvidia.rtxremix.minecraft.texture.TextureSourceRegistry;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureManager.class)
public class TextureManagerMixin {
    @Inject(method = "register", at = @At("RETURN"))
    private void rtxremix$onRegisterTexture(Identifier id, AbstractTexture texture, CallbackInfo ci) {
        TextureSourceRegistry.register(id, texture,
                ((com.nvidia.rtxremix.minecraft.texture.TextureSourceOwner) texture).rtxremix$gpuTexture());
        if (texture instanceof GlyphPageMirror.Owner page) {
            DynamicTextureCache.onRegisterGlyphPage(id, page.rtxremix$glyphPageMirror());
        }
    }
}
