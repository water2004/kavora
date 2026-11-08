/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import org.edtp.kavora.texture.SpriteAnimationSource;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TextureAtlasSprite.class)
public abstract class TextureAtlasSpriteMixin {
    @Inject(method = "createAnimationState", at = @At("RETURN"))
    private void kavora$setAnimationSource(GpuBufferSlice ubos, int mipLevels,
            CallbackInfoReturnable<SpriteContents.AnimationState> ci) {
        if (ci.getReturnValue() != null) {
            ((SpriteAnimationSource) ci.getReturnValue()).kavora$setSprite((TextureAtlasSprite) (Object) this);
        }
    }
}
