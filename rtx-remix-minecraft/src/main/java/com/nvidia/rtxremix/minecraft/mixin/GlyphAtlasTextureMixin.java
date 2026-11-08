/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.texture.GlyphPageMirror;
import net.minecraft.client.gui.font.FontTexture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Mirrors font pages at the common RenderPearl CPU upload boundary. */
@Mixin(FontTexture.class)
public abstract class GlyphAtlasTextureMixin implements GlyphPageMirror.Owner {

    @Shadow @Final private boolean colored;

    private GlyphPageMirror rtxremix$mirror;

    @org.spongepowered.asm.mixin.injection.Inject(method = "<init>", at = @At("RETURN"))
    private void rtxremix$registerMirror(java.util.function.Supplier<String> label,
            net.minecraft.client.gui.font.GlyphRenderTypes types, boolean colored,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        com.nvidia.rtxremix.minecraft.texture.TextureSourceRegistry.registerGlyph(
                ((net.minecraft.client.renderer.texture.AbstractTexture) (Object) this).getTexture(),
                rtxremix$glyphPageMirror());
    }

    @Override
    public GlyphPageMirror rtxremix$glyphPageMirror() {
        // Created on first use: hasColor is assigned by the constructor, so a field
        // initialiser could not read it.
        if (rtxremix$mirror == null) {
            rtxremix$mirror = new GlyphPageMirror(!colored);
        }
        return rtxremix$mirror;
    }

}
