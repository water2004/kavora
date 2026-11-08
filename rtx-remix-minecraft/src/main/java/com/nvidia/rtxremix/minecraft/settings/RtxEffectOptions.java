/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import com.nvidia.rtxremix.RemixAPI;
import java.util.List;

/** Native visual controls, separated from display reconstruction and CPU/GPU scheduling. */
final class RtxEffectOptions {
    record Control(String id, SettingGroup group, String key, RtxSetting.Kind kind, double min, double max,
                   boolean choices) {
        Control(String id, SettingGroup group, String key, RtxSetting.Kind kind, double min, double max) {
            this(id, group, key, kind, min, max, false);
        }

        String labelKey() { return "rtxremix.effect." + id; }
    }

    private static Control choice(String id, SettingGroup group, String key, int min, int max) {
        return new Control(id, group, key, RtxSetting.Kind.INT, min, max, true);
    }

    record ColorControl(String id, SettingGroup group, String key) {}

    static final List<ColorControl> COLORS = List.of(
            new ColorControl("fog.transmittance", SettingGroup.FOG, "rtx.volumetrics.transmittanceColor"),
            new ColorControl("fog.scatteringAlbedo", SettingGroup.FOG, "rtx.volumetrics.singleScatteringAlbedo"),
            new ColorControl("image.colorBalance", SettingGroup.COLOR_GRADING, "rtx.tonemap.colorBalance"));

