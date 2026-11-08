/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.compat.sodium.mixin;

import org.edtp.kavora.KavoraMod;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Restores requested sections after the new manager has registered its ready chunks. */
@Mixin(SodiumWorldRenderer.class)
public abstract class SodiumWorldRendererMixin {
    @Shadow private ClientLevel level;

    @Inject(method = "initRenderer", at = @At("RETURN"))
    private void kavora$restoreTerrainAfterReload(CallbackInfo ci) {
        KavoraMod.onTerrainRendererReloaded(level);
    }
}
