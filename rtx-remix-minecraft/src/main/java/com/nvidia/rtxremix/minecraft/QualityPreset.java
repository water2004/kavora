/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Visual profiles shared by the shader menu, command and developer launcher. */
public enum QualityPreset {
    LOW, MEDIUM, HIGH, ULTRA;

    /** The name the launchers, the benchmark harness and {@code /rtx preset} use. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<QualityPreset> byId(String id) {
        return Arrays.stream(values()).filter(preset -> preset.id().equals(id)).findFirst();
    }

    /** Every id, cheapest tier first. */
    public static List<String> ids() {
        return Arrays.stream(values()).map(QualityPreset::id).toList();
    }
}
