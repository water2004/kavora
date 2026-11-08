/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.settings;

import org.edtp.kavora.QualityPreset;
import java.util.Objects;

/** The preset a visual configuration started from, and whether it was subsequently edited. */
public record KavoraQualitySelection(QualityPreset base, boolean customized) {
    public KavoraQualitySelection {
        Objects.requireNonNull(base);
    }
}
