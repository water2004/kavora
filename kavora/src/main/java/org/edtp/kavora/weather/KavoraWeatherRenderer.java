/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.weather;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.edtp.kavora.mixin.WeatherEffectRendererAccessor;
import org.edtp.kavora.render.KavoraInterop;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;

/** Draws vanilla precipitation over the Remix image with depth occlusion and refractive rain. */
public final class KavoraWeatherRenderer {
    private static final Identifier RAIN = Identifier.withDefaultNamespace("textures/environment/rain.png");
    private static final Identifier SNOW = Identifier.withDefaultNamespace("textures/environment/snow.png");
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f modelView = new Matrix4f();
    private final ByteBuffer matrices = BufferUtils.createByteBuffer(144);
    private CompiledRenderPipeline rainPipeline;
    private CompiledRenderPipeline snowPipeline;
    private GpuFormat pipelineFormat;
    private int lastDrawCount;
    private int lastQuadCount;

    public void updateCamera(Camera camera, float fovYDegrees, float aspect) {
        modelView.rotation(camera.rotation().conjugate(new org.joml.Quaternionf()));
        projection.setPerspective((float) Math.toRadians(fovYDegrees), aspect, 0.05f, 1000.0f, RenderSystem.getDevice().getDeviceInfo().isZZeroToOne());
    }

    public void render(KavoraInterop interop) {
        lastDrawCount = 0;
        lastQuadCount = 0;
        var client = Minecraft.getInstance();
        var level = client.gameRenderer.gameRenderState().levelRenderState;
        var weather = level.weatherRenderState;
        if (weather.rainColumns.isEmpty() && weather.snowColumns.isEmpty()) return;
        GpuTextureView finalColor = interop.colorTexture();
        GpuTextureView depth = interop.depthTexture();
        if (finalColor == null || depth == null) throw new IllegalStateException("Remix precipitation requires color and depth textures");
        var target = client.gameRenderer.mainRenderTarget().getColorTextureView();
        ensurePipelines(target.texture().getFormat());
        modelView.get(0, matrices);
        projection.get(64, matrices);
        matrices.putFloat(128, 1.0f / target.texture().getWidth(0));
        matrices.putFloat(132, 1.0f / target.texture().getHeight(0));
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        var uniform = encoder.transientMemory().uploadGpu(matrices, 16, GpuBuffer.USAGE_UNIFORM);
        draw(weather.rainColumns, true, weather.radius, weather.intensity, target, finalColor, depth, uniform);
        draw(weather.snowColumns, false, weather.radius, weather.intensity, target, finalColor, depth, uniform);
    }

    private void draw(List<WeatherEffectRenderer.ColumnInstance> columns, boolean rain, int radius,
            float intensity, GpuTextureView target, GpuTextureView finalColor, GpuTextureView depth,
            com.mojang.renderpearl.api.buffers.GpuBufferSlice uniform) {
        if (columns.isEmpty()) return;
        var client = Minecraft.getInstance();
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        int byteCount = columns.size() * 4 * DefaultVertexFormat.PARTICLE.getVertexSize();
        try (var storage = ByteBufferBuilder.exactlySized(byteCount)) {
            var builder = new BufferBuilder(storage, PrimitiveTopology.QUADS, DefaultVertexFormat.PARTICLE);
            ((WeatherEffectRendererAccessor) client.levelRenderer.weatherEffectRenderer()).kavora$prepareInstances(
                    builder, columns, client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos,
                    rain ? 1 : 0.8f, radius, intensity);
            try (MeshData mesh = builder.buildOrThrow()) {
                var vertices = encoder.transientMemory().uploadGpu(mesh.vertexBuffer(), 4, GpuBuffer.USAGE_VERTEX);
                var indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
                var indexBuffer = indices.getBuffer(mesh.drawState().indexCount());
                var texture = client.getTextureManager().getTexture(rain ? RAIN : SNOW);
                try (var pass = encoder.createRenderPass(() -> "Remix precipitation", target, Optional.empty())) {
                    pass.setPipeline(rain ? rainPipeline : snowPipeline);
                    pass.setUniform("WeatherMatrices", uniform);
                    pass.setUniform("Sampler0", texture.getTextureView(), texture.getSampler());
                    pass.setUniform("Sampler1", finalColor, texture.getSampler());
                    pass.setUniform("RemixDepth", depth, texture.getSampler());
                    pass.setUniform("Lightmap", client.gameRenderer.levelLightmap(), texture.getSampler());
                    pass.setVertexBuffer(0, vertices);
                    pass.setIndexBuffer(indexBuffer, indices.type());
                    pass.drawIndexed(mesh.drawState().indexCount(), 1, 0, 0, 0);
                }
                lastDrawCount++;
                lastQuadCount += mesh.drawState().vertexCount() / 4;
            }
        }
    }

    private void ensurePipelines(GpuFormat format) {
        if (pipelineFormat == format) return;
        closePipelines();
        rainPipeline = compile(true, format);
        snowPipeline = compile(false, format);
        pipelineFormat = format;
    }