    static final List<Control> CONTROLS = List.of(
        new Control("nightSky.enabled", SettingGroup.NIGHT_SKY, "rtx.nightSky.enabled", RtxSetting.Kind.BOOL, 0, 1),
        new Control("nightSky.starIntensity", SettingGroup.NIGHT_SKY, "rtx.nightSky.starIntensity", RtxSetting.Kind.FLOAT, 0, 4),
        new Control("nightSky.milkyWayIntensity", SettingGroup.NIGHT_SKY, "rtx.nightSky.milkyWayIntensity", RtxSetting.Kind.FLOAT, 0, 4),
        new Control("nightSky.meteorRate", SettingGroup.NIGHT_SKY, "rtx.nightSky.meteorRate", RtxSetting.Kind.FLOAT, 0, 60),
        new Control("nightSky.meteorIntensity", SettingGroup.NIGHT_SKY, "rtx.nightSky.meteorIntensity", RtxSetting.Kind.FLOAT, 0, 8),
        new Control("endSky.enabled", SettingGroup.END_SKY, "rtx.endSky.enabled", RtxSetting.Kind.BOOL, 0, 1),
        new Control("endSky.intensity", SettingGroup.END_SKY, "rtx.endSky.intensity", RtxSetting.Kind.FLOAT, 0, 4),
        new Control("endSky.backgroundIntensity", SettingGroup.END_SKY, "rtx.endSky.backgroundIntensity", RtxSetting.Kind.FLOAT, 0, 4),
        new Control("endSky.haloIntensity", SettingGroup.END_SKY, "rtx.endSky.haloIntensity", RtxSetting.Kind.FLOAT, 0, 4),
        new Control("endSky.animationSpeed", SettingGroup.END_SKY, "rtx.endSky.animationSpeed", RtxSetting.Kind.FLOAT, 0, 4),
        new Control("endSky.rotation", SettingGroup.END_SKY, "rtx.endSky.rotation", RtxSetting.Kind.FLOAT, 0, 360),
        new Control("endSky.elevation", SettingGroup.END_SKY, "rtx.endSky.elevation", RtxSetting.Kind.FLOAT, -90, 90),
        choice("clouds.transport", SettingGroup.CLOUD_QUALITY, "rtx.clouds.transportMode", 0, 1),
        new Control("clouds.skyResolution", SettingGroup.CLOUD_QUALITY, "rtx.clouds.skyResolution", RtxSetting.Kind.INT, 128, 2048),
        new Control("clouds.windSpeed", SettingGroup.CLOUDS, "rtx.clouds.windSpeed", RtxSetting.Kind.FLOAT, 0, 5),
        new Control("clouds.anisotropy", SettingGroup.CLOUDS, "rtx.clouds.anisotropy", RtxSetting.Kind.FLOAT, 0, 0.95),
        new Control("clouds.multipleScattering", SettingGroup.CLOUDS, "rtx.clouds.multipleScattering", RtxSetting.Kind.FLOAT, 0, 1),
        new Control("clouds.coverage", SettingGroup.CLOUDS, "rtx.clouds.coverage", RtxSetting.Kind.FLOAT, 0, 1),
        new Control("clouds.altitudeOffset", SettingGroup.CLOUDS, "rtx.clouds.altitudeOffsetMeters", RtxSetting.Kind.FLOAT, 0, 2000),
        new Control("clouds.thickness", SettingGroup.CLOUDS, "rtx.clouds.thicknessMeters", RtxSetting.Kind.FLOAT, 8, 3000),
        new Control("clouds.shapeScale", SettingGroup.CLOUDS, "rtx.clouds.shapeScaleMeters", RtxSetting.Kind.FLOAT, 64, 4096),
        new Control("clouds.density", SettingGroup.CLOUDS, "rtx.clouds.extinctionPerMeter", RtxSetting.Kind.FLOAT, 0, 0.2),
        new Control("clouds.distance", SettingGroup.CLOUDS, "rtx.clouds.maxDistanceMeters", RtxSetting.Kind.FLOAT, 512, 60000),
        new Control("clouds.samples", SettingGroup.CLOUD_QUALITY, "rtx.clouds.steps", RtxSetting.Kind.INT, 8, 256),
        new Control("clouds.shadowSamples", SettingGroup.CLOUD_QUALITY, "rtx.clouds.lightSteps", RtxSetting.Kind.INT, 1, 16),
        new Control("fog.range", SettingGroup.FOG, "rtx.volumetrics.transmittanceMeasurementDistanceMeters", RtxSetting.Kind.FLOAT, 1, 2000),
        new Control("fog.baseHeight", SettingGroup.FOG, "rtx.volumetrics.heightFogBaseHeightMeters", RtxSetting.Kind.FLOAT, -64, 320),
        new Control("fog.heightFalloff", SettingGroup.FOG, "rtx.volumetrics.heightFogFalloffMeters", RtxSetting.Kind.FLOAT, 0, 128),
        new Control("mist.baseHeight", SettingGroup.GROUND_MIST, "rtx.volumetrics.groundMistBaseHeightMeters", RtxSetting.Kind.FLOAT, -64, 320),
        new Control("mist.heightFalloff", SettingGroup.GROUND_MIST, "rtx.volumetrics.groundMistFalloffMeters", RtxSetting.Kind.FLOAT, 0.1, 128),
        new Control("mist.regionSize", SettingGroup.GROUND_MIST, "rtx.volumetrics.groundMistRegionSizeMeters", RtxSetting.Kind.FLOAT, 16, 512),
        new Control("fog.anisotropy", SettingGroup.FOG, "rtx.volumetrics.anisotropy", RtxSetting.Kind.FLOAT, -0.95, 0.95),
        new Control("fog.translucentShadows", SettingGroup.FOG, "rtx.volumetrics.enableTranslucentShadows", RtxSetting.Kind.BOOL, 0, 1),
        new Control("fog.noise", SettingGroup.FOG_NOISE, "rtx.volumetrics.enableHeterogeneousFog", RtxSetting.Kind.BOOL, 0, 1),
        new Control("fog.noiseSpeed", SettingGroup.FOG_NOISE, "rtx.volumetrics.noiseFieldTimeScale", RtxSetting.Kind.FLOAT, 0, 3),
        new Control("fog.noiseScale", SettingGroup.FOG_NOISE, "rtx.volumetrics.noiseFieldDensityScale", RtxSetting.Kind.FLOAT, 0, 3),
        new Control("fog.noiseExponent", SettingGroup.FOG_NOISE, "rtx.volumetrics.noiseFieldDensityExponent", RtxSetting.Kind.FLOAT, 0.1, 5),
        new Control("fog.noiseFrequency", SettingGroup.FOG_NOISE, "rtx.volumetrics.noiseFieldInitialFrequencyPerMeter", RtxSetting.Kind.FLOAT, 0.005, 16),
        new Control("fog.grid", SettingGroup.FOG_QUALITY, "rtx.volumetrics.froxelGridResolutionScale", RtxSetting.Kind.INT, 4, 32),
        choice("fog.integration", SettingGroup.FOG_QUALITY, "rtx.volumetrics.fogIntegrationMode", 0, 1),
        new Control("fog.integrationResolution", SettingGroup.FOG_QUALITY, "rtx.volumetrics.fogIntegrationPixelStride", RtxSetting.Kind.INT, 2, 32),
        new Control("fog.initialSamples", SettingGroup.FOG_QUALITY, "rtx.volumetrics.initialRISSampleCount", RtxSetting.Kind.INT, 1, 64),
        new Control("fog.spatialSamples", SettingGroup.FOG_QUALITY, "rtx.volumetrics.spatialReuseMaxSampleCount", RtxSetting.Kind.INT, 1, 16),
        new Control("fog.lightingGrid", SettingGroup.FOG_QUALITY, "rtx.volumetrics.restirGridScale", RtxSetting.Kind.INT, 1, 8),
        new Control("fog.lightingDepthSlices", SettingGroup.FOG_QUALITY, "rtx.volumetrics.restirFroxelDepthSlices", RtxSetting.Kind.INT, 16, 256),
        new Control("fog.depthSlices", SettingGroup.FOG_QUALITY, "rtx.volumetrics.froxelDepthSlices", RtxSetting.Kind.INT, 32, 256),
        new Control("fog.history", SettingGroup.FOG_QUALITY, "rtx.volumetrics.maxAccumulationFrames", RtxSetting.Kind.INT, 1, 254),
        choice("lighting.indirect", SettingGroup.PATH_TRACING, "rtx.integrateIndirectMode", 0, 3),
        new Control("lighting.minBounces", SettingGroup.PATH_TRACING, "rtx.pathMinBounces", RtxSetting.Kind.INT, 0, 15),
        new Control("lighting.bounces", SettingGroup.PATH_TRACING, "rtx.pathMaxBounces", RtxSetting.Kind.INT, 0, 15),
        new Control("lighting.rtxdi", SettingGroup.DIRECT_LIGHTING, "rtx.useRTXDI", RtxSetting.Kind.BOOL, 0, 1),
        new Control("lighting.initialSamples", SettingGroup.DIRECT_LIGHTING, "rtx.di.initialSampleCount", RtxSetting.Kind.INT, 1, 64),
        new Control("lighting.spatialSamples", SettingGroup.DIRECT_LIGHTING, "rtx.di.spatialSamples", RtxSetting.Kind.INT, 1, 16),
        new Control("lighting.disocclusionSamples", SettingGroup.DIRECT_LIGHTING, "rtx.di.disocclusionSamples", RtxSetting.Kind.INT, 1, 32),
        new Control("lighting.risSamples", SettingGroup.SAMPLING, "rtx.risLightSampleCount", RtxSetting.Kind.INT, 1, 64),
        new Control("lighting.sparse", SettingGroup.SAMPLING, "rtx.sparseRendering.enableSparseRendering", RtxSetting.Kind.BOOL, 0, 1),
        new Control("lighting.adaptiveSampling", SettingGroup.SAMPLING, "rtx.sparseRendering.enableAdaptiveSamplingRate", RtxSetting.Kind.BOOL, 0, 1),
        new Control("lighting.samplingQuality", SettingGroup.SAMPLING, "rtx.sparseRendering.quality", RtxSetting.Kind.FLOAT, 0, 1),
        new Control("lighting.samplingRate", SettingGroup.SAMPLING, "rtx.sparseRendering.samplingRate", RtxSetting.Kind.FLOAT, 0.01, 1),
        new Control("lighting.samplingExponent", SettingGroup.SAMPLING, "rtx.sparseRendering.samplingRateResolutionExponent", RtxSetting.Kind.FLOAT, 0, 2),
        new Control("lighting.sparseTransparency", SettingGroup.SAMPLING, "rtx.sparseRendering.enableSparseAwareAlphaBlendSearch", RtxSetting.Kind.BOOL, 0, 1),
        new Control("cache.records", SettingGroup.RADIANCE_CACHE, "rtx.spatiallyHashedRadianceCache.targetNumUpdateRecords", RtxSetting.Kind.INT, 0, 262144),
        new Control("cache.combine", SettingGroup.RADIANCE_CACHE, "rtx.spatiallyHashedRadianceCache.combineUpdateAndQuery", RtxSetting.Kind.BOOL, 0, 1),
        new Control("cache.primary", SettingGroup.RADIANCE_CACHE, "rtx.spatiallyHashedRadianceCache.queryForThinOpaquePrimaryView", RtxSetting.Kind.BOOL, 0, 1),
        choice("cache.filter", SettingGroup.RADIANCE_CACHE, "rtx.spatiallyHashedRadianceCache.cellFilterMode", 0, 2),
        new Control("cache.spatialJitter", SettingGroup.RADIANCE_CACHE, "rtx.spatiallyHashedRadianceCache.primaryQuerySpatialJitter", RtxSetting.Kind.BOOL, 0, 1),
        new Control("cache.lodJitter", SettingGroup.RADIANCE_CACHE, "rtx.spatiallyHashedRadianceCache.primaryQueryLodJitter", RtxSetting.Kind.BOOL, 0, 1),
        new Control("lighting.direct", SettingGroup.DIRECT_LIGHTING, "rtx.enableDirectLighting", RtxSetting.Kind.BOOL, 0, 1),
        new Control("lighting.secondary", SettingGroup.PATH_TRACING, "rtx.enableSecondaryBounces", RtxSetting.Kind.BOOL, 0, 1),
        new Control("shadows.coloredDirect", SettingGroup.SHADOWS, "rtx.enableDirectTranslucentShadows", RtxSetting.Kind.BOOL, 0, 1),
        new Control("shadows.coloredIndirect", SettingGroup.SHADOWS, "rtx.enableIndirectTranslucentShadows", RtxSetting.Kind.BOOL, 0, 1),
        new Control("reflection.psrr", SettingGroup.REFLECTIONS, "rtx.enablePSRR", RtxSetting.Kind.BOOL, 0, 1),
        new Control("reflection.pstr", SettingGroup.REFLECTIONS, "rtx.enablePSTR", RtxSetting.Kind.BOOL, 0, 1),
        new Control("reflection.bounces", SettingGroup.REFLECTIONS, "rtx.psrrMaxBounces", RtxSetting.Kind.INT, 1, 16),
        new Control("refraction.bounces", SettingGroup.REFLECTIONS, "rtx.pstrMaxBounces", RtxSetting.Kind.INT, 1, 16),
        new Control("materials.normalStrength", SettingGroup.MATERIALS, "rtx.opaqueMaterial.normalIntensity", RtxSetting.Kind.FLOAT, 0, 3),
        choice("materials.pom", SettingGroup.PARALLAX, "rtx.displacement.mode", 0, 3),
        new Control("materials.pomIterations", SettingGroup.PARALLAX, "rtx.displacement.maxIterations", RtxSetting.Kind.INT, 8, 256),
        new Control("materials.pomDirect", SettingGroup.PARALLAX, "rtx.displacement.enableDirectLighting", RtxSetting.Kind.BOOL, 0, 1),
        new Control("materials.pomIndirect", SettingGroup.PARALLAX, "rtx.displacement.enableIndirectLighting", RtxSetting.Kind.BOOL, 0, 1),
        new Control("materials.pomNee", SettingGroup.PARALLAX, "rtx.displacement.enableNEECache", RtxSetting.Kind.BOOL, 0, 1),
        new Control("materials.pomRestir", SettingGroup.PARALLAX, "rtx.displacement.enableReSTIRGI", RtxSetting.Kind.BOOL, 0, 1),
        new Control("materials.pomIndirectHit", SettingGroup.PARALLAX, "rtx.displacement.enableIndirectHit", RtxSetting.Kind.BOOL, 0, 1),
        new Control("materials.pomPsr", SettingGroup.PARALLAX, "rtx.displacement.enablePSR", RtxSetting.Kind.BOOL, 0, 1),
        new Control("water.normalStrength", SettingGroup.WATER, "rtx.translucentMaterial.normalIntensity", RtxSetting.Kind.FLOAT, 0, 3),
        new Control("sss.inRefraction", SettingGroup.SUBSURFACE, "rtx.subsurface.enableThinOpaqueInRefractions", RtxSetting.Kind.BOOL, 0, 1),
        new Control("sss.transmission", SettingGroup.SUBSURFACE, "rtx.subsurface.enableTransmission", RtxSetting.Kind.BOOL, 0, 1),
        new Control("sss.singleScattering", SettingGroup.SUBSURFACE, "rtx.subsurface.enableTransmissionSingleScattering", RtxSetting.Kind.BOOL, 0, 1),
        new Control("sss.transmissionSamples", SettingGroup.SUBSURFACE, "rtx.subsurface.transmissionBsdfSampleCount", RtxSetting.Kind.INT, 1, 16),
        new Control("sss.scatteringSamples", SettingGroup.SUBSURFACE, "rtx.subsurface.transmissionSingleScatteringSampleCount", RtxSetting.Kind.INT, 1, 16),
        new Control("sss.diffusionScale", SettingGroup.SUBSURFACE, "rtx.subsurface.diffusionProfileScale", RtxSetting.Kind.FLOAT, 0, 3),
        new Control("bloom.strength", SettingGroup.BLOOM, "rtx.bloom.burnIntensity", RtxSetting.Kind.FLOAT, 0, 5),
        new Control("bloom.threshold", SettingGroup.BLOOM, "rtx.bloom.luminanceThreshold", RtxSetting.Kind.FLOAT, 0, 5),
        choice("image.tonemapper", SettingGroup.COLOR_GRADING, "rtx.tonemappingMode", 0, 1),
        new Control("image.localContrast", SettingGroup.TONEMAPPING, "rtx.localtonemap.boostLocalContrast", RtxSetting.Kind.BOOL, 0, 1),
        new Control("image.localAces", SettingGroup.TONEMAPPING, "rtx.localtonemap.finalizeWithACES", RtxSetting.Kind.BOOL, 0, 1),
        new Control("image.globalExposure", SettingGroup.COLOR_GRADING, "rtx.tonemap.exposureBias", RtxSetting.Kind.FLOAT, -8, 8),
        new Control("image.grading", SettingGroup.COLOR_GRADING, "rtx.tonemap.colorGradingEnabled", RtxSetting.Kind.BOOL, 0, 1),
        new Control("image.contrast", SettingGroup.COLOR_GRADING, "rtx.tonemap.contrast", RtxSetting.Kind.FLOAT, 0, 2),
        new Control("image.saturation", SettingGroup.COLOR_GRADING, "rtx.tonemap.saturation", RtxSetting.Kind.FLOAT, 0, 2),
        new Control("exposure.enabled", SettingGroup.EYE_ADAPTATION, "rtx.autoExposure.enabled", RtxSetting.Kind.BOOL, 0, 1),
        new Control("exposure.speed", SettingGroup.EYE_ADAPTATION, "rtx.autoExposure.autoExposureSpeed", RtxSetting.Kind.FLOAT, 0.1, 20),
        new Control("exposure.center", SettingGroup.EYE_ADAPTATION, "rtx.autoExposure.exposureCenterMeteringEnabled", RtxSetting.Kind.BOOL, 0, 1),
        new Control("exposure.centerSize", SettingGroup.EYE_ADAPTATION, "rtx.autoExposure.centerMeteringSize", RtxSetting.Kind.FLOAT, 0.01, 1),
        choice("exposure.average", SettingGroup.EYE_ADAPTATION, "rtx.autoExposure.exposureAverageMode", 0, 1));

