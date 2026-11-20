/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.sky;

import org.edtp.kavora.light.LightManager.SkyLightState;
import org.edtp.kavora.sdk.LightInfoAtmosphereEXT;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryUtil;

/** Vulkan compute atmosphere with a transmittance LUT and asynchronous readback ring. */
final class AtmosphericSkyGenerator {
    static final int WIDTH = 512;
    static final int HEIGHT = 512;
    static final float VERTICAL_SCALE = 4.0f;
    static final int OUTPUT_BYTES = WIDTH * HEIGHT * 8;
    static final int LUT_WIDTH = 1024;
    static final int LUT_HEIGHT = 256;
    static final int SCATTERING_WIDTH = 64;
    static final int SCATTERING_HEIGHT = 64;
    private static final String SHADER_RESOURCE = "/assets/kavora/shaders/sky_atmosphere.comp";
    private final float[] sunDirection = new float[3];
    private final float[] moonDirection = new float[3];
    private final ByteBuffer skyParameters = BufferUtils.createByteBuffer(80);
    private final ByteBuffer publishedSkyParameters = BufferUtils.createByteBuffer(80);
    private final ByteBuffer completedSkyParameters = BufferUtils.createByteBuffer(80);
    private final ByteBuffer[] transportTables = new ByteBuffer[2];
    private final ByteBuffer publishedPixels;
    private final ByteBuffer publishedTransmittance;
    private final int width;
    private final int height;
    private final int outputBytes;
    private final AtmosphericSkyCompute compute;
    private boolean hasPublishedSkyParameters;
    private boolean tablesCaptured;
    private int generation;

    AtmosphericSkyGenerator() { this(WIDTH, HEIGHT); }

    AtmosphericSkyGenerator(int width, int height) {
        if (width < 8 || height < 4 || (height & 1) != 0) {
            throw new IllegalArgumentException("Sky resolution requires width >= 8 and an even height >= 4");
        }
        this.width = width;
        this.height = height;
        outputBytes = width * height * 8;
        publishedPixels = BufferUtils.createByteBuffer(outputBytes);
        publishedTransmittance = BufferUtils.createByteBuffer(height * 8);
        String source = loadShaderSource(width, height);
        compute = new AtmosphericSkyVulkanCompute(width, height, source);
        try {
            transportTables[0] = BufferUtils.createByteBuffer(LUT_WIDTH * LUT_HEIGHT * 16);
            transportTables[1] = BufferUtils.createByteBuffer(SCATTERING_WIDTH * SCATTERING_HEIGHT * 16);
            for (int index = 0; index < transportTables.length; index++) compute.readLut(index, transportTables[index]);
        } catch (RuntimeException exception) {
            compute.close();
            throw exception;
        }
    }

    ByteBuffer generate(SkyLightState lightState, float[] upperMean,
            float mieScatteringAnisotropy, float observerHeight, float seaLevelMeters, boolean publishedOutputValid) {
        if (upperMean.length < 3) throw new IllegalArgumentException("Upper-mean output requires three floats");
        if (!Float.isFinite(mieScatteringAnisotropy) || mieScatteringAnisotropy < 0 || mieScatteringAnisotropy >= 1) {
            throw new IllegalArgumentException("Mie scattering anisotropy must be in [0, 1)");
        }
        setSkyTextureDirection(sunDirection, lightState.sunPosition());
        setSkyTextureDirection(moonDirection, lightState.moonPosition());
        normalize(sunDirection);
        normalize(moonDirection);
        boolean changed = fillSkyParameters(lightState, mieScatteringAnisotropy, observerHeight, seaLevelMeters);
        ByteBuffer completed = compute.consume(completedSkyParameters);
        if (completed != null && completedSkyParameters.getInt(76) != generation) completed = null;
        if (completed != null) {
            upperMean[0] = Float.float16ToFloat(completed.getShort(outputBytes));
            upperMean[1] = Float.float16ToFloat(completed.getShort(outputBytes + 2));
            upperMean[2] = Float.float16ToFloat(completed.getShort(outputBytes + 4));
            completed.position(width * (height + 1) * 8);
            publishedTransmittance.clear().put(completed).flip();
            completed.position(0);
            completed.limit(outputBytes);
            publishedPixels.clear().put(completed).flip();
        }
        if ((changed || !publishedOutputValid) && compute.enqueue(skyParameters)) markSkyParametersDispatched();
        return completed == null ? null : publishedPixels;
    }

