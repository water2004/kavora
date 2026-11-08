/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import org.edtp.kavora.texture.AtlasAnimationForwarder;
import org.edtp.kavora.texture.SpriteAnimationSource;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SpriteContents.AnimationState.class)
public abstract class SpriteAnimationStateMixin implements SpriteAnimationSource {
    @Shadow private int frame;
    @Shadow private int subFrame;
    @Shadow @Final private SpriteContents.AnimatedTexture animationInfo;
    @Shadow private boolean isDirty;
    @Unique private TextureAtlasSprite kavora$sprite;

    @Override
    public void kavora$setSprite(TextureAtlasSprite sprite) {
        kavora$sprite = sprite;
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void kavora$captureAnimation(CallbackInfo ci) {
        if (kavora$sprite == null || (!isDirty && !animationInfo.interpolateFrames)) return;
        SpriteContents.FrameInfo current = animationInfo.frames.get(frame);
        SpriteContents.FrameInfo next = animationInfo.frames.get((frame + 1) % animationInfo.frames.size());
        float progress = animationInfo.interpolateFrames ? (float) subFrame / current.time : 0;
        float blend = (int) (progress * 1000.0f) / 1000.0f;
        AtlasAnimationForwarder.addAnimationPatch(kavora$sprite, current.index, next.index, blend);
    }
}