    private RtxEffectOptions() {}

    static void addTo(List<RtxSetting<?>> settings, RemixAPI api) {
        for (Control control : CONTROLS) {
            String initial = api.getConfigVariableResolved(control.key());
            String label = control.labelKey();
            Object authored = RtxQualityPresets.defaultValue(control.id());
            switch (control.kind()) {
                case BOOL -> {
                    boolean[] value = {Boolean.parseBoolean(initial)};
                    settings.add(RtxSetting.ofBool(control.id(), control.group(), label, label + ".tip",
                            authored == null ? value[0] : (Boolean) authored, () -> value[0], next -> {
                                api.setConfigVariable(control.key(), Boolean.toString(next));
                                value[0] = next;
                            }));
                }
                case INT -> {
                    int[] value = {Integer.parseInt(initial)};
                    RtxSetting<Integer> setting = RtxSetting.ofInt(control.id(), control.group(), label, label + ".tip",
                            Math.min((int) control.min(), value[0]), Math.max((int) control.max(), value[0]),
                            authored == null ? value[0] : (Integer) authored, () -> value[0], next -> {
                                api.setConfigVariable(control.key(), Integer.toString(next));
                                value[0] = next;
                            });
                    if (control.choices()) setting.withChoices();
                    settings.add(setting);
                }
                case FLOAT -> {
                    double[] value = {Double.parseDouble(initial)};
                    settings.add(RtxSetting.ofFloat(control.id(), control.group(), label, label + ".tip",
                            Math.min(control.min(), value[0]), Math.max(control.max(), value[0]),
                            authored == null ? value[0] : (Double) authored, () -> value[0], next -> {
                                api.setConfigVariable(control.key(), Float.toString(next.floatValue()));
                                value[0] = next;
                            }));
                }
            }
        }
        for (ColorControl control : COLORS) addColor(settings, api, control);
    }

