/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import com.nvidia.rtxremix.RemixAPI;
import java.util.List;

/** Native visual controls, separated from display reconstruction and CPU/GPU scheduling. */
final class RtxEffectOptions {
    record Control(String id, SettingGroup group, String key, RtxSetting.Kind kind, double min, double max) {
        String labelKey() { return "rtxremix.effect." + id; }
    }

    record ColorControl(String id, SettingGroup group, String key) {}

    static final List<ColorControl> COLORS = List.of(
            new ColorControl("fog.transmittance", SettingGroup.FOG, "rtx.volumetrics.transmittanceColor"),
            new ColorControl("fog.scatteringAlbedo", SettingGroup.FOG, "rtx.volumetrics.singleScatteringAlbedo"),
            new ColorControl("image.colorBalance", SettingGroup.COLOR_GRADING, "rtx.tonemap.colorBalance"));

    static final List<Control> CONTROLS = List.of(
        new Control("clouds.windSpeed", SettingGroup.CLOUDS, "rtx.clouds.windSpeed", RtxSetting.Kind.FLOAT, 0, 5),
        new Control("clouds.anisotropy", SettingGroup.CLOUDS, "rtx.clouds.anisotropy", RtxSetting.Kind.FLOAT, 0, 0.95),
        new Control("clouds.multipleScattering", SettingGroup.CLOUDS, "rtx.clouds.multipleScattering", RtxSetting.Kind.FLOAT, 0, 1),
        new Control("clouds.coverage", SettingGroup.CLOUDS, "rtx.clouds.coverage", RtxSetting.Kind.FLOAT, 0, 1),
        new Control("clouds.thickness", SettingGroup.CLOUDS, "rtx.clouds.thicknessMeters", RtxSetting.Kind.FLOAT, 8, 256),
        new Control("clouds.shapeScale", SettingGroup.CLOUDS, "rtx.clouds.shapeScaleMeters", RtxSetting.Kind.FLOAT, 64, 1024),
        new Control("clouds.density", SettingGroup.CLOUDS, "rtx.clouds.extinctionPerMeter", RtxSetting.Kind.FLOAT, 0, 0.2),
        new Control("clouds.distance", SettingGroup.CLOUDS, "rtx.clouds.maxDistanceMeters", RtxSetting.Kind.FLOAT, 512, 8192),
        new Control("clouds.samples", SettingGroup.CLOUDS, "rtx.clouds.steps", RtxSetting.Kind.INT, 8, 256),
        new Control("clouds.shadowSamples", SettingGroup.CLOUDS, "rtx.clouds.lightSteps", RtxSetting.Kind.INT, 1, 16),
        new Control("fog.range", SettingGroup.FOG, "rtx.volumetrics.transmittanceMeasurementDistanceMeters", RtxSetting.Kind.FLOAT, 1, 2000),
        new Control("fog.baseHeight", SettingGroup.FOG, "rtx.volumetrics.heightFogBaseHeightMeters", RtxSetting.Kind.FLOAT, -64, 320),
        new Control("fog.heightFalloff", SettingGroup.FOG, "rtx.volumetrics.heightFogFalloffMeters", RtxSetting.Kind.FLOAT, 0, 128),
        new Control("mist.baseHeight", SettingGroup.GROUND_MIST, "rtx.volumetrics.groundMistBaseHeightMeters", RtxSetting.Kind.FLOAT, -64, 320),
        new Control("mist.heightFalloff", SettingGroup.GROUND_MIST, "rtx.volumetrics.groundMistFalloffMeters", RtxSetting.Kind.FLOAT, 0.1, 128),
        new Control("mist.regionSize", SettingGroup.GROUND_MIST, "rtx.volumetrics.groundMistRegionSizeMeters", RtxSetting.Kind.FLOAT, 16, 512),
        new Control("fog.anisotropy", SettingGroup.FOG, "rtx.volumetrics.anisotropy", RtxSetting.Kind.FLOAT, -0.95, 0.95),
        new Control("fog.translucentShadows", SettingGroup.FOG, "rtx.volumetrics.enableTranslucentShadows", RtxSetting.Kind.BOOL, 0, 1),
        new Control("fog.noise", SettingGroup.FOG, "rtx.volumetrics.enableHeterogeneousFog", RtxSetting.Kind.BOOL, 0, 1),
        new Control("fog.noiseSpeed", SettingGroup.FOG, "rtx.volumetrics.noiseFieldTimeScale", RtxSetting.Kind.FLOAT, 0, 3),
        new Control("fog.noiseScale", SettingGroup.FOG, "rtx.volumetrics.noiseFieldDensityScale", RtxSetting.Kind.FLOAT, 0, 3),
        new Control("fog.noiseExponent", SettingGroup.FOG, "rtx.volumetrics.noiseFieldDensityExponent", RtxSetting.Kind.FLOAT, 0.1, 5),
        new Control("fog.noiseFrequency", SettingGroup.FOG, "rtx.volumetrics.noiseFieldInitialFrequencyPerMeter", RtxSetting.Kind.FLOAT, 0.005, 16),
        new Control("shadows.direct", SettingGroup.SHADOWS, "rtx.enableDirectLighting", RtxSetting.Kind.BOOL, 0, 1),
        new Control("shadows.indirect", SettingGroup.SHADOWS, "rtx.enableSecondaryBounces", RtxSetting.Kind.BOOL, 0, 1),
        new Control("shadows.coloredDirect", SettingGroup.SHADOWS, "rtx.enableDirectTranslucentShadows", RtxSetting.Kind.BOOL, 0, 1),
        new Control("shadows.coloredIndirect", SettingGroup.SHADOWS, "rtx.enableIndirectTranslucentShadows", RtxSetting.Kind.BOOL, 0, 1),
        new Control("reflection.psrr", SettingGroup.REFLECTIONS, "rtx.enablePSRR", RtxSetting.Kind.BOOL, 0, 1),
        new Control("reflection.pstr", SettingGroup.REFLECTIONS, "rtx.enablePSTR", RtxSetting.Kind.BOOL, 0, 1),
        new Control("reflection.bounces", SettingGroup.REFLECTIONS, "rtx.psrrMaxBounces", RtxSetting.Kind.INT, 1, 16),
        new Control("refraction.bounces", SettingGroup.REFLECTIONS, "rtx.pstrMaxBounces", RtxSetting.Kind.INT, 1, 16),
        new Control("materials.normalStrength", SettingGroup.MATERIALS, "rtx.opaqueMaterial.normalIntensity", RtxSetting.Kind.FLOAT, 0, 3),
        new Control("water.normalStrength", SettingGroup.WATER, "rtx.translucentMaterial.normalIntensity", RtxSetting.Kind.FLOAT, 0, 3),
        new Control("sss.inRefraction", SettingGroup.SUBSURFACE, "rtx.subsurface.enableThinOpaqueInRefractions", RtxSetting.Kind.BOOL, 0, 1),
        new Control("sss.transmission", SettingGroup.SUBSURFACE, "rtx.subsurface.enableTransmission", RtxSetting.Kind.BOOL, 0, 1),
        new Control("sss.singleScattering", SettingGroup.SUBSURFACE, "rtx.subsurface.enableTransmissionSingleScattering", RtxSetting.Kind.BOOL, 0, 1),
        new Control("sss.diffusionScale", SettingGroup.SUBSURFACE, "rtx.subsurface.diffusionProfileScale", RtxSetting.Kind.FLOAT, 0, 3),
        new Control("bloom.strength", SettingGroup.BLOOM, "rtx.bloom.burnIntensity", RtxSetting.Kind.FLOAT, 0, 5),
        new Control("bloom.threshold", SettingGroup.BLOOM, "rtx.bloom.luminanceThreshold", RtxSetting.Kind.FLOAT, 0, 5),
        new Control("image.tonemapper", SettingGroup.COLOR_GRADING, "rtx.tonemappingMode", RtxSetting.Kind.INT, 0, 1),
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
        new Control("exposure.average", SettingGroup.EYE_ADAPTATION, "rtx.autoExposure.exposureAverageMode", RtxSetting.Kind.INT, 0, 1));

