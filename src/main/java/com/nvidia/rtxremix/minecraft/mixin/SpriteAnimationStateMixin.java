/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.texture.AtlasAnimationForwarder;
import com.nvidia.rtxremix.minecraft.texture.SpriteAnimationSource;
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
    @Unique private TextureAtlasSprite rtxremix$sprite;

    @Override
    public void rtxremix$setSprite(TextureAtlasSprite sprite) {
        rtxremix$sprite = sprite;
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void rtxremix$captureAnimation(CallbackInfo ci) {
        if (rtxremix$sprite == null || (!isDirty && !animationInfo.interpolateFrames)) return;
        SpriteContents.FrameInfo current = animationInfo.frames.get(frame);
        SpriteContents.FrameInfo next = animationInfo.frames.get((frame + 1) % animationInfo.frames.size());
        float progress = animationInfo.interpolateFrames ? (float) subFrame / current.time : 0;
        float blend = (int) (progress * 1000.0f) / 1000.0f;
        AtlasAnimationForwarder.addAnimationPatch(rtxremix$sprite, current.index, next.index, blend);
    }
}
