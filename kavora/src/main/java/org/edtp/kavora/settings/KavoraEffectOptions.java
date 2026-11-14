/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.settings;

import java.util.List;
import org.edtp.kavora.render.KavoraWaterRipples;
import org.edtp.kavora.sdk.RemixAPI;

/** Native visual controls, separated from display reconstruction and CPU/GPU scheduling. */
final class KavoraEffectOptions {
    @FunctionalInterface
    interface BooleanCallback {
        void accept(boolean value);
    }

    private static final BooleanCallback NO_BOOLEAN_CALLBACK = ignored -> {};

    record Control(String id, SettingGroup group, String key, KavoraSetting.Kind kind, double min, double max,
                   boolean choices, BooleanCallback booleanCallback) {
        String labelKey() { return "kavora.effect." + id; }
    }

    private static Control choice(String id, SettingGroup group, String key, int min, int max) {
        return new Control(id, group, key, KavoraSetting.Kind.INT, min, max, true, NO_BOOLEAN_CALLBACK);
    }

    private static Control toggle(String id, SettingGroup group, String key) {
        return toggle(id, group, key, NO_BOOLEAN_CALLBACK);
    }

    private static Control toggle(String id, SettingGroup group, String key, BooleanCallback callback) {
        return new Control(id, group, key, KavoraSetting.Kind.BOOL, 0, 1, false, callback);
    }

    private static Control decimal(String id, SettingGroup group, String key, double min, double max) {
        return new Control(id, group, key, KavoraSetting.Kind.FLOAT, min, max, false, NO_BOOLEAN_CALLBACK);
    }

    private static Control integer(String id, SettingGroup group, String key, int min, int max) {
        return new Control(id, group, key, KavoraSetting.Kind.INT, min, max, false, NO_BOOLEAN_CALLBACK);
    }

    record ColorControl(String id, SettingGroup group, String key) {}

    static final List<ColorControl> COLORS = List.of(
            new ColorControl("image.colorBalance", SettingGroup.COLOR_GRADING, "rtx.tonemap.colorBalance"));