    private RtxEffectOptions() {}

    static void addTo(List<RtxSetting<?>> settings, RemixAPI api) {
        for (Control control : CONTROLS) {
            String initial = api.getConfigVariableResolved(control.key());
            String label = control.labelKey();
            switch (control.kind()) {
                case BOOL -> {
                    boolean[] value = {Boolean.parseBoolean(initial)};
                    settings.add(RtxSetting.ofBool(control.id(), control.group(), label, label + ".tip",
                            value[0], () -> value[0], next -> {
                                api.setConfigVariable(control.key(), Boolean.toString(next));
                                value[0] = next;
                            }));
                }
                case INT -> {
                    int[] value = {Integer.parseInt(initial)};
                    settings.add(RtxSetting.ofInt(control.id(), control.group(), label, label + ".tip",
                            Math.min((int) control.min(), value[0]), Math.max((int) control.max(), value[0]),
                            value[0], () -> value[0], next -> {
                                api.setConfigVariable(control.key(), Integer.toString(next));
                                value[0] = next;
                            }));
                }
                case FLOAT -> {
                    double[] value = {Double.parseDouble(initial)};
                    settings.add(RtxSetting.ofFloat(control.id(), control.group(), label, label + ".tip",
                            Math.min(control.min(), value[0]), Math.max(control.max(), value[0]),
                            value[0], () -> value[0], next -> {
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

