/* SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.sky;

import com.mojang.blaze3d.platform.NativeImage;
import org.edtp.kavora.sdk.LightInfo;
import org.edtp.kavora.sdk.LightInfoDomeEXT;
import org.edtp.kavora.sdk.RemixAPI;
import org.edtp.kavora.sdk.TextureFormat;
import org.edtp.kavora.sdk.TexturePurpose;
import org.edtp.kavora.asset.KavoraLight;
import org.edtp.kavora.asset.KavoraLightStore;
import org.edtp.kavora.asset.KavoraTexture;
import org.edtp.kavora.asset.KavoraTextureStore;
import org.edtp.kavora.light.LightManager;
import org.edtp.kavora.light.LightManager.SkyLightState;
import org.edtp.kavora.util.DirectBufferPool;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/** Resource-pack celestial images, composed behind the air and weather without duplicating analytic lighting. */
final class CelestialSky implements AutoCloseable {
    private Body sun;
    private Body moon;

    void draw(RemixAPI api, SkyLightState light) {
        if (sun == null) {
            try {
                sun = new Body(Identifier.withDefaultNamespace("textures/environment/celestial/sun.png"), .3f, false);
                moon = new Body(Identifier.fromNamespaceAndPath("kavora", "textures/environment/moon_rgbh.png"), .05f, true);
            } catch (IOException | RuntimeException error) {
                close();
                if (error instanceof IOException io) throw new UncheckedIOException(io);
                throw (RuntimeException) error;
            }
        }
        float[] solar = light.sunSourceRadiance();
        sun.draw(api, light.sunPosition(), SkyLightState.irradiance(solar[0]),
                SkyLightState.irradiance(solar[1]), SkyLightState.irradiance(solar[2]), 1f);
        float lunar = .2f * LightManager.getMoonIntensity();
        moon.draw(api, light.moonPosition(), lunar, lunar, lunar, SkyRenderer.getMoonSize());
    }

    @Override public void close() {
        if (sun != null) sun.close();
        if (moon != null) moon.close();
        sun = null;
        moon = null;
    }

    private static NativeImage read(Identifier path) throws IOException {
        var resource = Minecraft.getInstance().getResourceManager().getResource(path)
                .orElseThrow(() -> new IOException("Missing celestial texture " + path));
        try (var stream = resource.open()) {
            return NativeImage.read(stream);
        }
    }

    private static final class Body implements AutoCloseable {
        private KavoraTexture texture;
        private final float projectionWidth;
        private final float emissionScale;
        private final LightInfoDomeEXT dome = new LightInfoDomeEXT();
        private final LightInfo info = LightInfo.builder().domeExt(dome).build();
        private KavoraLight light;

