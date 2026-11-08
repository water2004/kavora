/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.util.Optional;
import java.util.OptionalDouble;

/** Composites the runtime's premultiplied menu layer after Minecraft's screens and HUD. */
public final class NativeMenuOverlay implements AutoCloseable {
    private CompiledRenderPipeline pipeline;
    private GpuSampler sampler;

    public void render(GpuTextureView overlay) {
        if (overlay == null) return;
        ensurePipeline();
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        try (var pass = encoder.createRenderPass(() -> "Remix native menus",
                Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTextureView(), Optional.empty())) {
            pass.setPipeline(pipeline);
            pass.setUniform("UiOverlay", overlay, sampler);
            pass.draw(3, 1, 0, 0);
        }
    }

    private void ensurePipeline() {
        if (pipeline != null) return;
        Identifier id = Identifier.fromNamespaceAndPath("rtx-remix-renderer", "native_menu_overlay");
        var layout = BindGroupLayout.builder().withUniform("UiOverlay", UniformType.COMBINED_IMAGE_SAMPLER).build();
        var description = RenderPipeline.builder().withLocation(id).withVertexShader(id).withFragmentShader(id)
                .withBindGroupLayout(layout).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false)
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA))
                .withDepthStencilState(Optional.empty()).build();
        try (var source = new Source()) {
            pipeline = RenderSystem.getDevice().compilePipeline(description, source, Runnable::run).join().finishCompile();
        }
        sampler = RenderSystem.getDevice().createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty());
    }

    @Override
    public void close() {
        if (pipeline != null) { pipeline.close(); pipeline = null; }
        if (sampler != null) { sampler.close(); sampler = null; }
    }

    private static final class Source implements ShaderSource {
        @Override
        public String getShader(Identifier id, ShaderType stage) {
            if (stage == ShaderType.VERTEX) return """
                    #version 330
                    #extension GL_ARB_separate_shader_objects : require
                    void main() {
                        vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
                        gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
                    }
                    """;
            return """
                    #version 330
                    #extension GL_ARB_separate_shader_objects : require
                    uniform sampler2D UiOverlay;
                    layout(location=0) out vec4 fragColor;
                    void main() {
                        fragColor = texelFetch(UiOverlay, ivec2(gl_FragCoord.xy), 0);
                    }
                    """;
        }

        @Override
        public CachedIncludeSource getInclude(Identifier id) {
            throw new IllegalArgumentException("Unexpected native menu shader include " + id);
        }

        @Override
        public void close() { /* Source text owns no native resources. */ }
    }
}
