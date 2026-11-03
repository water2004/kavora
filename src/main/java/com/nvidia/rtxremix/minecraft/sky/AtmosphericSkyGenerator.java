/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import com.nvidia.rtxremix.minecraft.light.LightManager.SkyLightState;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.lwjgl.BufferUtils;

/** Vulkan compute atmosphere with a transmittance LUT and asynchronous readback ring. */
final class AtmosphericSkyGenerator {
    static final int WIDTH = 256;
    static final int HEIGHT = 128;
    static final int UPPER_HEIGHT = HEIGHT / 2;
    static final int OUTPUT_BYTES = WIDTH * HEIGHT * 8;
    static final int READBACK_BYTES = WIDTH * (HEIGHT + 1) * 8;
    static final int LUT_WIDTH = 1024;
    static final int LUT_HEIGHT = 256;
    private static final String SHADER_RESOURCE = "/assets/rtx-remix-renderer/shaders/sky_atmosphere.comp";
    private final float[] sunDirection = new float[3];
    private final float[] moonDirection = new float[3];
    private final ByteBuffer skyParameters = BufferUtils.createByteBuffer(80);
    private final ByteBuffer publishedSkyParameters = BufferUtils.createByteBuffer(80);
    private final ByteBuffer publishedPixels;
    private final int outputBytes;
    private final AtmosphericSkyCompute compute;
    private boolean hasPublishedSkyParameters;

    AtmosphericSkyGenerator() { this(WIDTH, HEIGHT); }

    AtmosphericSkyGenerator(int width, int height) {
        if (width < 8 || height < 4 || (height & 1) != 0) {
            throw new IllegalArgumentException("Sky resolution requires width >= 8 and an even height >= 4");
        }
        outputBytes = width * height * 8;
        publishedPixels = BufferUtils.createByteBuffer(outputBytes);
        String source = loadShaderSource(width, height);
        compute = new AtmosphericSkyVulkanCompute(width, height, source);
    }

    ByteBuffer generate(SkyLightState lightState, float[] upperMean,
            float mieScatteringAnisotropy, boolean publishedOutputValid) {
        if (upperMean.length < 3) throw new IllegalArgumentException("Upper-mean output requires three floats");
        if (!Float.isFinite(mieScatteringAnisotropy) || mieScatteringAnisotropy < 0 || mieScatteringAnisotropy >= 1) {
            throw new IllegalArgumentException("Mie scattering anisotropy must be in [0, 1)");
        }
        setSkyTextureDirection(sunDirection, lightState.sunPosition());
        setSkyTextureDirection(moonDirection, lightState.moonPosition());
        normalize(sunDirection);
        normalize(moonDirection);
        boolean changed = fillSkyParameters(lightState, mieScatteringAnisotropy);
        ByteBuffer completed = compute.consume();
        if (completed != null) {
            upperMean[0] = halfToFloat(completed.getShort(outputBytes));
            upperMean[1] = halfToFloat(completed.getShort(outputBytes + 2));
            upperMean[2] = halfToFloat(completed.getShort(outputBytes + 4));
            completed.limit(outputBytes);
            publishedPixels.clear().put(completed).flip();
        }
        if ((changed || !publishedOutputValid) && compute.enqueue(skyParameters)) markSkyParametersDispatched();
        return completed == null ? null : publishedPixels;
    }

    void readTransmittanceLut(ByteBuffer destination) {
        if (destination.capacity() < LUT_WIDTH * LUT_HEIGHT * 16) {
            throw new IllegalArgumentException("LUT readback buffer is too small");
        }
        compute.readLut(destination);
    }

    void destroy() { compute.close(); }

    private boolean fillSkyParameters(
            SkyLightState lightState,
            float mieScatteringAnisotropy) {
        putVectorAndScalar(
                0,
                sunDirection,
                lightState.sunAngularRadius());
        putVectorAndScalar(
                16,
                moonDirection,
                lightState.moonAngularRadius());
        putVectorAndScalar(
                32,
                lightState.sunSourceRadiance(),
                atmosphereScatteringScale(
                        lightState.sunPosition()[1]));
        putVectorAndScalar(
                48,
                lightState.moonSourceRadiance(),
                0.0f);
        skyParameters.putFloat(64, mieScatteringAnisotropy);
        skyParameters.putFloat(68, 0.0f);
        skyParameters.putFloat(72, 0.0f);
        skyParameters.putFloat(76, 0.0f);

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

    private static float atmosphereScatteringScale(float sunHeight) {
        float nightFactor =
                1.0f - smoothstep(-0.15f, 0.05f, sunHeight);
        return 16.0f * (1.0f - nightFactor) + 70.0f * nightFactor;
    }

    private static float smoothstep(
            float edge0,
            float edge1,
            float value) {
        float amount = Math.max(
                0.0f,
                Math.min(1.0f, (value - edge0) / (edge1 - edge0)));
        return amount * amount * (3.0f - 2.0f * amount);
    }

    private static int divideRoundUp(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private static float halfToFloat(short value) {
        int bits = value & 0xffff;
        int sign = (bits & 0x8000) << 16;
        int exponent = (bits >>> 10) & 0x1f;
        int mantissa = bits & 0x03ff;
        if (exponent == 0) {
            return Float.intBitsToFloat(sign);
        }
        if (exponent == 0x1f) {
            return Float.intBitsToFloat(
                    sign | 0x7f800000 | (mantissa << 13));
        }
        return Float.intBitsToFloat(
                sign | ((exponent + 112) << 23) | (mantissa << 13));
    }

}
