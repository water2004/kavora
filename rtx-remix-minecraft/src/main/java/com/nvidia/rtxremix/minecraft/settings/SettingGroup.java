/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

/** Ordering of constants drives ordering of rows in the settings screen. */
public enum SettingGroup {
    RENDER_DISTANCE(SettingCategory.PERFORMANCE, "rtxremix.settings.group.renderDistance"),
    FRAMERATE(SettingCategory.PERFORMANCE, "rtxremix.settings.group.framerate"),
    CELESTIAL_LIGHTS(SettingCategory.LIGHTING, "rtxremix.settings.group.celestialLights"),
    BLOCK_LIGHTS(SettingCategory.LIGHTING, "rtxremix.settings.group.blockLights"),
    DIRECT_LIGHTING(SettingCategory.RAY_TRACING, "rtxremix.settings.group.directLighting"),
    PATH_TRACING(SettingCategory.RAY_TRACING, "rtxremix.settings.group.pathTracing"),
    SAMPLING(PATH_TRACING, "rtxremix.settings.group.sampling"),
    RADIANCE_CACHE(PATH_TRACING, "rtxremix.settings.group.radianceCache"),
    SHADOWS(SettingCategory.RAY_TRACING, "rtxremix.settings.group.shadows"),
    REFLECTIONS(SettingCategory.RAY_TRACING, "rtxremix.settings.group.reflections"),
    MATERIALS(SettingCategory.MATERIALS, "rtxremix.settings.group.materials"),
    PARALLAX(MATERIALS, "rtxremix.settings.group.parallax"),
    WATER(SettingCategory.MATERIALS, "rtxremix.settings.group.water"),
    SUBSURFACE(SettingCategory.MATERIALS, "rtxremix.settings.group.subsurface"),
    SKY(SettingCategory.SKY, "rtxremix.settings.group.sky"),
    NIGHT_SKY(SettingCategory.SKY, "rtxremix.settings.group.nightSky"),
    END_SKY(SettingCategory.SKY, "rtxremix.settings.group.endSky"),
    CLOUDS(SettingCategory.WEATHER, "rtxremix.settings.group.clouds"),
    CLOUD_QUALITY(CLOUDS, "rtxremix.settings.group.cloudQuality"),
    FOG(SettingCategory.WEATHER, "rtxremix.settings.group.fog"),
    FOG_QUALITY(FOG, "rtxremix.settings.group.fogQuality"),
    FOG_NOISE(FOG, "rtxremix.settings.group.fogNoise"),
    GROUND_MIST(SettingCategory.WEATHER, "rtxremix.settings.group.groundMist"),
    TONEMAPPING(SettingCategory.IMAGE, "rtxremix.settings.group.tonemapping"),
    BLOOM(SettingCategory.IMAGE, "rtxremix.settings.group.bloom"),
    COLOR_GRADING(SettingCategory.IMAGE, "rtxremix.settings.group.colorGrading"),
    EYE_ADAPTATION(SettingCategory.IMAGE, "rtxremix.settings.group.eyeAdaptation");

    private final SettingCategory category;
    private final String labelKey;
    private final SettingGroup parent;

    SettingGroup(SettingCategory category, String labelKey) {
        this.category = category;
        this.labelKey = labelKey;
        this.parent = null;
    }

    SettingGroup(SettingGroup parent, String labelKey) {
        this.category = parent.category;
        this.labelKey = labelKey;
        this.parent = parent;
    }

    SettingGroup parent() {
        return parent;
    }

    boolean contains(SettingGroup group) {
        for (var ancestor = group; ancestor != null; ancestor = ancestor.parent) {
            if (ancestor == this) return true;
        }
        return false;
    }

    SettingCategory category() {
        return category;
    }

    String labelKey() {
        return labelKey;
    }
}
