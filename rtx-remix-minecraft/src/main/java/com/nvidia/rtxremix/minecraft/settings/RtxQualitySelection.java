/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import com.nvidia.rtxremix.minecraft.QualityPreset;
import java.util.Objects;

/** The preset a visual configuration started from, and whether it was subsequently edited. */
public record RtxQualitySelection(QualityPreset base, boolean customized) {
    public RtxQualitySelection {
        Objects.requireNonNull(base);
    }
}
