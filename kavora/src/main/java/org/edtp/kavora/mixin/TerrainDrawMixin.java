/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import org.edtp.kavora.KavoraMod;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Skips terrain draws inside their render passes, retaining extraction, culling and scheduling. */
@Mixin(ChunkSectionsToRender.class)
public abstract class TerrainDrawMixin {
    @Inject(method = {"renderGroup", "renderOit"}, at = @At("HEAD"), cancellable = true)
    private void kavora$skipReplacedTerrain(CallbackInfo ci) {
        if (KavoraMod.isRemixWorldRendering()) ci.cancel();
    }
}
