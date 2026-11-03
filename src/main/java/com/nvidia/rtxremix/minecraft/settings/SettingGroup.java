/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

/** Ordering of constants drives ordering of rows in the settings screen. */
public enum SettingGroup {
    RENDER_DISTANCE(SettingCategory.PERFORMANCE, "rtxremix.settings.group.renderDistance"),
    FRAMERATE(SettingCategory.PERFORMANCE, "rtxremix.settings.group.framerate"),
    SKY(SettingCategory.ATMOSPHERE, "rtxremix.settings.group.sky"),
    CLOUDS(SettingCategory.ATMOSPHERE, "rtxremix.settings.group.clouds"),
    FOG(SettingCategory.ATMOSPHERE, "rtxremix.settings.group.fog"),
    GROUND_MIST(SettingCategory.ATMOSPHERE, "rtxremix.settings.group.groundMist"),
    CELESTIAL_LIGHTS(SettingCategory.LIGHTING, "rtxremix.settings.group.celestialLights"),
    BLOCK_LIGHTS(SettingCategory.LIGHTING, "rtxremix.settings.group.blockLights"),
    SHADOWS(SettingCategory.LIGHTING, "rtxremix.settings.group.shadows"),
    REFLECTIONS(SettingCategory.LIGHTING, "rtxremix.settings.group.reflections"),
    TONEMAPPING(SettingCategory.IMAGE, "rtxremix.settings.group.tonemapping"),
    BLOOM(SettingCategory.IMAGE, "rtxremix.settings.group.bloom"),
    COLOR_GRADING(SettingCategory.IMAGE, "rtxremix.settings.group.colorGrading"),
    EYE_ADAPTATION(SettingCategory.IMAGE, "rtxremix.settings.group.eyeAdaptation"),
    WATER(SettingCategory.MATERIALS, "rtxremix.settings.group.water"),
    SUBSURFACE(SettingCategory.MATERIALS, "rtxremix.settings.group.subsurface"),
    MATERIALS(SettingCategory.MATERIALS, "rtxremix.settings.group.materials");

    private final SettingCategory category;
    private final String labelKey;

    SettingGroup(SettingCategory category, String labelKey) {
        this.category = category;
        this.labelKey = labelKey;
    }

    SettingCategory category() {
        return category;
    }

    String labelKey() {
        return labelKey;
    }
}
