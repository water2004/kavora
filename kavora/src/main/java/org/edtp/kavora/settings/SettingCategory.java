/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.settings;

/** Top-level pages in the RTX settings menu. */
enum SettingCategory {
    PERFORMANCE("kavora.settings.category.performance"),
    LIGHTING("kavora.settings.category.lighting"),
    RAY_TRACING("kavora.settings.category.rayTracing"),
    MATERIALS("kavora.settings.category.materials"),
    SKY("kavora.settings.category.sky"),
    WEATHER("kavora.settings.category.weather"),
    IMAGE("kavora.settings.category.image");

    private final String labelKey;

    SettingCategory(String labelKey) {
        this.labelKey = labelKey;
    }

    String labelKey() {
        return labelKey;
    }
}
