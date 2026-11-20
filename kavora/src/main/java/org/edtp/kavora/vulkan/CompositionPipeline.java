/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.vulkan;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;

import java.util.Optional;

/** Single-color presentation variants preserve the host's RGB blend. */
public final class CompositionPipeline extends RenderPipeline {
    public interface Access {
        void kavora$setCompositionSource(RenderPipeline source);
        CompiledRenderPipeline kavora$compositionVariant(GpuFormat format, boolean coverage);
    }

    public CompositionPipeline(RenderPipeline source, GpuFormat format, boolean coverage) {
        super(source.getLocation(), source.getShaders(), source.getShaderDefines(), source.getBindGroupLayouts(),
                new ColorTargetState[] {colorTarget(source.getColorTargetStates().getFirst(), format, coverage)}, source.getDepthStencilState(),
                source.getPolygonMode(), source.isCull(), source.getVertexFormatBindings().toArray(VertexFormat[]::new),
                source.getPrimitiveTopology(), source.pushConstantSize(), source.getSortKey());
    }

    public static boolean needsCoverageBlend(ColorTargetState original) {
        if ((original.writeMask() & ColorTargetState.WRITE_COLOR) == 0) return original.writeAlpha();
        return !original.writeAlpha() || original.blendFunction().isPresent()
                && !original.blendFunction().get().alpha().equals(BlendFunction.TRANSLUCENT.alpha());
    }

    private static ColorTargetState colorTarget(ColorTargetState original, GpuFormat format, boolean coverage) {
        int mask = original.writeMask();
        Optional<BlendFunction> blend = original.blendFunction();
        if (coverage) {
            if ((mask & ColorTargetState.WRITE_COLOR) == 0) {
                mask = ColorTargetState.WRITE_NONE;
            } else {
                mask |= ColorTargetState.WRITE_ALPHA;
                blend = blend.map(b -> new BlendFunction(b.color(), BlendFunction.TRANSLUCENT.alpha()));
            }
        }
        return new ColorTargetState(blend, format, mask);
    }
}
