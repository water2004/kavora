/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import org.edtp.kavora.texture.GlyphPageMirror;
import net.minecraft.client.gui.font.FontTexture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Mirrors font pages at the common RenderPearl CPU upload boundary. */
@Mixin(FontTexture.class)
public abstract class GlyphAtlasTextureMixin implements GlyphPageMirror.Owner {

    @Shadow @Final private boolean colored;

    private GlyphPageMirror kavora$mirror;

    @org.spongepowered.asm.mixin.injection.Inject(method = "<init>", at = @At("RETURN"))
    private void kavora$registerMirror(java.util.function.Supplier<String> label,
            net.minecraft.client.gui.font.GlyphRenderTypes types, boolean colored,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        org.edtp.kavora.texture.TextureSourceRegistry.registerGlyph(
                ((net.minecraft.client.renderer.texture.AbstractTexture) (Object) this).getTexture(),
                kavora$glyphPageMirror());
    }

    @Override
    public GlyphPageMirror kavora$glyphPageMirror() {
        // Created on first use: hasColor is assigned by the constructor, so a field
        // initialiser could not read it.
        if (kavora$mirror == null) {
            kavora$mirror = new GlyphPageMirror(!colored);
        }
        return kavora$mirror;
    }

}
