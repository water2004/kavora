/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.settings;

import org.edtp.kavora.light.BlockLightIntensityControls;

/** Exponential light multiplier range with an explicit Off interval. */
final class BlockLightSliderScale {
    static final double MIN_NONZERO_MULTIPLIER = 0.02;
    static final double ZERO_SENTINEL_END = 0.04;

    private BlockLightSliderScale() {}

    static double multiplierFromPosition(double position) {
        if (position < ZERO_SENTINEL_END) {
            return 0.0;
        }
        double progress = (position - ZERO_SENTINEL_END) / (1.0 - ZERO_SENTINEL_END);
        double ratio =
                BlockLightIntensityControls.MAX_MULTIPLIER / MIN_NONZERO_MULTIPLIER;
        return MIN_NONZERO_MULTIPLIER * Math.pow(ratio, progress);
    }

    static double positionFromMultiplier(double multiplier) {
        if (multiplier <= 0.0) {
            return 0.0;
        }
        double clamped = Math.max(
                MIN_NONZERO_MULTIPLIER,
                Math.min(BlockLightIntensityControls.MAX_MULTIPLIER, multiplier));
        double ratio =
                BlockLightIntensityControls.MAX_MULTIPLIER / MIN_NONZERO_MULTIPLIER;
        double progress = Math.log(clamped / MIN_NONZERO_MULTIPLIER) / Math.log(ratio);
        return ZERO_SENTINEL_END + progress * (1.0 - ZERO_SENTINEL_END);
    }
}
