/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.nvidia.rtxremix.minecraft.texture.TextureSourceRegistry;
import java.nio.ByteBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures the common CPU upload path used by every RenderPearl backend. */
@Mixin(FrontendCommandEncoder.class)
public abstract class FrontendCommandEncoderMixin {
    @Inject(method = "writeToTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;Ljava/nio/ByteBuffer;IIIIII)V", at = @At("RETURN"))
    private void rtxremix$captureUpload(GpuTexture texture, ByteBuffer bytes, int mip,
            int layer, int x, int y, int width, int height, CallbackInfo ci) {
        TextureSourceRegistry.upload(texture, bytes, mip, layer, x, y, width, height);
    }
}
