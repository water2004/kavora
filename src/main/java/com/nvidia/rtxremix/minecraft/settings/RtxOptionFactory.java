/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import com.nvidia.rtxremix.minecraft.UnfocusedFramerateLimit;
import java.util.Locale;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Value labels and slider mappings shared by Sodium and the shader menu. */
final class RtxOptionFactory {

    static final String ENTITY_DISTANCE_ID = "dist.entity";
    static final String LIGHT_DISTANCE_ID = "dist.light";
    static final String SUN_INTENSITY_ID = "sky.sunIntensity";
    static final String PROCEDURAL_CLOUDS_ID = "clouds.procedural";
    static final String TERRAIN_EMISSION_MULTIPLIER_ID =
            "materials.terrainEmissionMultiplier";
    static final double SUN_MIN_NONZERO_INTENSITY = 0.01;
    static final double SUN_ZERO_SENTINEL_END = 0.02;
    static final double MULTIPLIER_MIN_NONZERO_VALUE = 0.02;
    static final double MULTIPLIER_ZERO_SENTINEL_END = 0.04;
    /**
     * Slider position above the shared 0..32 range that encodes the runtime
     * value 0.
     *
     * <p>Zero means Auto to these setters, which reads backwards
     * at the left edge of a distance slider, so it is drawn at the right edge
     * instead. That needs a slider-space value of its own: persistence and
     * default comparisons stay in runtime-value space, and only the widget sees
     * this position. Another sentinel-backed slider must preserve the same
     * separation.
     */
    static final int SENTINEL_SLIDER_VALUE = 33;
    static final String UNFOCUSED_FRAMERATE_ID = "framerate.unfocused";

    private RtxOptionFactory() {}

    static int intValueFromSlider(RtxSetting<Integer> setting, int sliderValue) {
        int sentinelSliderValue = sentinelSliderValue(setting.id());
        if (sentinelSliderValue > 0 && sliderValue == sentinelSliderValue) {
            return 0;
        }
        return sliderValue * setting.step();
    }

    static int sliderValueFromInt(RtxSetting<Integer> setting, int value) {
        int sentinelSliderValue = sentinelSliderValue(setting.id());
        if (sentinelSliderValue < 0) {
            return value / setting.step();
        }
        if (value == 0) {
            return sentinelSliderValue;
        }
        return Math.max(1, Math.min(sentinelSliderValue - 1, value));
    }

    static double floatValueFromSlider(
            String settingId, double progress, double min, double max) {
        if (SUN_INTENSITY_ID.equals(settingId)) {
            return exponentialValueFromSlider(
                    progress, max, SUN_MIN_NONZERO_INTENSITY,
                    SUN_ZERO_SENTINEL_END);
        }
        if (TERRAIN_EMISSION_MULTIPLIER_ID.equals(settingId)) {
            return exponentialValueFromSlider(
                    progress, max, MULTIPLIER_MIN_NONZERO_VALUE,
                    MULTIPLIER_ZERO_SENTINEL_END);
        }
        return min + progress * (max - min);
    }

    static double sliderValueFromFloat(
            String settingId, double value, double min, double max) {
        if (SUN_INTENSITY_ID.equals(settingId)) {
            return sliderValueFromExponential(
                    value, max, SUN_MIN_NONZERO_INTENSITY,
                    SUN_ZERO_SENTINEL_END);
        }
        if (TERRAIN_EMISSION_MULTIPLIER_ID.equals(settingId)) {
            return sliderValueFromExponential(
                    value, max, MULTIPLIER_MIN_NONZERO_VALUE,
                    MULTIPLIER_ZERO_SENTINEL_END);
        }
        return (value - min) / (max - min);
    }

    private static double exponentialValueFromSlider(
            double progress, double max, double minNonzero, double zeroSentinelEnd) {
        if (progress < zeroSentinelEnd) {
            return 0.0;
        }
        double logarithmicProgress =
                (progress - zeroSentinelEnd) / (1.0 - zeroSentinelEnd);
        return minNonzero * Math.pow(max / minNonzero, logarithmicProgress);
    }

    private static double sliderValueFromExponential(
            double value, double max, double minNonzero, double zeroSentinelEnd) {
        if (value <= 0.0) {
            return 0.0;
        }
        double clamped = Math.max(minNonzero, Math.min(max, value));
        double logarithmicProgress = Math.log(clamped / minNonzero)
                / Math.log(max / minNonzero);
        return zeroSentinelEnd
                + logarithmicProgress * (1.0 - zeroSentinelEnd);
    }

    static Component floatValueText(String settingId, double value) {
        String format = RtxEffectOptions.contains(settingId)
                ? Math.abs(value) > 0 && Math.abs(value) < 0.01 ? "%.6f" : "%.3f"
                : "%.2f";
        String formatted = String.format(Locale.ROOT, format, value);
        if (!TERRAIN_EMISSION_MULTIPLIER_ID.equals(settingId)) {
            return Component.literal(formatted);
        }
        if (value <= 0.0) {
            return Component.translatable("rtxremix.settings.value.off");
        }
        return Component.translatable("rtxremix.settings.value.multiplier", formatted);
    }

    static Component booleanValueText(String settingId, boolean value) {
        if (PROCEDURAL_CLOUDS_ID.equals(settingId)) {
            return Component.translatable(value
                    ? "rtxremix.settings.value.procedural"
                    : "rtxremix.settings.value.classic");
        }
        return CommonComponents.optionStatus(value);
    }

    static Component intValueText(String settingId, int value) {
        if (settingId.equals("image.tonemapper")) {
            return Component.translatable("rtxremix.settings.value.tone." + (value == 0 ? "global" : "local"));
        }
        if (settingId.equals("exposure.average")) {
            return Component.translatable("rtxremix.settings.value.metering." + (value == 0 ? "mean" : "median"));
        }
        if (UNFOCUSED_FRAMERATE_ID.equals(settingId)) {
            // Vanilla's own Max Framerate wording, so the two read alike.
            return value == UnfocusedFramerateLimit.UNLIMITED
                    ? Component.translatable("options.framerateLimit.max")
                    : Component.translatable("options.framerate", value);
        }
        if (value == 0 && sentinelSliderValue(settingId) > 0) {
            return Component.translatable("rtxremix.settings.value.auto");
        }
        return Component.literal(Integer.toString(value));
    }

    static int sentinelSliderValue(String settingId) {
        boolean hasSentinel = ENTITY_DISTANCE_ID.equals(settingId)
                || LIGHT_DISTANCE_ID.equals(settingId);
        return hasSentinel ? SENTINEL_SLIDER_VALUE : -1;
    }
}
