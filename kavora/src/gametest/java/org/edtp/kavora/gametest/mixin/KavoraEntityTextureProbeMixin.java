/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.gametest.mixin;

import org.edtp.kavora.entity.KavoraEntitySubmitter;
import org.edtp.kavora.gametest.KavoraFrameProbe;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = KavoraEntitySubmitter.class, remap = false)
public abstract class KavoraEntityTextureProbeMixin {
    @Inject(method = "tryCreateTextureSet", at = @At("RETURN"))
    private void kavora$builtPlayerTexture(Identifier id, CallbackInfoReturnable<?> cir) {
        if (cir.getReturnValue() != null && id.getPath().startsWith("textures/entity/player/")) {
            KavoraFrameProbe.playerTextureBuilds++;
        }
    }
}
