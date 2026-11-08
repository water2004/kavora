/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.compat.sodium.mixin;

import org.edtp.kavora.compat.sodium.SodiumRasterSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Keeps the accepted mesh's raster ownership with its section and sorting state. */
@Mixin(RenderSection.class)
public abstract class SodiumRenderSectionMixin implements SodiumRasterSection {
    @Unique private boolean kavora$rasterUploadOmitted;

    @Override
    public boolean kavora$isRasterUploadOmitted() {
        return kavora$rasterUploadOmitted;
    }

    @Override
    public void kavora$setRasterUploadOmitted(boolean omitted) {
        kavora$rasterUploadOmitted = omitted;
    }
}