        Body(Identifier path, float projectionWidth, boolean lunar) throws IOException {
            this.projectionWidth = projectionWidth;
            dome.projection = lunar ? 2 : 1;
            dome.backgroundOnly = true;
            // Analytic distant lights supply illumination independently of the visible surface.
            dome.blendType = 6;
            try (NativeImage image = read(path)) {
                int width = image.getWidth(), height = image.getHeight();
                var pixels = DirectBufferPool.acquire(width * height * (lunar ? 4 : 8));
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int argb = image.getPixel(x, y);
                        if (lunar) {
                            // Lunar alpha is elevation, not opacity.
                            pixels.put((byte) (argb >>> 16)).put((byte) (argb >>> 8))
                                    .put((byte) argb).put((byte) (argb >>> 24));
                            continue;
                        }
                        float alpha = (argb >>> 24) / 255f;
                        pixels.putShort(Float.floatToFloat16(linear((argb >>> 16) & 255) * alpha));
                        pixels.putShort(Float.floatToFloat16(linear((argb >>> 8) & 255) * alpha));
                        pixels.putShort(Float.floatToFloat16(linear(argb & 255) * alpha));
                        pixels.putShort(Float.floatToFloat16(alpha));
                    }
                }
                pixels.flip();
                emissionScale = lunar ? 1f : prepareSolarEmission(pixels, width, height, projectionWidth);
                texture = KavoraTextureStore.get().create(new KavoraTexture(width, height,
                        lunar ? TextureFormat.RGBA8_UNORM : TextureFormat.RGBA16_SFLOAT,
                        lunar ? TexturePurpose.DATA : TexturePurpose.COLOR, pixels, true));
            } catch (IOException | RuntimeException error) {
                close();
                throw error;
            }
        }

        void draw(RemixAPI api, float[] direction, float red, float green, float blue, float size) {
            float x = direction[0], y = direction[1], z = direction[2];
            float invRight = 1 / (float) Math.sqrt(x * x + z * z);
            float rx = z * invRight, rz = -x * invRight;
            float extent = projectionWidth * size;
            boolean changed = put(0, rx * extent) | put(1, y * rz * extent) | put(2, x)
                    | put(4, 0) | put(5, (z * rx - x * rz) * extent) | put(6, y)
                    | put(8, rz * extent) | put(9, -y * rx * extent) | put(10, z);
            red *= emissionScale;
            green *= emissionScale;
            blue *= emissionScale;
            changed |= info.radiance.x != red || info.radiance.y != green || info.radiance.z != blue;
            info.radiance.x = red;
            info.radiance.y = green;
            info.radiance.z = blue;
            long handle = texture.getHandle();
            changed |= dome.colorTexture != handle;
            dome.colorTexture = handle;
            if (light == null) {
                light = KavoraLightStore.get().create(new KavoraLight(info, null));
            } else if (changed) {
                KavoraLightStore.get().update(light, info);
            }
            light.draw(api);
        }

        private boolean put(int index, float value) {
            boolean changed = dome.transform[index] != value;
            dome.transform[index] = value;
            return changed;
        }

        @Override public void close() {
            if (light != null && !light.isDeleted()) light.destroy();
            light = null;
            if (texture != null && !texture.isDeleted()) texture.destroy();
            texture = null;
        }
    }

    // A display-referred sun image contains a clipped light source and an authored glow.
    // Recover highlight headroom before normalizing the projected image to unit irradiance.
    // The dome and bloom can then use their ordinary HDR paths, including when a cloud occludes it.
    private static float prepareSolarEmission(ByteBuffer pixels, int width, int height, float projectionWidth) {
        // projectedUv() maps local x/z in [-0.5, 0.5] across the full texture width.
        double area = (double) projectionWidth * projectionWidth / ((double) width * height);
        double flux = 0;
        double peak = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = (y * width + x) * 8;
                float alpha = Float.float16ToFloat(pixels.getShort(i + 6));
                if (alpha == 0) continue;
                double luma = solarLuminance(pixels, i);
                peak = Math.max(peak, luma / alpha);
                flux += luma * projectedWeight(x, y, width, height, projectionWidth);
            }
        }
        if (peak == 0) return 0;
        // Effective projected solid angle / pi is the inverse peak-to-diffuse white ratio.
        double inverseWhite = flux * area / (Math.PI * peak);
        double hdrFlux = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = (y * width + x) * 8;
                float alpha = Float.float16ToFloat(pixels.getShort(i + 6));
                if (alpha == 0) continue;
                double luminance = solarLuminance(pixels, i);
                double relative = Math.min(1.0, luminance / (alpha * peak));
                double recovered = inverseWhite / ((1.0 - relative) + inverseWhite * relative);
                // The sprite supplies the spatial profile and its original painted colour.
                // Beyond its display white, the additional radiance has the spectrum of
                // the analytic sunlight supplied to draw(), including in camera bloom.
                double highlight = luminance * (recovered - inverseWhite);
                for (int channel = 0; channel < 6; channel += 2) {
                    float value = Float.float16ToFloat(pixels.getShort(i + channel));
                    pixels.putShort(i + channel, Float.floatToFloat16((float) (value * inverseWhite + highlight)));
                }
                hdrFlux += solarLuminance(pixels, i) * projectedWeight(x, y, width, height, projectionWidth);
            }
        }
        return (float) (1.0 / (hdrFlux * area));
    }

    private static double solarLuminance(ByteBuffer pixels, int i) {
        return .2126 * Float.float16ToFloat(pixels.getShort(i))
                + .7152 * Float.float16ToFloat(pixels.getShort(i + 2))
                + .0722 * Float.float16ToFloat(pixels.getShort(i + 4));
    }

    private static double projectedWeight(int x, int y, int width, int height, float projectionWidth) {
        double u = projectionWidth * ((x + 0.5) / width - 0.5);
        double v = projectionWidth * ((y + 0.5) / height - 0.5);
        double squaredLength = 1 + u * u + v * v;
        return 1 / (squaredLength * squaredLength);
    }

    private static float linear(int value) {
        float encoded = value / 255f;
        return encoded <= .04045f ? encoded / 12.92f : (float) Math.pow((encoded + .055f) / 1.055f, 2.4);
    }
}
