/* SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0 */
package org.edtp.kavora.sky;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OvercastIlluminationTest {
    @Test
    void clearRainAndThunderUseTheAuthoredZenithTransmission() {
        assertEquals(OvercastIllumination.CLEAR, OvercastIllumination.evaluate(0, 0, 1, 0));
        assertEquals(.0025, OvercastIllumination.evaluate(1, 0, 1, 1).sunBeam(), 1e-7);
        assertEquals(.001, OvercastIllumination.evaluate(1, 1, 1, 1).moonBeam(), 1e-7);
    }

    @Test
    void areaWeightedWeatherMixesFluxContinuously() {
        var rain = OvercastIllumination.evaluate(1, 0, .7f, .3f);
        var storm = OvercastIllumination.evaluate(1, 1, .7f, .3f);
        for (float r : new float[]{0, .4f, 1}) {
            for (float s : new float[]{0, .25f, 1}) {
                var mixed = OvercastIllumination.evaluate(r, s, .7f, .3f);
                float rainArea = r * (1 - s);
                assertEquals(rainArea + s, mixed.opacity(), 1e-6);
                assertEquals(1 - mixed.opacity() + rainArea * rain.sunBeam() + s * storm.sunBeam(), mixed.sunBeam(), 1e-6);
                assertEquals(rainArea * rain.diffuseSun() + s * storm.diffuseSun(), mixed.opacity() * mixed.diffuseSun(), 1e-6);
                assertEquals(rainArea * rain.diffuseSky() + s * storm.diffuseSky(), mixed.opacity() * mixed.diffuseSky(), 1e-6);
            }
        }
    }

    @Test
    void transmittedBeamAndDiffuseFluxRespectTheIncidentEnergyAtEveryElevation() {
        for (int i = 0; i <= 100; i++) {
            float mu = i / 100f;
            var sky = OvercastIllumination.evaluate(1, .5f, mu, mu);
            assertEquals(sky.sunBeam(), sky.moonBeam());
            assertEquals(sky.diffuseSun(), sky.diffuseMoon());
            assertTrue(sky.diffuseSun() >= 0 && mu * sky.sunBeam() + sky.diffuseSun() <= mu + 1e-6);
        }
        var sunset = OvercastIllumination.evaluate(1, 0, -0.01f, .01f);
        assertEquals(0f, sunset.diffuseSun());
        assertTrue(sunset.diffuseSky() > 0);
    }

    @Test
    void cloudDeckScattersAnOverheadBeamEvenUnderFullCloudCover() {
        // Independent quadrature of first-collision depth with two-stream escape.
        for (float thunder : new float[]{0, 1}) {
            double mu = .8, depth = -Math.log(thunder == 0 ? .0025 : .001);
            double delta = depth / 2048, diffuse = 0;
            for (int i = 0; i < 2048; i++) {
                double z = (i + .5) * delta;
                diffuse += delta * Math.exp(-z / mu) * (z + 1 / Math.sqrt(3)) / (depth + 2 / Math.sqrt(3));
            }
            assertEquals(diffuse, OvercastIllumination.evaluate(1, thunder, (float) mu, 0).diffuseSun(), 1e-6);
        }
    }
}
