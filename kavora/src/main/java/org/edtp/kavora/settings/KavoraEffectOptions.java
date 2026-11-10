/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.settings;

import org.edtp.kavora.sdk.RemixAPI;
import java.util.List;

/** Native visual controls, separated from display reconstruction and CPU/GPU scheduling. */
final class KavoraEffectOptions {
    record Control(String id, SettingGroup group, String key, KavoraSetting.Kind kind, double min, double max,
                   boolean choices) {
        Control(String id, SettingGroup group, String key, KavoraSetting.Kind kind, double min, double max) {
            this(id, group, key, kind, min, max, false);
        }

        String labelKey() { return "kavora.effect." + id; }
    }

    private static Control choice(String id, SettingGroup group, String key, int min, int max) {
        return new Control(id, group, key, KavoraSetting.Kind.INT, min, max, true);
    }

    record ColorControl(String id, SettingGroup group, String key) {}

    static final List<ColorControl> COLORS = List.of(
            new ColorControl("fog.transmittance", SettingGroup.FOG, "rtx.volumetrics.transmittanceColor"),
            new ColorControl("fog.scatteringAlbedo", SettingGroup.FOG, "rtx.volumetrics.singleScatteringAlbedo"),
            new ColorControl("image.colorBalance", SettingGroup.COLOR_GRADING, "rtx.tonemap.colorBalance"));

