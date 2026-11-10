/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.settings;

/** Ordering of constants drives ordering of rows in the settings screen. */
public enum SettingGroup {
    RENDER_DISTANCE(SettingCategory.PERFORMANCE, "kavora.settings.group.renderDistance"),
    FRAMERATE(SettingCategory.PERFORMANCE, "kavora.settings.group.framerate"),
    CELESTIAL_LIGHTS(SettingCategory.LIGHTING, "kavora.settings.group.celestialLights"),
    BLOCK_LIGHTS(SettingCategory.LIGHTING, "kavora.settings.group.blockLights"),
    DIRECT_LIGHTING(SettingCategory.RAY_TRACING, "kavora.settings.group.directLighting"),
    PATH_TRACING(SettingCategory.RAY_TRACING, "kavora.settings.group.pathTracing"),
    SAMPLING(PATH_TRACING, "kavora.settings.group.sampling"),
    RADIANCE_CACHE(PATH_TRACING, "kavora.settings.group.radianceCache"),
    SHADOWS(SettingCategory.RAY_TRACING, "kavora.settings.group.shadows"),
    REFLECTIONS(SettingCategory.RAY_TRACING, "kavora.settings.group.reflections"),
    MATERIALS(SettingCategory.MATERIALS, "kavora.settings.group.materials"),
    PARALLAX(MATERIALS, "kavora.settings.group.parallax"),
    WATER(SettingCategory.MATERIALS, "kavora.settings.group.water"),
    SUBSURFACE(SettingCategory.MATERIALS, "kavora.settings.group.subsurface"),
    SKY(SettingCategory.SKY, "kavora.settings.group.sky"),
    NIGHT_SKY(SettingCategory.SKY, "kavora.settings.group.nightSky"),
    END_SKY(SettingCategory.SKY, "kavora.settings.group.endSky"),
    CLOUDS(SettingCategory.WEATHER, "kavora.settings.group.clouds"),
    CLOUD_QUALITY(CLOUDS, "kavora.settings.group.cloudQuality"),
    FOG(SettingCategory.WEATHER, "kavora.settings.group.fog"),
    FOG_QUALITY(FOG, "kavora.settings.group.fogQuality"),
    FOG_NOISE(FOG, "kavora.settings.group.fogNoise"),
    GROUND_MIST(SettingCategory.WEATHER, "kavora.settings.group.groundMist"),
    TONEMAPPING(SettingCategory.IMAGE, "kavora.settings.group.tonemapping"),
    BLOOM(SettingCategory.IMAGE, "kavora.settings.group.bloom"),
    COLOR_GRADING(SettingCategory.IMAGE, "kavora.settings.group.colorGrading"),
    EYE_ADAPTATION(SettingCategory.IMAGE, "kavora.settings.group.eyeAdaptation"),
    NEURAL_RENDERING(SettingCategory.NEURAL_RENDERING, "kavora.video.neuralRendering.settings");

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
