/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.logic;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocalMistPolicyTest {
    @Test
    void dryBiomesAndClearMiddayDoNotCondense() {
        for (long day = -2; day <= 4; day++) {
            assertEquals(0, LocalMistPolicy.strength(day * 24000 + 1000, 0, 1));
            assertEquals(0, LocalMistPolicy.strength(day * 24000 + 6000, 1, 0));
            assertEquals(0, LocalMistPolicy.strength(day * 24000 + 18000, 1, 0));
        }
    }

    @Test
    void wetWeatherAddsMistAndEpisodesVaryByDay() {
        assertTrue(LocalMistPolicy.strength(6000, 1, 1) > 0.5);
        float low = 1, high = 0;
        for (long day = 0; day < 12; day++) {
            float value = LocalMistPolicy.strength(day * 24000 + 500, 1, 0);
            low = Math.min(low, value);
            high = Math.max(high, value);
        }
        assertTrue(low < 0.01 && high > 0.8, "Mist should be episodic rather than permanent");
    }

    @Test
    void sunriseIsContinuousAndHumidityScalesDensity() {
        for (long day = 0; day < 12; day++) {
            assertEquals(LocalMistPolicy.strength(day * 24000 + 23999, 1, 0),
                    LocalMistPolicy.strength((day + 1) * 24000, 1, 0), 0.0001);
        }
        assertEquals(LocalMistPolicy.strength(24500, 1, 0) * 0.5f,
                LocalMistPolicy.strength(24500, 0.5f, 0));
    }
}