    static final List<Control> CONTROLS = List.of(
        new Control("nightSky.enabled", SettingGroup.NIGHT_SKY, "rtx.nightSky.enabled", KavoraSetting.Kind.BOOL, 0, 1),
        new Control("nightSky.starIntensity", SettingGroup.NIGHT_SKY, "rtx.nightSky.starIntensity", KavoraSetting.Kind.FLOAT, 0, 4),
        new Control("nightSky.milkyWayIntensity", SettingGroup.NIGHT_SKY, "rtx.nightSky.milkyWayIntensity", KavoraSetting.Kind.FLOAT, 0, 4),
        new Control("nightSky.meteorRate", SettingGroup.NIGHT_SKY, "rtx.nightSky.meteorRate", KavoraSetting.Kind.FLOAT, 0, 60),
        new Control("nightSky.meteorIntensity", SettingGroup.NIGHT_SKY, "rtx.nightSky.meteorIntensity", KavoraSetting.Kind.FLOAT, 0, 8),
        new Control("endSky.enabled", SettingGroup.END_SKY, "rtx.endSky.enabled", KavoraSetting.Kind.BOOL, 0, 1),
        new Control("endSky.intensity", SettingGroup.END_SKY, "rtx.endSky.intensity", KavoraSetting.Kind.FLOAT, 0, 4),
        new Control("endSky.backgroundIntensity", SettingGroup.END_SKY, "rtx.endSky.backgroundIntensity", KavoraSetting.Kind.FLOAT, 0, 4),
        new Control("endSky.haloIntensity", SettingGroup.END_SKY, "rtx.endSky.haloIntensity", KavoraSetting.Kind.FLOAT, 0, 4),
        new Control("endSky.animationSpeed", SettingGroup.END_SKY, "rtx.endSky.animationSpeed", KavoraSetting.Kind.FLOAT, 0, 4),
        new Control("endSky.rotation", SettingGroup.END_SKY, "rtx.endSky.rotation", KavoraSetting.Kind.FLOAT, 0, 360),
        new Control("endSky.elevation", SettingGroup.END_SKY, "rtx.endSky.elevation", KavoraSetting.Kind.FLOAT, -90, 90),
        choice("clouds.transport", SettingGroup.CLOUD_QUALITY, "rtx.clouds.transportMode", 0, 1),
        new Control("clouds.skyResolution", SettingGroup.CLOUD_QUALITY, "rtx.clouds.skyResolution", KavoraSetting.Kind.INT, 128, 2048),
        new Control("clouds.windSpeed", SettingGroup.CLOUDS, "rtx.clouds.windSpeed", KavoraSetting.Kind.FLOAT, 0, 5),
        new Control("clouds.anisotropy", SettingGroup.CLOUDS, "rtx.clouds.anisotropy", KavoraSetting.Kind.FLOAT, 0, 0.95),
        new Control("clouds.multipleScattering", SettingGroup.CLOUDS, "rtx.clouds.multipleScattering", KavoraSetting.Kind.FLOAT, 0, 1),
        new Control("clouds.coverage", SettingGroup.CLOUDS, "rtx.clouds.coverage", KavoraSetting.Kind.FLOAT, 0, 1),
        new Control("clouds.altitudeOffset", SettingGroup.CLOUDS, "rtx.clouds.altitudeOffsetMeters", KavoraSetting.Kind.FLOAT, 0, 2000),
        new Control("clouds.thickness", SettingGroup.CLOUDS, "rtx.clouds.thicknessMeters", KavoraSetting.Kind.FLOAT, 8, 3000),
        new Control("clouds.shapeScale", SettingGroup.CLOUDS, "rtx.clouds.shapeScaleMeters", KavoraSetting.Kind.FLOAT, 64, 4096),
        new Control("clouds.density", SettingGroup.CLOUDS, "rtx.clouds.extinctionPerMeter", KavoraSetting.Kind.FLOAT, 0, 0.2),
        new Control("clouds.distance", SettingGroup.CLOUDS, "rtx.clouds.maxDistanceMeters", KavoraSetting.Kind.FLOAT, 512, 60000),
        new Control("clouds.samples", SettingGroup.CLOUD_QUALITY, "rtx.clouds.steps", KavoraSetting.Kind.INT, 8, 256),
        new Control("clouds.shadowSamples", SettingGroup.CLOUD_QUALITY, "rtx.clouds.lightSteps", KavoraSetting.Kind.INT, 1, 16),
        new Control("fog.range", SettingGroup.FOG, "rtx.volumetrics.transmittanceMeasurementDistanceMeters", KavoraSetting.Kind.FLOAT, 1, 2000),
        new Control("fog.baseHeight", SettingGroup.FOG, "rtx.volumetrics.heightFogBaseHeightMeters", KavoraSetting.Kind.FLOAT, -64, 320),
        new Control("fog.heightFalloff", SettingGroup.FOG, "rtx.volumetrics.heightFogFalloffMeters", KavoraSetting.Kind.FLOAT, 0, 128),
        new Control("mist.baseHeight", SettingGroup.GROUND_MIST, "rtx.volumetrics.groundMistBaseHeightMeters", KavoraSetting.Kind.FLOAT, -64, 320),
        new Control("mist.heightFalloff", SettingGroup.GROUND_MIST, "rtx.volumetrics.groundMistFalloffMeters", KavoraSetting.Kind.FLOAT, 0.1, 128),
        new Control("mist.regionSize", SettingGroup.GROUND_MIST, "rtx.volumetrics.groundMistRegionSizeMeters", KavoraSetting.Kind.FLOAT, 16, 512),
        new Control("fog.anisotropy", SettingGroup.FOG, "rtx.volumetrics.anisotropy", KavoraSetting.Kind.FLOAT, -0.95, 0.95),
        new Control("fog.translucentShadows", SettingGroup.FOG, "rtx.volumetrics.enableTranslucentShadows", KavoraSetting.Kind.BOOL, 0, 1),
        new Control("fog.noise", SettingGroup.FOG_NOISE, "rtx.volumetrics.enableHeterogeneousFog", KavoraSetting.Kind.BOOL, 0, 1),
        new Control("fog.noiseSpeed", SettingGroup.FOG_NOISE, "rtx.volumetrics.noiseFieldTimeScale", KavoraSetting.Kind.FLOAT, 0, 3),
        new Control("fog.noiseScale", SettingGroup.FOG_NOISE, "rtx.volumetrics.noiseFieldDensityScale", KavoraSetting.Kind.FLOAT, 0, 3),
        new Control("fog.noiseExponent", SettingGroup.FOG_NOISE, "rtx.volumetrics.noiseFieldDensityExponent", KavoraSetting.Kind.FLOAT, 0.1, 5),
        new Control("fog.noiseFrequency", SettingGroup.FOG_NOISE, "rtx.volumetrics.noiseFieldInitialFrequencyPerMeter", KavoraSetting.Kind.FLOAT, 0.005, 16),
        new Control("fog.grid", SettingGroup.FOG_QUALITY, "rtx.volumetrics.froxelGridResolutionScale", KavoraSetting.Kind.INT, 4, 32),
        choice("fog.integration", SettingGroup.FOG_QUALITY, "rtx.volumetrics.fogIntegrationMode", 0, 1),
        new Control("fog.integrationResolution", SettingGroup.FOG_QUALITY, "rtx.volumetrics.fogIntegrationPixelStride", KavoraSetting.Kind.INT, 2, 32),
        new Control("fog.initialSamples", SettingGroup.FOG_QUALITY, "rtx.volumetrics.initialRISSampleCount", KavoraSetting.Kind.INT, 1, 64),
        new Control("fog.spatialSamples", SettingGroup.FOG_QUALITY, "rtx.volumetrics.spatialReuseMaxSampleCount", KavoraSetting.Kind.INT, 1, 16),
        new Control("fog.lightingGrid", SettingGroup.FOG_QUALITY, "rtx.volumetrics.restirGridScale", KavoraSetting.Kind.INT, 1, 8),
        new Control("fog.lightingDepthSlices", SettingGroup.FOG_QUALITY, "rtx.volumetrics.restirFroxelDepthSlices", KavoraSetting.Kind.INT, 16, 256),
        new Control("fog.depthSlices", SettingGroup.FOG_QUALITY, "rtx.volumetrics.froxelDepthSlices", KavoraSetting.Kind.INT, 32, 256),
        new Control("fog.history", SettingGroup.FOG_QUALITY, "rtx.volumetrics.maxAccumulationFrames", KavoraSetting.Kind.INT, 1, 254),
        choice("lighting.indirect", SettingGroup.PATH_TRACING, "rtx.integrateIndirectMode", 0, 3),
        new Control("lighting.minBounces", SettingGroup.PATH_TRACING, "rtx.pathMinBounces", KavoraSetting.Kind.INT, 0, 15),
        new Control("lighting.bounces", SettingGroup.PATH_TRACING, "rtx.pathMaxBounces", KavoraSetting.Kind.INT, 0, 15),
        new Control("lighting.rtxdi", SettingGroup.DIRECT_LIGHTING, "rtx.useRTXDI", KavoraSetting.Kind.BOOL, 0, 1),
        new Control("lighting.initialSamples", SettingGroup.DIRECT_LIGHTING, "rtx.di.initialSampleCount", KavoraSetting.Kind.INT, 1, 64),
        new Control("lighting.spatialSamples", SettingGroup.DIRECT_LIGHTING, "rtx.di.spatialSamples", KavoraSetting.Kind.INT, 1, 16),
        new Control("lighting.disocclusionSamples", SettingGroup.DIRECT_LIGHTING, "rtx.di.disocclusionSamples", KavoraSetting.Kind.INT, 1, 32),
        new Control("lighting.risSamples", SettingGroup.SAMPLING, "rtx.risLightSampleCount", KavoraSetting.Kind.INT, 1, 64),
        new Control("lighting.sparse", SettingGroup.SAMPLING, "rtx.sparseRendering.enableSparseRendering", KavoraSetting.Kind.BOOL, 0, 1),
        new Control("lighting.adaptiveSampling", SettingGroup.SAMPLING, "rtx.sparseRendering.enableAdaptiveSamplingRate", KavoraSetting.Kind.BOOL, 0, 1),
        new Control("lighting.samplingQuality", SettingGroup.SAMPLING, "rtx.sparseRendering.quality", KavoraSetting.Kind.FLOAT, 0, 1),
        new Control("lighting.samplingRate", SettingGroup.SAMPLING, "rtx.sparseRendering.samplingRate", KavoraSetting.Kind.FLOAT, 0.01, 1),
        new Control("lighting.samplingExponent", SettingGroup.SAMPLING, "rtx.sparseRendering.samplingRateResolutionExponent", KavoraSetting.Kind.FLOAT, 0, 2),
        new Control("lighting.sparseTransparency", SettingGroup.SAMPLING, "rtx.sparseRendering.enableSparseAwareAlphaBlendSearch", KavoraSetting.Kind.BOOL, 0, 1),
        new Control("cache.records", SettingGroup.RADIANCE_CACHE, "rtx.spatiallyHashedRadianceCache.targetNumUpdateRecords", KavoraSetting.Kind.INT, 0, 262144),
        new Control("cache.combine", SettingGroup.RADIANCE_CACHE, "rtx.spatiallyHashedRadianceCache.combineUpdateAndQuery", KavoraSetting.Kind.BOOL, 0, 1),
        new Control("cache.primary", SettingGroup.RADIANCE_CACHE, "rtx.spatiallyHashedRadianceCache.queryForThinOpaquePrimaryView", KavoraSetting.Kind.BOOL, 0, 1),
        choice("cache.filter", SettingGroup.RADIANCE_CACHE, "rtx.spatiallyHashedRadianceCache.cellFilterMode", 0, 2),
        new Control("cache.spatialJitter", SettingGroup.RADIANCE_CACHE, "rtx.spatiallyHashedRadianceCache.primaryQuerySpatialJitter", KavoraSetting.Kind.BOOL, 0, 1),
        new Control("cache.lodJitter", SettingGroup.RADIANCE_CACHE, "rtx.spatiallyHashedRadianceCache.primaryQueryLodJitter", KavoraSetting.Kind.BOOL, 0, 1),
        new Control("lighting.direct", SettingGroup.DIRECT_LIGHTING, "rtx.enableDirectLighting", KavoraSetting.Kind.BOOL, 0, 1),
        new Control("lighting.secondary", SettingGroup.PATH_TRACING, "rtx.enableSecondaryBounces", KavoraSetting.Kind.BOOL, 0, 1),
        new Control("shadows.coloredDirect", SettingGroup.SHADOWS, "rtx.enableDirectTranslucentShadows", KavoraSetting.Kind.BOOL, 0, 1),
        new Control("shadows.coloredIndirect", SettingGroup.SHADOWS, "rtx.enableIndirectTranslucentShadows", KavoraSetting.Kind.BOOL, 0, 1),
        new Control("reflection.psrr", SettingGroup.REFLECTIONS, "rtx.enablePSRR", KavoraSetting.Kind.BOOL, 0, 1),
        new Control("reflection.pstr", SettingGroup.REFLECTIONS, "rtx.enablePSTR", KavoraSetting.Kind.BOOL, 0, 1),
        new Control("reflection.bounces", SettingGroup.REFLECTIONS, "rtx.psrrMaxBounces", KavoraSetting.Kind.INT, 1, 16),
        new Control("refraction.bounces", SettingGroup.REFLECTIONS, "rtx.pstrMaxBounces", KavoraSetting.Kind.INT, 1, 16),
        new Control("materials.normalStrength", SettingGroup.MATERIALS, "rtx.opaqueMaterial.normalIntensity", KavoraSetting.Kind.FLOAT, 0, 3),
        choice("materials.pom", SettingGroup.PARALLAX, "rtx.displacement.mode", 0, 3),
        new Control("materials.pomIterations", SettingGroup.PARALLAX, "rtx.displacement.maxIterations", KavoraSetting.Kind.INT, 8, 256),
        new Control("materials.pomDirect", SettingGroup.PARALLAX, "rtx.displacement.enableDirectLighting", KavoraSetting.Kind.BOOL, 0, 1),
        new Control("materials.pomIndirect", SettingGroup.PARALLAX, "rtx.displacement.enableIndirectLighting", KavoraSetting.Kind.BOOL, 0, 1),
        new Control("materials.pomNee", SettingGroup.PARALLAX, "rtx.displacement.enableNEECache", KavoraSetting.Kind.BOOL, 0, 1),
        new Control("materials.pomRestir", SettingGroup.PARALLAX, "rtx.displacement.enableReSTIRGI", KavoraSetting.Kind.BOOL, 0, 1),
        new Control("materials.pomIndirectHit", SettingGroup.PARALLAX, "rtx.displacement.enableIndirectHit", KavoraSetting.Kind.BOOL, 0, 1),
        new Control("materials.pomPsr", SettingGroup.PARALLAX, "rtx.displacement.enablePSR", KavoraSetting.Kind.BOOL, 0, 1),
        new Control("materials.pomTransmissionSilhouettes", SettingGroup.PARALLAX, "rtx.displacement.silhouettesBehindTranslucency", KavoraSetting.Kind.BOOL, 0, 1),
        new Control("water.normalStrength", SettingGroup.WATER, "rtx.translucentMaterial.normalIntensity", KavoraSetting.Kind.FLOAT, 0, 3),
        new Control("water.simulation", SettingGroup.WATER, "rtx.waterSim.enable", KavoraSetting.Kind.BOOL, 0, 1),
        new Control("water.windWaves", SettingGroup.WATER, "rtx.waterSim.windEnabled", KavoraSetting.Kind.BOOL, 0, 1),
        new Control("water.dropletRings", SettingGroup.WATER, "rtx.waterSim.ringsEnabled", KavoraSetting.Kind.BOOL, 0, 1),
        new Control("water.floatingSludge", SettingGroup.WATER, "rtx.waterSim.sludgeEnabled", KavoraSetting.Kind.BOOL, 0, 1),
        new Control("sss.inRefraction", SettingGroup.SUBSURFACE, "rtx.subsurface.enableThinOpaqueInRefractions", KavoraSetting.Kind.BOOL, 0, 1),
        new Control("sss.transmission", SettingGroup.SUBSURFACE, "rtx.subsurface.enableTransmission", KavoraSetting.Kind.BOOL, 0, 1),
        new Control("sss.singleScattering", SettingGroup.SUBSURFACE, "rtx.subsurface.enableTransmissionSingleScattering", KavoraSetting.Kind.BOOL, 0, 1),
        new Control("sss.transmissionSamples", SettingGroup.SUBSURFACE, "rtx.subsurface.transmissionBsdfSampleCount", KavoraSetting.Kind.INT, 1, 16),
        new Control("sss.scatteringSamples", SettingGroup.SUBSURFACE, "rtx.subsurface.transmissionSingleScatteringSampleCount", KavoraSetting.Kind.INT, 1, 16),
        new Control("sss.diffusionScale", SettingGroup.SUBSURFACE, "rtx.subsurface.diffusionProfileScale", KavoraSetting.Kind.FLOAT, 0, 3),
        new Control("bloom.strength", SettingGroup.BLOOM, "rtx.bloom.burnIntensity", KavoraSetting.Kind.FLOAT, 0, 5),
        new Control("bloom.threshold", SettingGroup.BLOOM, "rtx.bloom.luminanceThreshold", KavoraSetting.Kind.FLOAT, 0, 5),
        choice("image.tonemapper", SettingGroup.COLOR_GRADING, "rtx.tonemappingMode", 0, 1),
        new Control("image.localContrast", SettingGroup.TONEMAPPING, "rtx.localtonemap.boostLocalContrast", KavoraSetting.Kind.BOOL, 0, 1),
        new Control("image.localAces", SettingGroup.TONEMAPPING, "rtx.localtonemap.finalizeWithACES", KavoraSetting.Kind.BOOL, 0, 1),
        new Control("image.globalExposure", SettingGroup.COLOR_GRADING, "rtx.tonemap.exposureBias", KavoraSetting.Kind.FLOAT, -8, 8),
        new Control("image.grading", SettingGroup.COLOR_GRADING, "rtx.tonemap.colorGradingEnabled", KavoraSetting.Kind.BOOL, 0, 1),
        new Control("image.contrast", SettingGroup.COLOR_GRADING, "rtx.tonemap.contrast", KavoraSetting.Kind.FLOAT, 0, 2),
        new Control("image.saturation", SettingGroup.COLOR_GRADING, "rtx.tonemap.saturation", KavoraSetting.Kind.FLOAT, 0, 2),
        new Control("exposure.enabled", SettingGroup.EYE_ADAPTATION, "rtx.autoExposure.enabled", KavoraSetting.Kind.BOOL, 0, 1),
        new Control("exposure.speed", SettingGroup.EYE_ADAPTATION, "rtx.autoExposure.autoExposureSpeed", KavoraSetting.Kind.FLOAT, 0.1, 20),
        new Control("exposure.center", SettingGroup.EYE_ADAPTATION, "rtx.autoExposure.exposureCenterMeteringEnabled", KavoraSetting.Kind.BOOL, 0, 1),
        new Control("exposure.centerSize", SettingGroup.EYE_ADAPTATION, "rtx.autoExposure.centerMeteringSize", KavoraSetting.Kind.FLOAT, 0.01, 1),
        choice("exposure.average", SettingGroup.EYE_ADAPTATION, "rtx.autoExposure.exposureAverageMode", 0, 1));