    /** Transmission from the same completed bake as the published radiance, one RGB sample per latitude. */
    ByteBuffer transmittance() {
        return publishedTransmittance;
    }

    /** Old-world readbacks retire normally but cannot publish into the next world. */
    void invalidate() {
        generation++;
        hasPublishedSkyParameters = false;
    }

    /** Copies the immutable tables read once after their initial Vulkan bake. */
    void copyTransportTable(int index, ByteBuffer destination) {
        destination.put(transportTables[index].duplicate()).flip();
    }

    /** Publishes the optical model and the celestial beams incident on the local air. */
    void describeAtmosphere(LightInfoAtmosphereEXT air, OvercastIllumination weather) {
        air.seaLevelMeters = completedSkyParameters.getFloat(72);
        air.innerRadius = (float) SkyAtmosphere.INNER_RADIUS;
        air.lowerDepth = (float) SkyAtmosphere.LOWER_DEPTH;
        air.atmosphereHeight = (float) SkyAtmosphere.ATMOSPHERE_HEIGHT;
        air.rayleighExtinction.x = (float) SkyAtmosphere.RAYLEIGH_R;
        air.rayleighExtinction.y = (float) SkyAtmosphere.RAYLEIGH_G;
        air.rayleighExtinction.z = (float) SkyAtmosphere.RAYLEIGH_B;
        air.rayleighHeight = (float) SkyAtmosphere.RAYLEIGH_HEIGHT;
        air.mieExtinction = (float) SkyAtmosphere.MIE_EXTINCTION;
        air.mieHeight = (float) SkyAtmosphere.MIE_HEIGHT;
        air.mieAlbedo = (float) SkyAtmosphere.MIE_ALBEDO;
        air.mieAnisotropy = completedSkyParameters.getFloat(64);
        for (int light = 0; light < 2; light++) {
            int start = light * 4;
            float beam = light == 0 ? weather.sunBeam() : weather.moonBeam();
            for (int channel = 0; channel < 4; channel++) {
                float direction = completedSkyParameters.getFloat(light * 16 + channel * 4);
                air.lightDirections[start + channel] = channel == 0 ? -direction : direction;
                air.lightIrradiances[start + channel] = beam * completedSkyParameters.getFloat(32 + light * 16 + channel * 4);
            }
        }
    }

    /** Explicit diagnostics reuse the immutable tables used by native finite-ray transport. */
    void snapshotTransport(Path directory) throws IOException {
        if (tablesCaptured) return;
        java.nio.file.Files.writeString(directory.resolve("sky-projection.json"),
                "{\"width\":" + width + ",\"height\":" + height
                        + ",\"verticalScale\":" + VERTICAL_SCALE + "}\n");
        String[] names = {"transmittance.rgba32f", "multiple-scattering.rgba32f"};
        int[] lengths = {LUT_WIDTH * LUT_HEIGHT * 16, SCATTERING_WIDTH * SCATTERING_HEIGHT * 16};
        ByteBuffer pixels = MemoryUtil.memAlloc(lengths[0]);
        try {
            for (int index = 0; index < names.length; index++) {
                pixels.clear().limit(lengths[index]);
                copyTransportTable(index, pixels);
                try (var channel = java.nio.channels.FileChannel.open(directory.resolve(names[index]),
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                        java.nio.file.StandardOpenOption.WRITE)) {
                    while (pixels.hasRemaining()) channel.write(pixels);
                }
            }
        } finally {
            MemoryUtil.memFree(pixels);
        }
        tablesCaptured = true;
    }

