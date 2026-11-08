/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.settings;

import org.edtp.kavora.light.BlockLightIntensityControls;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockLightSliderScaleTest {

    private static final double EPSILON = 1.0e-9;

    @Test
    void mappingProvidesOffSentinelAndExponentialEndpoints() {
        assertEquals(0.0, BlockLightSliderScale.multiplierFromPosition(0.0));
        assertEquals(
                0.0,
                BlockLightSliderScale.multiplierFromPosition(
                        BlockLightSliderScale.ZERO_SENTINEL_END / 2.0));
        assertEquals(
                BlockLightSliderScale.MIN_NONZERO_MULTIPLIER,
                BlockLightSliderScale.multiplierFromPosition(
                        BlockLightSliderScale.ZERO_SENTINEL_END),
                EPSILON);
        assertEquals(
                BlockLightIntensityControls.MAX_MULTIPLIER,
                BlockLightSliderScale.multiplierFromPosition(1.0),
                EPSILON);
    }

    @Test
    void mappingRoundTripsDefaultAndRepresentativeMultipliers() {
        for (double multiplier : new double[]{0.0, 0.02, 0.1, 1.0, 5.0, 25.0, 50.0}) {
            double position = BlockLightSliderScale.positionFromMultiplier(multiplier);
            assertEquals(
                    multiplier,
                    BlockLightSliderScale.multiplierFromPosition(position),
                    EPSILON,
                    "multiplier=" + multiplier);
        }
    }

    @Test
    void nonzeroRangeIsStrictlyIncreasing() {
        double previous = 0.0;
        for (int step = 4; step <= 100; step++) {
            double current = BlockLightSliderScale.multiplierFromPosition(step / 100.0);
            assertTrue(current > previous, "step=" + step);
            previous = current;
        }
    }

}
