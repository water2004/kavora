/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.compat.sodium.mixin;

import com.nvidia.rtxremix.minecraft.compat.sodium.SodiumRasterSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Keeps the accepted mesh's raster ownership with its section and sorting state. */
@Mixin(RenderSection.class)
public abstract class SodiumRenderSectionMixin implements SodiumRasterSection {
    @Unique private boolean rtxremix$rasterUploadOmitted;

    @Override
    public boolean rtxremix$isRasterUploadOmitted() {
        return rtxremix$rasterUploadOmitted;
    }

    @Override
    public void rtxremix$setRasterUploadOmitted(boolean omitted) {
        rtxremix$rasterUploadOmitted = omitted;
    }
}
