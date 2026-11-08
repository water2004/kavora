/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.cloud;

import java.util.function.BiConsumer;

/** Supplies world metadata to NoSave runtime options; artist controls remain in the settings registry. */
final class VolumetricCloudState {
    private final BiConsumer<String, String> writer;
    private boolean enabled;
    private float time = Float.NaN;
    private float height = Float.NaN;
    private float weather = Float.NaN;
    private float thunder = Float.NaN;

    VolumetricCloudState(BiConsumer<String, String> writer) {
        this.writer = writer;
    }

    void update(double worldTimeSeconds, float baseHeight, float rain, float storm) {
        float worldTime = (float) worldTimeSeconds;
        if (Float.compare(time, worldTime) != 0) {
            writer.accept("rtx.clouds.worldTimeSeconds", Float.toString(worldTime));
            time = worldTime;
        }
        if (Float.compare(height, baseHeight) != 0) {
            writer.accept("rtx.clouds.worldBaseHeightMeters", Float.toString(baseHeight));
            height = baseHeight;
        }
        if (Float.compare(weather, rain) != 0) {
            writer.accept("rtx.clouds.weather", Float.toString(rain));
            weather = rain;
        }
        if (Float.compare(thunder, storm) != 0) {
            writer.accept("rtx.clouds.thunder", Float.toString(storm));
            thunder = storm;
        }
        if (!enabled) {
            writer.accept("rtx.clouds.sceneEnabled", "true");
            enabled = true;
        }
    }

    void disable() {
        if (enabled) {
            writer.accept("rtx.clouds.sceneEnabled", "false");
            enabled = false;
        }
    }
}