    private static CompiledRenderPipeline compile(boolean rain, GpuFormat format) {
        Identifier location = Identifier.fromNamespaceAndPath("kavora", rain ? "rain_refraction" : "snow_composite");
        var layout = BindGroupLayout.builder().withUniform("WeatherMatrices", UniformType.UNIFORM_BUFFER)
                .withUniform("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("Sampler1", UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("RemixDepth", UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("Lightmap", UniformType.COMBINED_IMAGE_SAMPLER).build();
        var blend = rain ? Optional.<BlendFunction>empty() : Optional.of(BlendFunction.TRANSLUCENT);
        var colorTarget = new ColorTargetState(blend, format, ColorTargetState.DEFAULT.writeMask());
        var pipeline = RenderPipeline.builder().withLocation(location).withVertexShader(location).withFragmentShader(location)
                .withBindGroupLayout(layout).withVertexBinding(0, DefaultVertexFormat.PARTICLE)
                .withPrimitiveTopology(PrimitiveTopology.QUADS).withCull(false)
                .withColorTargetState(colorTarget)
                .withDepthStencilState(Optional.empty()).build();
        try (var source = new WeatherShaderSource(rain)) {
            return RenderSystem.getDevice().compilePipeline(pipeline, source, Runnable::run).join().finishCompile();
        }
    }

    /** Projection used by native Remix's parameterized camera and the precipitation pass. */
    public org.joml.Matrix4fc projectionMatrix() { return projection; }

    public int getLastDrawCount() { return lastDrawCount; }
    public int getLastQuadCount() { return lastQuadCount; }

    public void cleanup() {
        closePipelines();
        lastDrawCount = 0;
        lastQuadCount = 0;
    }

    private void closePipelines() {
        if (rainPipeline != null) { rainPipeline.close(); rainPipeline = null; }
        if (snowPipeline != null) { snowPipeline.close(); snowPipeline = null; }
        pipelineFormat = null;
    }

    private static final class WeatherShaderSource implements ShaderSource {
        private final boolean rain;
        WeatherShaderSource(boolean rain) { this.rain = rain; }
        @Override public String getShader(Identifier id, ShaderType type) {
            if (type == ShaderType.VERTEX) return """
                    #version 330
                    #extension GL_ARB_separate_shader_objects : require
                    layout(location=0) in vec3 Position;
                    layout(location=1) in vec2 UV0;
                    layout(location=2) in vec4 Color;
                    layout(location=3) in ivec2 UV2;
                    layout(std140) uniform WeatherMatrices { mat4 ModelViewMat; mat4 ProjMat; vec2 InverseViewportSize; };
                    uniform sampler2D Lightmap;
                    layout(location=0) out vec2 texCoord;
                    layout(location=1) out vec4 vertexColor;
                    layout(location=2) out vec3 viewPosition;
                    void main() {
                        viewPosition=(ModelViewMat*vec4(Position,1)).xyz;
                        gl_Position=ProjMat*vec4(viewPosition,1);
                        texCoord=UV0;
                        vertexColor=Color;
                    }
                    """.replace("vertexColor=Color;", rain ? "vertexColor=Color;"
                            : "vertexColor=Color*texture(Lightmap,clamp(vec2(UV2)/256.0+0.5/16.0,vec2(0.5/16.0),vec2(15.5/16.0)));");
            if (!rain) return """
                    #version 330
                    #extension GL_ARB_separate_shader_objects : require
                    uniform sampler2D Sampler0;
                    uniform sampler2D RemixDepth;
                    layout(std140) uniform WeatherMatrices { mat4 ModelViewMat; mat4 ProjMat; vec2 InverseViewportSize; };
                    layout(location=0) in vec2 texCoord;
                    layout(location=1) in vec4 vertexColor;
                    layout(location=2) in vec3 viewPosition;
                    layout(location=0) out vec4 fragColor;
                    void main() {
                        vec2 screenUv=gl_FragCoord.xy*InverseViewportSize;
                        ivec2 depthSize=textureSize(RemixDepth,0);
                        ivec2 depthPixel=clamp(ivec2(screenUv*vec2(depthSize)),ivec2(0),depthSize-1);
                        if (gl_FragCoord.z>texelFetch(RemixDepth,depthPixel,0).r) discard;
                        fragColor=texture(Sampler0,texCoord)*vertexColor;
                        if (fragColor.a<0.1) discard;
                    }
                    """;
            return resource("/assets/kavora/shaders/core/rain_refraction.fsh");
        }
        @Override public CachedIncludeSource getInclude(Identifier id) {
            throw new IllegalArgumentException("Unexpected weather shader include " + id);
        }
        @Override public void close() { /* Shader sources retain no native resources. */ }
        private static String resource(String path) {
            try (var stream = KavoraWeatherRenderer.class.getResourceAsStream(path)) {
                if (stream == null) throw new IllegalStateException("Missing shader " + path);
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) { throw new IllegalStateException("Cannot read shader " + path, e); }
        }
    }
}
