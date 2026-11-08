/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import org.edtp.kavora.texture.DynamicTextureCache;
import org.edtp.kavora.texture.GlyphPageMirror;
import org.edtp.kavora.texture.TextureSourceRegistry;
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
    private void kavora$onRegisterTexture(Identifier id, AbstractTexture texture, CallbackInfo ci) {
        TextureSourceRegistry.register(id, texture,
                ((org.edtp.kavora.texture.TextureSourceOwner) texture).kavora$gpuTexture());
        if (texture instanceof GlyphPageMirror.Owner page) {
            DynamicTextureCache.onRegisterGlyphPage(id, page.kavora$glyphPageMirror());
        }
    }
}
