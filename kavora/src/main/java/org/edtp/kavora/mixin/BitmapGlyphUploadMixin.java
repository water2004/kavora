/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import com.mojang.renderpearl.api.textures.GpuTexture;
import net.minecraft.client.gui.font.providers.BitmapProvider;
import org.edtp.kavora.texture.TextureSourceRegistry;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.gui.font.providers.BitmapProvider$Glyph$1")
public abstract class BitmapGlyphUploadMixin {
    @Shadow @Final private BitmapProvider.Glyph this$0;

    @Inject(method = "upload", at = @At("RETURN"))
    private void kavora$mirrorBitmapGlyph(int x, int y, GpuTexture texture, CallbackInfo ci) {
        var glyph = this$0;
        TextureSourceRegistry.uploadBitmapGlyph(texture, glyph.imageData().image,
                glyph.offsetX(), glyph.offsetY(), x, y, glyph.width(), glyph.height());
    }
}