    void destroy() { compute.close(); }

    private boolean fillSkyParameters(
            SkyLightState lightState,
            float mieScatteringAnisotropy, float observerHeight, float seaLevelMeters) {
        putVectorAndScalar(
                0,
                sunDirection,
                lightState.sunAngularRadius());
        putVectorAndScalar(
                16,
                moonDirection,
                lightState.moonAngularRadius());
        putIrradiance(32, lightState.sunSourceRadiance());
        putIrradiance(48, lightState.moonSourceRadiance());
        skyParameters.putFloat(64, mieScatteringAnisotropy);
        skyParameters.putFloat(68, SkyAtmosphere.observerHeight(observerHeight));
        skyParameters.putFloat(72, seaLevelMeters);
        skyParameters.putInt(76, generation);

        return !hasPublishedSkyParameters || skyParameters.mismatch(publishedSkyParameters) >= 0;
    }

    /** Records the block the dispatch just ran on, so the next identical block can skip it. */
    private void markSkyParametersDispatched() {
        publishedSkyParameters.clear();
        publishedSkyParameters.put(skyParameters.duplicate());
        publishedSkyParameters.clear();
        hasPublishedSkyParameters = true;
    }

    private void putVectorAndScalar(
            int offset,
            float[] vector,
            float scalar) {
        skyParameters.putFloat(offset, vector[0]);
        skyParameters.putFloat(offset + Float.BYTES, vector[1]);
        skyParameters.putFloat(offset + 2 * Float.BYTES, vector[2]);
        skyParameters.putFloat(offset + 3 * Float.BYTES, scalar);
    }

    private void putIrradiance(int offset, float[] source) {
        skyParameters.putFloat(offset, SkyLightState.irradiance(source[0]));
        skyParameters.putFloat(offset + 4, SkyLightState.irradiance(source[1]));
        skyParameters.putFloat(offset + 8, SkyLightState.irradiance(source[2]));
        skyParameters.putFloat(offset + 12, 0.0f);
    }

    int outputBytes() {
        return outputBytes;
    }

    private static String loadShaderSource(int width, int height) {
        try (InputStream stream =
                AtmosphericSkyGenerator.class.getResourceAsStream(
                        SHADER_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException(
                        "Missing atmospheric sky shader " + SHADER_RESOURCE);
            }
            String source = new String(
                    stream.readAllBytes(),
                    StandardCharsets.UTF_8);
            return withSkyDimensions(source, width, height);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to read atmospheric sky shader "
                            + SHADER_RESOURCE,
                    e);
        }
    }

    /**
     * Inserts the size defines directly below the shader's {@code #version} directive.
     * GLSL requires that directive to precede every statement, and the file opens with
     * its licence header, so the defines cannot simply follow the first line.
     */
    static String withSkyDimensions(String source, int width, int height) {
        int versionStart = source.indexOf("#version");
        if (versionStart < 0) {
            throw new IllegalStateException(
                    "Atmospheric sky shader has no #version directive");
        }
        int versionEnd = source.indexOf('\n', versionStart) + 1;
        return source.substring(0, versionEnd)
                + "#define SKY_WIDTH " + width + "\n"
                + "#define SKY_HEIGHT " + height + "\n"
                + "#define SKY_VERTICAL_SCALE " + VERTICAL_SCALE + "\n"
                + SkyAtmosphere.shaderDefinitions()
                + source.substring(versionEnd);
    }

    private static void setSkyTextureDirection(
            float[] output,
            float[] direction) {
        output[0] = -direction[0];
        output[1] = direction[1];
        output[2] = direction[2];
    }

    private static void normalize(float[] vector) {
        float length = (float) Math.sqrt(
                vector[0] * vector[0]
                        + vector[1] * vector[1]
                        + vector[2] * vector[2]);
        if (length > 0.0f) {
            vector[0] /= length;
            vector[1] /= length;
            vector[2] /= length;
        }
    }

}