    private KavoraEffectOptions() {}

    static void addTo(List<KavoraSetting<?>> settings, RemixAPI api) {
        for (Control control : CONTROLS) {
            String initial = api.getConfigVariableResolved(control.key());
            String label = control.labelKey();
            Object authored = KavoraQualityPresets.defaultValue(control.id());
            switch (control.kind()) {
                case BOOL -> {
                    boolean[] value = {Boolean.parseBoolean(initial)};
                    org.edtp.kavora.render.KavoraWaterRipples.updateOption(control.id(), value[0]);
                    settings.add(KavoraSetting.ofBool(control.id(), control.group(), label, label + ".tip",
                            authored == null ? value[0] : (Boolean) authored, () -> value[0], next -> {
                                api.setConfigVariable(control.key(), Boolean.toString(next));
                                org.edtp.kavora.render.KavoraWaterRipples.updateOption(control.id(), next);
                                value[0] = next;
                            }));
                }
                case INT -> {
                    int[] value = {Integer.parseInt(initial)};
                    KavoraSetting<Integer> setting = KavoraSetting.ofInt(control.id(), control.group(), label, label + ".tip",
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
                    settings.add(KavoraSetting.ofFloat(control.id(), control.group(), label, label + ".tip",
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

    private static void addColor(List<KavoraSetting<?>> settings, RemixAPI api, ColorControl control) {
        String[] components = api.getConfigVariableResolved(control.key()).replace("(", "").replace(")", "").trim().split("\\s*,\\s*");
        if (components.length != 3) throw new IllegalArgumentException("Invalid RGB runtime option: " + control.key());
        double[] value = {Double.parseDouble(components[0]), Double.parseDouble(components[1]), Double.parseDouble(components[2])};
        String[] channels = {"red", "green", "blue"};
        for (int channel = 0; channel < channels.length; channel++) {
            int index = channel;
            String id = control.id() + "." + channels[channel];
            String label = "kavora.effect." + id;
            settings.add(KavoraSetting.ofFloat(id, control.group(), label, label + ".tip", 0, 1, value[index],
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

