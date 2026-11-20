/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import java.util.List;
import java.util.Optional;
import org.edtp.kavora.vulkan.CompositionPipeline;
import org.edtp.kavora.vulkan.NativeVulkanSurface;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value = FrontendRenderPass.class, remap = false)
abstract class FrontendRenderPassMixin {
    @Shadow @Final private List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colorAttachments;

    @ModifyVariable(method = "setPipeline", at = @At("HEAD"), argsOnly = true)
    private CompiledRenderPipeline kavora$matchCompositionFormat(CompiledRenderPipeline compiled) {
        if (colorAttachments.size() != 1 || !(compiled instanceof FrontendRenderPipeline pipeline)) return compiled;
        var attachment = colorAttachments.getFirst();
        var targets = pipeline.colorTargetStates();
        if (attachment == null || targets.size() != 1 || targets.getFirst() == null) return compiled;
        var texture = attachment.textureView().texture();
        var target = targets.getFirst();
        var format = texture.getFormat();
        boolean retarget = format == GpuFormat.RGBA16_FLOAT && target.format() == GpuFormat.RGBA8_UNORM;
        boolean coverage = NativeVulkanSurface.recordsUiCoverage(texture) && CompositionPipeline.needsCoverageBlend(target);
        if (retarget || coverage) {
            return ((CompositionPipeline.Access) compiled).kavora$compositionVariant(format, coverage);
        }
        return compiled;
    }
}
