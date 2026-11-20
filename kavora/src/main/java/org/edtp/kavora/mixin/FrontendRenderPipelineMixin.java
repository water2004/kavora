/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import org.edtp.kavora.vulkan.CompositionPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = FrontendRenderPipeline.class, remap = false)
abstract class FrontendRenderPipelineMixin implements CompositionPipeline.Access {
    @Unique private RenderPipeline kavora$compositionSource;
    @Unique private CompositionPipeline[] kavora$descriptors;
    @Unique private CompiledRenderPipeline[] kavora$variants;

    @Override
    public void kavora$setCompositionSource(RenderPipeline source) {
        kavora$compositionSource = source;
    }

    @Override
    public CompiledRenderPipeline kavora$compositionVariant(GpuFormat format, boolean coverage) {
        int index = coverage ? (format == GpuFormat.RGBA16_FLOAT ? 2 : 1) : 0;
        if (kavora$variants == null) {
            kavora$descriptors = new CompositionPipeline[3];
            kavora$variants = new CompiledRenderPipeline[3];
        }
        if (kavora$descriptors[index] == null) {
            kavora$descriptors[index] = new CompositionPipeline(kavora$compositionSource, format, coverage);
        }
        if (kavora$variants[index] == null || kavora$variants[index].isClosed()) {
            kavora$variants[index] = RenderSystem.getCompiledPipeline(kavora$descriptors[index]);
        }
        return kavora$variants[index];
    }
}
