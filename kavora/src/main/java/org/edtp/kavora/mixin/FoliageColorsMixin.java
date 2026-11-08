/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import org.edtp.kavora.terrain.BiomeColorGeneration;
import net.minecraft.world.level.FoliageColor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The foliage colormap reloader replaces the static map through this setter. Every biome
 * colour {@link BiomeMixin} cached was derived from the old map, so the generation moves on
 * and the next lookup recomputes.
 */
@Mixin(FoliageColor.class)
public abstract class FoliageColorsMixin {
    @Inject(method = "init", at = @At("RETURN"))
    private static void kavora$onColormapReplaced(int[] map, CallbackInfo ci) {
        BiomeColorGeneration.invalidate();
    }
}