    static final List<Control> CONTROLS = List.of(
        toggle("nightSky.enabled", SettingGroup.NIGHT_SKY, "rtx.nightSky.enabled"),
        decimal("nightSky.starIntensity", SettingGroup.NIGHT_SKY, "rtx.nightSky.starIntensity", 0, 4),
        decimal("nightSky.milkyWayIntensity", SettingGroup.NIGHT_SKY, "rtx.nightSky.milkyWayIntensity", 0, 4),
        decimal("nightSky.meteorRate", SettingGroup.NIGHT_SKY, "rtx.nightSky.meteorRate", 0, 60),
        decimal("nightSky.meteorIntensity", SettingGroup.NIGHT_SKY, "rtx.nightSky.meteorIntensity", 0, 8),
        toggle("endSky.enabled", SettingGroup.END_SKY, "rtx.endSky.enabled"),
        decimal("endSky.intensity", SettingGroup.END_SKY, "rtx.endSky.intensity", 0, 4),
        decimal("endSky.backgroundIntensity", SettingGroup.END_SKY, "rtx.endSky.backgroundIntensity", 0, 4),
        decimal("endSky.haloIntensity", SettingGroup.END_SKY, "rtx.endSky.haloIntensity", 0, 4),
        decimal("endSky.animationSpeed", SettingGroup.END_SKY, "rtx.endSky.animationSpeed", 0, 4),
        decimal("endSky.rotation", SettingGroup.END_SKY, "rtx.endSky.rotation", 0, 360),
        decimal("endSky.elevation", SettingGroup.END_SKY, "rtx.endSky.elevation", -90, 90),
        choice("clouds.transport", SettingGroup.CLOUD_QUALITY, "rtx.clouds.transportMode", 0, 1),
        integer("clouds.skyResolution", SettingGroup.CLOUD_QUALITY, "rtx.clouds.skyResolution", 128, 2048),
        decimal("clouds.windSpeed", SettingGroup.CLOUDS, "rtx.clouds.windSpeed", 0, 5),
        decimal("clouds.anisotropy", SettingGroup.CLOUDS, "rtx.clouds.anisotropy", 0, 0.95),
        decimal("clouds.multipleScattering", SettingGroup.CLOUDS, "rtx.clouds.multipleScattering", 0, 1),
        decimal("clouds.coverage", SettingGroup.CLOUDS, "rtx.clouds.coverage", 0, 1),
        decimal("clouds.altitudeOffset", SettingGroup.CLOUDS, "rtx.clouds.altitudeOffsetMeters", 0, 2000),
        decimal("clouds.thickness", SettingGroup.CLOUDS, "rtx.clouds.thicknessMeters", 8, 3000),
        decimal("clouds.shapeScale", SettingGroup.CLOUDS, "rtx.clouds.shapeScaleMeters", 64, 4096),
        decimal("clouds.density", SettingGroup.CLOUDS, "rtx.clouds.extinctionPerMeter", 0, 0.2),
        decimal("clouds.distance", SettingGroup.CLOUDS, "rtx.clouds.maxDistanceMeters", 512, 60000),
        integer("clouds.samples", SettingGroup.CLOUD_QUALITY, "rtx.clouds.steps", 8, 256),
        integer("clouds.shadowSamples", SettingGroup.CLOUD_QUALITY, "rtx.clouds.lightSteps", 1, 16),
        decimal("mist.baseHeight", SettingGroup.GROUND_MIST, "rtx.volumetrics.groundMistBaseHeightMeters", -64, 320),
        decimal("mist.heightFalloff", SettingGroup.GROUND_MIST, "rtx.volumetrics.groundMistFalloffMeters", 0.1, 128),
        decimal("mist.regionSize", SettingGroup.GROUND_MIST, "rtx.volumetrics.groundMistRegionSizeMeters", 16, 512),
        toggle("fog.translucentShadows", SettingGroup.FOG, "rtx.volumetrics.enableTranslucentShadows"),
        toggle("fog.noise", SettingGroup.FOG_NOISE, "rtx.volumetrics.enableHeterogeneousFog"),
        decimal("fog.noiseSpeed", SettingGroup.FOG_NOISE, "rtx.volumetrics.noiseFieldTimeScale", 0, 3),
        decimal("fog.noiseScale", SettingGroup.FOG_NOISE, "rtx.volumetrics.noiseFieldDensityScale", 0, 3),
        decimal("fog.noiseExponent", SettingGroup.FOG_NOISE, "rtx.volumetrics.noiseFieldDensityExponent", 0.1, 5),
        decimal("fog.noiseFrequency", SettingGroup.FOG_NOISE, "rtx.volumetrics.noiseFieldInitialFrequencyPerMeter", 0.005, 16),
        integer("fog.noiseOctaves", SettingGroup.FOG_NOISE, "rtx.volumetrics.noiseFieldOctaves", 1, 8),
        decimal("fog.noiseLacunarity", SettingGroup.FOG_NOISE, "rtx.volumetrics.noiseFieldLacunarity", 1, 4),
        decimal("fog.noiseGain", SettingGroup.FOG_NOISE, "rtx.volumetrics.noiseFieldGain", 0, 1),
        integer("fog.grid", SettingGroup.FOG_QUALITY, "rtx.volumetrics.froxelGridResolutionScale", 4, 32),
        choice("fog.integration", SettingGroup.FOG_QUALITY, "rtx.volumetrics.fogIntegrationMode", 0, 1),
        integer("fog.integrationResolution", SettingGroup.FOG_QUALITY, "rtx.volumetrics.fogIntegrationPixelStride", 2, 32),
        integer("fog.initialSamples", SettingGroup.FOG_QUALITY, "rtx.volumetrics.initialRISSampleCount", 1, 64),
        integer("fog.spatialSamples", SettingGroup.FOG_QUALITY, "rtx.volumetrics.spatialReuseMaxSampleCount", 1, 16),
        integer("fog.lightingGrid", SettingGroup.FOG_QUALITY, "rtx.volumetrics.restirGridScale", 1, 8),
        integer("fog.lightingDepthSlices", SettingGroup.FOG_QUALITY, "rtx.volumetrics.restirFroxelDepthSlices", 16, 256),
        integer("fog.depthSlices", SettingGroup.FOG_QUALITY, "rtx.volumetrics.froxelDepthSlices", 32, 256),
        decimal("fog.depthDistribution", SettingGroup.FOG_QUALITY, "rtx.volumetrics.froxelDepthSliceDistributionExponent", 1, 4),
        integer("fog.history", SettingGroup.FOG_QUALITY, "rtx.volumetrics.maxAccumulationFrames", 1, 254),
        choice("lighting.indirect", SettingGroup.PATH_TRACING, "rtx.integrateIndirectMode", 0, 3),
        integer("lighting.minBounces", SettingGroup.PATH_TRACING, "rtx.pathMinBounces", 0, 15),
        integer("lighting.bounces", SettingGroup.PATH_TRACING, "rtx.pathMaxBounces", 0, 15),
        toggle("lighting.rtxdi", SettingGroup.DIRECT_LIGHTING, "rtx.useRTXDI"),
        integer("lighting.initialSamples", SettingGroup.DIRECT_LIGHTING, "rtx.di.initialSampleCount", 1, 64),
        integer("lighting.spatialSamples", SettingGroup.DIRECT_LIGHTING, "rtx.di.spatialSamples", 1, 16),
        integer("lighting.disocclusionSamples", SettingGroup.DIRECT_LIGHTING, "rtx.di.disocclusionSamples", 1, 32),
        integer("lighting.risSamples", SettingGroup.SAMPLING, "rtx.risLightSampleCount", 1, 64),
        toggle("lighting.sparse", SettingGroup.SAMPLING, "rtx.sparseRendering.enableSparseRendering"),
        toggle("lighting.adaptiveSampling", SettingGroup.SAMPLING, "rtx.sparseRendering.enableAdaptiveSamplingRate"),
        decimal("lighting.samplingQuality", SettingGroup.SAMPLING, "rtx.sparseRendering.quality", 0, 1),
        decimal("lighting.samplingRate", SettingGroup.SAMPLING, "rtx.sparseRendering.samplingRate", 0.01, 1),
        decimal("lighting.samplingExponent", SettingGroup.SAMPLING, "rtx.sparseRendering.samplingRateResolutionExponent", 0, 2),
        toggle("lighting.sparseTransparency", SettingGroup.SAMPLING, "rtx.sparseRendering.enableSparseAwareAlphaBlendSearch"),
        integer("cache.records", SettingGroup.RADIANCE_CACHE, "rtx.spatiallyHashedRadianceCache.targetNumUpdateRecords", 0, 262144),
        toggle("cache.combine", SettingGroup.RADIANCE_CACHE, "rtx.spatiallyHashedRadianceCache.combineUpdateAndQuery"),
        toggle("cache.primary", SettingGroup.RADIANCE_CACHE, "rtx.spatiallyHashedRadianceCache.queryForThinOpaquePrimaryView"),
        choice("cache.filter", SettingGroup.RADIANCE_CACHE, "rtx.spatiallyHashedRadianceCache.cellFilterMode", 0, 2),
        toggle("cache.spatialJitter", SettingGroup.RADIANCE_CACHE, "rtx.spatiallyHashedRadianceCache.primaryQuerySpatialJitter"),
        toggle("cache.lodJitter", SettingGroup.RADIANCE_CACHE, "rtx.spatiallyHashedRadianceCache.primaryQueryLodJitter"),
        integer("nrc.trainingIterations", SettingGroup.NRC, "rtx.neuralRadianceCache.targetNumTrainingIterations", 1, 16),
        decimal("nrc.trainingFeedback", SettingGroup.NRC, "rtx.neuralRadianceCache.selfTrainingAttenuation", 0, 1),
        decimal("nrc.featureSize", SettingGroup.NRC, "rtx.neuralRadianceCache.smallestResolvableFeatureSizeMeters", 0.001, 1),
        decimal("nrc.terminationThreshold", SettingGroup.NRC, "rtx.neuralRadianceCache.terminationHeuristicThreshold", 0.001, 1),
        toggle("lighting.direct", SettingGroup.DIRECT_LIGHTING, "rtx.enableDirectLighting"),
        toggle("lighting.secondary", SettingGroup.PATH_TRACING, "rtx.enableSecondaryBounces"),
        toggle("shadows.coloredDirect", SettingGroup.SHADOWS, "rtx.enableDirectTranslucentShadows"),
        toggle("shadows.coloredIndirect", SettingGroup.SHADOWS, "rtx.enableIndirectTranslucentShadows"),
        toggle("reflection.psrr", SettingGroup.REFLECTIONS, "rtx.enablePSRR"),
        toggle("reflection.pstr", SettingGroup.REFLECTIONS, "rtx.enablePSTR"),
        integer("reflection.bounces", SettingGroup.REFLECTIONS, "rtx.psrrMaxBounces", 1, 16),
        integer("refraction.bounces", SettingGroup.REFLECTIONS, "rtx.pstrMaxBounces", 1, 16),
        decimal("materials.normalStrength", SettingGroup.MATERIALS, "rtx.opaqueMaterial.normalIntensity", 0, 3),
        choice("materials.pom", SettingGroup.PARALLAX, "rtx.displacement.mode", 0, 3),
        integer("materials.pomIterations", SettingGroup.PARALLAX, "rtx.displacement.maxIterations", 8, 256),
        toggle("materials.pomDirect", SettingGroup.PARALLAX, "rtx.displacement.enableDirectLighting"),
        toggle("materials.pomIndirect", SettingGroup.PARALLAX, "rtx.displacement.enableIndirectLighting"),
        toggle("materials.pomNee", SettingGroup.PARALLAX, "rtx.displacement.enableNEECache"),
        toggle("materials.pomRestir", SettingGroup.PARALLAX, "rtx.displacement.enableReSTIRGI"),
        toggle("materials.pomIndirectHit", SettingGroup.PARALLAX, "rtx.displacement.enableIndirectHit"),
        toggle("materials.pomPsr", SettingGroup.PARALLAX, "rtx.displacement.enablePSR"),
        toggle("materials.pomTransmissionSilhouettes", SettingGroup.PARALLAX, "rtx.displacement.silhouettesBehindTranslucency"),
        decimal("water.normalStrength", SettingGroup.WATER, "rtx.translucentMaterial.normalIntensity", 0, 3),
        toggle("water.simulation", SettingGroup.WATER, "rtx.waterSim.enable",
                KavoraWaterRipples::setSimulationEnabled),
        toggle("water.windWaves", SettingGroup.WATER, "rtx.waterSim.windEnabled"),
        toggle("water.dropletRings", SettingGroup.WATER, "rtx.waterSim.ringsEnabled",
                KavoraWaterRipples::setDropletRingsEnabled),
        toggle("water.floatingSludge", SettingGroup.WATER, "rtx.waterSim.sludgeEnabled",
                KavoraWaterRipples::setFloatingSludgeEnabled),
        toggle("particles.spawnBySurfaceArea", SettingGroup.SIMULATED_PARTICLES, "rtx.particles.globalPreset.spawnBySurfaceArea"),
        decimal("particles.minScreenSize", SettingGroup.SIMULATED_PARTICLES, "rtx.particles.globalPreset.minScreenSize", 0, 64),
        decimal("particles.maxScreenSize", SettingGroup.SIMULATED_PARTICLES, "rtx.particles.globalPreset.maxScreenSize", 0, 512),
        decimal("particles.screenSizeCoverage", SettingGroup.SIMULATED_PARTICLES, "rtx.particles.globalPreset.screenSizeCoverage", 0, 1),
        toggle("sss.inRefraction", SettingGroup.SUBSURFACE, "rtx.subsurface.enableThinOpaqueInRefractions"),
        toggle("sss.transmission", SettingGroup.SUBSURFACE, "rtx.subsurface.enableTransmission"),
        toggle("sss.singleScattering", SettingGroup.SUBSURFACE, "rtx.subsurface.enableTransmissionSingleScattering"),
        integer("sss.transmissionSamples", SettingGroup.SUBSURFACE, "rtx.subsurface.transmissionBsdfSampleCount", 1, 16),
        integer("sss.scatteringSamples", SettingGroup.SUBSURFACE, "rtx.subsurface.transmissionSingleScatteringSampleCount", 1, 16),
        decimal("sss.diffusionScale", SettingGroup.SUBSURFACE, "rtx.subsurface.diffusionProfileScale", 0, 3),
        decimal("bloom.strength", SettingGroup.BLOOM, "rtx.bloom.burnIntensity", 0, 5),
        decimal("bloom.threshold", SettingGroup.BLOOM, "rtx.bloom.luminanceThreshold", 0, 5),
        integer("bloom.radius", SettingGroup.BLOOM, "rtx.bloom.steps", 1, 8),
        choice("image.tonemapper", SettingGroup.COLOR_GRADING, "rtx.tonemappingMode", 0, 1),
        toggle("image.localContrast", SettingGroup.TONEMAPPING, "rtx.localtonemap.boostLocalContrast"),
        toggle("image.localAces", SettingGroup.TONEMAPPING, "rtx.localtonemap.finalizeWithACES"),
        decimal("image.globalExposure", SettingGroup.COLOR_GRADING, "rtx.tonemap.exposureBias", -8, 8),
        toggle("image.grading", SettingGroup.COLOR_GRADING, "rtx.tonemap.colorGradingEnabled"),
        decimal("image.contrast", SettingGroup.COLOR_GRADING, "rtx.tonemap.contrast", 0, 2),
        toggle("lens.enabled", SettingGroup.LENS, "rtx.postfx.enable"),
        toggle("lens.chromaticAberration", SettingGroup.LENS, "rtx.postfx.enableChromaticAberration"),
        decimal("lens.chromaticAmount", SettingGroup.LENS, "rtx.postfx.chromaticAberrationAmount", 0, 0.2),
        decimal("lens.chromaticFalloff", SettingGroup.LENS, "rtx.postfx.chromaticCenterAttenuationAmount", 0, 1),
        toggle("lens.vignette", SettingGroup.LENS, "rtx.postfx.enableVignette"),
        decimal("lens.vignetteIntensity", SettingGroup.LENS, "rtx.postfx.vignetteIntensity", 0, 1),
        decimal("lens.vignetteRadius", SettingGroup.LENS, "rtx.postfx.vignetteRadius", 0, 2),
        decimal("lens.vignetteSoftness", SettingGroup.LENS, "rtx.postfx.vignetteSoftness", 0, 1),
        toggle("lens.motionBlur", SettingGroup.MOTION_BLUR, "rtx.postfx.enableMotionBlur"),
        decimal("lens.shutter", SettingGroup.MOTION_BLUR, "rtx.postfx.exposureFraction", 0, 1),
        decimal("lens.blurRadius", SettingGroup.MOTION_BLUR, "rtx.postfx.blurDiameterFraction", 0, 0.2),
        integer("lens.blurSamples", SettingGroup.MOTION_BLUR, "rtx.postfx.motionBlurSampleCount", 1, 16),
        decimal("image.inversionAmount", SettingGroup.INVERSION, "rtx.postfx.inversionAmount", 0, 1),
        choice("image.inversionMode", SettingGroup.INVERSION, "rtx.postfx.inversionMode", 0, 1),
        decimal("image.inversionThreshold", SettingGroup.INVERSION, "rtx.postfx.inversionThreshold", 0, 1),
        toggle("exposure.enabled", SettingGroup.EYE_ADAPTATION, "rtx.autoExposure.enabled"),
        decimal("exposure.speed", SettingGroup.EYE_ADAPTATION, "rtx.autoExposure.autoExposureSpeed", 0.1, 20),
        toggle("exposure.center", SettingGroup.EYE_ADAPTATION, "rtx.autoExposure.exposureCenterMeteringEnabled"),
        decimal("exposure.centerSize", SettingGroup.EYE_ADAPTATION, "rtx.autoExposure.centerMeteringSize", 0.01, 1),
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
                    control.booleanCallback().accept(value[0]);
                    settings.add(KavoraSetting.ofBool(control.id(), control.group(), label, label + ".tip",
                            authored == null ? value[0] : (Boolean) authored, () -> value[0], next -> {
                                api.setConfigVariable(control.key(), Boolean.toString(next));
                                control.booleanCallback().accept(next);
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

