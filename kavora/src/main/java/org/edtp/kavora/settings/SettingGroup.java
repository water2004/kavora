/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.settings;

import org.edtp.kavora.logic.AtmosphereDimension;

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
    NRC(RADIANCE_CACHE, "kavora.settings.group.nrc"),
    SHADOWS(SettingCategory.RAY_TRACING, "kavora.settings.group.shadows"),
    REFLECTIONS(SettingCategory.RAY_TRACING, "kavora.settings.group.reflections"),
    MATERIALS(SettingCategory.MATERIALS, "kavora.settings.group.materials"),
    PARALLAX(MATERIALS, "kavora.settings.group.parallax"),
    WATER(SettingCategory.MATERIALS, "kavora.settings.group.water"),
    SUBSURFACE(SettingCategory.MATERIALS, "kavora.settings.group.subsurface"),
    SIMULATED_PARTICLES(SettingCategory.PARTICLES, "kavora.settings.group.simulatedParticles"),
    SKY(SettingCategory.SKY, "kavora.settings.group.sky"),
    NIGHT_SKY(SettingCategory.SKY, "kavora.settings.group.nightSky"),
    END_SKY(SettingCategory.SKY, "kavora.settings.group.endSky"),
    CLOUDS(SettingCategory.WEATHER, "kavora.settings.group.clouds"),
    CLOUD_QUALITY(CLOUDS, "kavora.settings.group.cloudQuality"),
    FOG(SettingCategory.WEATHER, "kavora.settings.group.fog"),
    FOG_OVERWORLD(FOG, "kavora.settings.group.overworld"),
    GROUND_MIST(FOG_OVERWORLD, "kavora.settings.group.groundMist"),
    FOG_NETHER(FOG, "kavora.settings.group.nether"),
    FOG_END(FOG, "kavora.settings.group.end"),
    FOG_QUALITY(FOG, "kavora.settings.group.fogQuality"),
    FOG_NOISE(FOG, "kavora.settings.group.fogNoise"),
    LAVA_OVERWORLD(SettingCategory.COLOR, "kavora.settings.group.overworld"),
    LAVA_NETHER(SettingCategory.COLOR, "kavora.settings.group.nether"),
    LAVA_END(SettingCategory.COLOR, "kavora.settings.group.end"),
    TONEMAPPING(SettingCategory.IMAGE, "kavora.settings.group.tonemapping"),
    BLOOM(SettingCategory.IMAGE, "kavora.settings.group.bloom"),
    COLOR_GRADING(SettingCategory.IMAGE, "kavora.settings.group.colorGrading"),
    LENS(SettingCategory.IMAGE, "kavora.settings.group.lens"),
    MOTION_BLUR(LENS, "kavora.settings.group.motionBlur"),
    INVERSION(COLOR_GRADING, "kavora.settings.group.inversion"),
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

    static SettingGroup fog(AtmosphereDimension dimension) {
        return switch (dimension) {
            case OVERWORLD -> FOG_OVERWORLD;
            case NETHER -> FOG_NETHER;
            case END -> FOG_END;
        };
    }

    static SettingGroup lava(AtmosphereDimension dimension) {
        return switch (dimension) {
            case OVERWORLD -> LAVA_OVERWORLD;
            case NETHER -> LAVA_NETHER;
            case END -> LAVA_END;
        };
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
