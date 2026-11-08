/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import com.nvidia.rtxremix.minecraft.entity.RemixEntitySubmitter;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureAtlas.class)
public abstract class SpriteAtlasTextureMixin {

    @Shadow @Final private Identifier location;

    @Inject(method = "createTexture", at = @At("RETURN"))
    private void rtxremix$useSpritePixels(int width, int height, int mipLevel, CallbackInfo ci) {
        com.nvidia.rtxremix.minecraft.texture.TextureSourceRegistry.registerSpriteAtlas(
                ((com.nvidia.rtxremix.minecraft.texture.TextureSourceOwner) this).rtxremix$gpuTexture());
    }

    @Inject(method = "upload", at = @At("RETURN"))
    private void rtxremix$onAtlasUploaded(SpriteLoader.Preparations stitchResult, CallbackInfo ci) {
        RemixEntitySubmitter.onMinecraftAtlasUploaded(location);
    }
}
