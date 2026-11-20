/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.frontend.shaders.PipelineBuilder;
import org.edtp.kavora.vulkan.CompositionPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = PipelineBuilder.class, remap = false)
abstract class PipelineBuilderMixin {
    @ModifyReturnValue(method = "lambda$compilePipeline$1", at = @At("RETURN"))
    private static CompiledRenderPipeline kavora$rememberCompositionSource(CompiledRenderPipeline compiled,
            @Local(argsOnly = true) RenderPipeline source) {
        if (compiled instanceof CompositionPipeline.Access access) {
            access.kavora$setCompositionSource(source);
        }
        return compiled;
    }
}