    private static void addColor(List<RtxSetting<?>> settings, RemixAPI api, ColorControl control) {
        String[] components = api.getConfigVariableResolved(control.key()).replace("(", "").replace(")", "").trim().split("\\s*,\\s*");
        if (components.length != 3) throw new IllegalArgumentException("Invalid RGB runtime option: " + control.key());
        double[] value = {Double.parseDouble(components[0]), Double.parseDouble(components[1]), Double.parseDouble(components[2])};
        String[] channels = {"red", "green", "blue"};
        for (int channel = 0; channel < channels.length; channel++) {
            int index = channel;
            String id = control.id() + "." + channels[channel];
            String label = "rtxremix.effect." + id;
            settings.add(RtxSetting.ofFloat(id, control.group(), label, label + ".tip", 0, 1, value[index],
                    () -> value[index], next -> {
                        double[] updated = value.clone();
                        updated[index] = next;
                        api.setConfigVariable(control.key(), Float.toString((float) updated[0]) + ", "
                                + Float.toString((float) updated[1]) + ", " + Float.toString((float) updated[2]));
                        System.arraycopy(updated, 0, value, 0, 3);
                    }));
        }
    }

    static boolean contains(String id) {
        return CONTROLS.stream().anyMatch(control -> control.id().equals(id))
                || COLORS.stream().anyMatch(control -> id.startsWith(control.id() + "."));
    }
}

