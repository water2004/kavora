/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gametest.mixin;

import com.nvidia.rtxremix.minecraft.entity.RemixEntitySubmitter;
import com.nvidia.rtxremix.minecraft.gametest.RemixFrameProbe;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = RemixEntitySubmitter.class, remap = false)
public abstract class RemixEntityTextureProbeMixin {
    @Inject(method = "tryCreateTextureSet", at = @At("RETURN"))
    private void rtxremix$builtPlayerTexture(Identifier id, CallbackInfoReturnable<?> cir) {
        if (cir.getReturnValue() != null && id.getPath().startsWith("textures/entity/player/")) {
            RemixFrameProbe.playerTextureBuilds++;
        }
    }
}
