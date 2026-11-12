/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.terrain;

import org.edtp.kavora.render.RenderOrigin;
import org.edtp.kavora.asset.KavoraLight;
import org.edtp.kavora.light.LightEntry;
import org.edtp.kavora.texture.BlockTextureManager;
import org.edtp.kavora.asset.KavoraLightStore;
import org.edtp.kavora.light.BlockLightIntensityControls;
import org.edtp.kavora.light.BlockLightRuleManager;
import org.edtp.kavora.sdk.Float3D;
import org.edtp.kavora.sdk.LightInfo;
import org.edtp.kavora.sdk.LightInfoCylinderEXT;
import org.edtp.kavora.sdk.LightInfoDiskEXT;
import org.edtp.kavora.sdk.LightInfoLightShaping;
import org.edtp.kavora.sdk.LightInfoRectEXT;
import org.edtp.kavora.sdk.LightInfoSphereEXT;
import org.edtp.kavora.light.LightPower;

/**
 * One terrain block light as the draw loop sees it: the native handle plus
 * the position and power the reach cull needs, which the {@link KavoraLight}
 * itself no longer holds after creation.
 *
 * <p>Render-thread only. The drawn-frame stamp is written only when the light
 * is visited, so a light skipped wholesale by the column or section gates
 * needs no per-light write and simply reads as not drawn.
 */
public final class TerrainLight {

    private static final double FOUR_PI = 4.0 * Math.PI;
    /**
     * A light drawn last frame stays drawn until its weight falls to 1/1.5 of
     * the threshold (about 1.22x the reach in distance), so a light sitting on
     * the boundary does not flicker.
     */
    private static final float HYSTERESIS = 1.5f;

    public final KavoraLight light;
    private double x, z;
    private float y;
    private float power;
    private long lastDrawnFrame = Long.MIN_VALUE;

    public TerrainLight(KavoraLight light) {
        this.light = light;
    }

    /** Constructs the same analytic emitter for either terrain distance. */
    public static TerrainLight create(LightEntry entry, BlockTextureManager textures) {
        BlockLightRadiance radiance = blockLightRadiance(entry, textures);
        KavoraLight light = new KavoraLight(buildBlockLightInfo(entry, radiance), null);
        KavoraLightStore.get().create(light);
        TerrainLight record = new TerrainLight(light);
        record.place(entry.x(), entry.y(), entry.z(),
                LightPower.of(entry, radiance.r(), radiance.g(), radiance.b()));
        return record;
    }

    void update(LightEntry entry, BlockTextureManager textures) {
        BlockLightRadiance radiance = blockLightRadiance(entry, textures);
        KavoraLightStore.get().update(light, buildBlockLightInfo(entry, radiance));
        place(entry.x(), entry.y(), entry.z(), LightPower.of(entry, radiance.r(), radiance.g(), radiance.b()));
    }

    public static float emittedPower(LightEntry entry, BlockTextureManager textures) {
        BlockLightRadiance radiance = blockLightRadiance(entry, textures);
        return LightPower.of(entry, radiance.r(), radiance.g(), radiance.b());
    }

    public static double reach(float power, float threshold) {
        return Math.sqrt(HYSTERESIS * power / (FOUR_PI * threshold));
    }

    /** Records where the light is and how much it emits; called at create and update. */
    public void place(double x, float y, double z, float power) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.power = power;
    }

    public float power() {
        return power;
    }

    public boolean drawnInFrame(long frame) {
        return lastDrawnFrame == frame;
    }

    /**
     * Whether the light's weight at the camera, power / (4 pi d^2), clears the
     * threshold this frame, applying the hysteresis band when it was drawn
     * the previous frame. Records the decision.
     */
    public boolean shouldDraw(double camX, double camY, double camZ, float threshold, long frame) {
        float floor = lastDrawnFrame == frame - 1 ? threshold / HYSTERESIS : threshold;
        boolean draw = withinReach(x, y, z, power, camX, camY, camZ, floor);
        if (draw) lastDrawnFrame = frame;
        return draw;
    }

    public static boolean withinReach(double x, float y, double z, float power,
                                      double camX, double camY, double camZ, float threshold) {
        double dx = x - camX, dy = y - camY, dz = z - camZ;
        double distanceSq = Math.max(1.0, dx * dx + dy * dy + dz * dz);
        return power / (FOUR_PI * distanceSq) >= threshold;
    }

    /**
     * Weight threshold for a light reach in blocks: the weight of the shipped
     * torch at that distance. A light of power P is then drawn out to
     * reach x sqrt(P / torch power).
     */
    public static float thresholdFor(int reachBlocks) {
        double reach = Math.max(1, reachBlocks);
        return (float) (LightPower.torchReference() / (FOUR_PI * reach * reach));
    }

    /** Linear RGB radiance after every multiplier, shared by the native light and its power. */
    private record BlockLightRadiance(float r, float g, float b) {}

    private static BlockLightRadiance blockLightRadiance(LightEntry entry, BlockTextureManager tex) {
        float[] blockColor = (tex != null)
                ? tex.getLightColorForBlock(entry.block())
                : BlockTextureManager.FALLBACK_WARM_WHITE;
        float r = entry.hasColor() ? entry.colorR() : blockColor[0];
        float g = entry.hasColor() ? entry.colorG() : blockColor[1];
        float b = entry.hasColor() ? entry.colorB() : blockColor[2];
        float baseIntensity = entry.hasIntensity()
                ? entry.intensity()
                : entry.lightLevel() * BlockLightRuleManager.RADIANCE_PER_LIGHT_LEVEL;
        baseIntensity *= entry.authoredIntensityMultiplier();
        float intensity =
                BlockLightIntensityControls.apply(entry.key().ruleId(), baseIntensity);
        return new BlockLightRadiance(r * intensity, g * intensity, b * intensity);
    }

    private static LightInfo buildBlockLightInfo(LightEntry entry, BlockLightRadiance radiance) {
        RenderOrigin origin = RenderOrigin.current();
        LightInfo.Builder builder = LightInfo.builder()
                .radiance(radiance.r(), radiance.g(), radiance.b());
        return switch (entry.shape()) {
            case SPHERE -> {
                LightInfoSphereEXT.Builder sphere = LightInfoSphereEXT.builder()
                        .position(origin.localX(entry.x()), entry.y(), origin.localZ(entry.z()))
                        .radius(entry.radius())
                        .volumetricRadianceScale(entry.volumetricRadianceScale());
                // Spot lights: only sphere/rect/disk accept shaping in the Remix
                // API, and shapedSphere is currently the only producer of it.
                if (entry.hasShaping()) {
                    sphere.shaping(shapingOf(entry));
                }
                yield builder.sphereExt(sphere.build()).build();
            }
            case CYLINDER -> builder
                    .cylinderExt(LightInfoCylinderEXT.builder()
                            .position(origin.localX(entry.x()), entry.y(), origin.localZ(entry.z()))
                            .radius(entry.radius())
                            .axis(entry.axisX(), entry.axisY(), entry.axisZ())
                            .axisLength(entry.axisLength())
                            .volumetricRadianceScale(entry.volumetricRadianceScale())
                            .build())
                    .build();
            case RECT -> builder
                    .rectExt(LightInfoRectEXT.builder()
                            .position(origin.localX(entry.x()), entry.y(), origin.localZ(entry.z()))
                            .xAxis(entry.xAxisX(), entry.xAxisY(), entry.xAxisZ())
                            .xSize(entry.xSize())
                            .yAxis(entry.yAxisX(), entry.yAxisY(), entry.yAxisZ())
                            .ySize(entry.ySize())
                            .direction(entry.directionX(), entry.directionY(), entry.directionZ())
                            .volumetricRadianceScale(entry.volumetricRadianceScale())
                            .build())
                    .build();
            case DISK -> builder
                    .diskExt(LightInfoDiskEXT.builder()
                            .position(origin.localX(entry.x()), entry.y(), origin.localZ(entry.z()))
                            .xAxis(entry.xAxisX(), entry.xAxisY(), entry.xAxisZ())
                            .xRadius(entry.xSize())
                            .yAxis(entry.yAxisX(), entry.yAxisY(), entry.yAxisZ())
                            .yRadius(entry.ySize())
                            .direction(entry.directionX(), entry.directionY(), entry.directionZ())
                            .volumetricRadianceScale(entry.volumetricRadianceScale())
                            .build())
                    .build();
        };
    }

    private static LightInfoLightShaping shapingOf(LightEntry entry) {
        LightInfoLightShaping shaping = new LightInfoLightShaping();
        shaping.direction = new Float3D(
                entry.shapingDirectionX(), entry.shapingDirectionY(), entry.shapingDirectionZ());
        shaping.coneAngleDegrees = entry.coneAngleDegrees();
        shaping.coneSoftness = entry.coneSoftness();
        shaping.focusExponent = entry.focusExponent();
        return shaping;
    }
}
