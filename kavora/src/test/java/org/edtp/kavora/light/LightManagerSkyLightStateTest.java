/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.light;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

class LightManagerSkyLightStateTest {

    @Test
    void moonPhasesPreserveFullMoonAndExposeTheDarkMonthIndependently() {
        assertEquals(1f, LightManager.moonPhasePower(0, 0));
        assertEquals(1f, LightManager.moonPhasePower(0, 1));
        assertEquals(.3f, LightManager.moonPhasePower(4, .7f), 1e-6f);
        assertEquals(.475f, LightManager.moonPhasePower(3, .7f), 1e-6f);
        assertEquals(0f, LightManager.moonPhasePower(4, 1));
        for (int phase = 0; phase < 8; phase++) {
            assertEquals(1f, LightManager.moonPhasePower(phase, 0));
            assertEquals(LightManager.moonPhasePower(phase, .7f),
                    LightManager.moonPhasePower(-phase, .7f));
        }
    }


    @Test
    void cloudIncidenceUsesAirAtTheCloudLayerIndependentlyOfTheCamera() {
        var clear = LightManager.evaluateSkyLightState(0f, .5f, 0, 0f, 0f, 64f, 320f);
        var rainy = LightManager.evaluateSkyLightState(0f, .5f, 0, 1f, 0f, 64f, 320f);
        var higherCamera = LightManager.evaluateSkyLightState(0f, .5f, 0, 1f, 0f, 192f, 320f);
        assertTrue(clear.sunCloudRadiance()[0] > clear.sunDirectRadiance()[0]);
        assertTrue(clear.sunCloudRadiance()[0] > rainy.sunCloudRadiance()[0]);
        assertArrayEquals(rainy.sunCloudRadiance(), higherCamera.sunCloudRadiance());
    }

    @Test
    void theSurfaceAndEveryCloudAltitudeReceiveTheSameWeatherBeam() {
        float[] heights = {320f, 1820f, 6000f, 6900f};
        for (float time : new float[]{0f, .24f, .5f, .74f}) {
            var clear = LightManager.evaluateSkyLightState(time, time + .5f, 0, 0f, 0f, 64f, 320f);
            float[] clearSun = new float[16], clearMoon = new float[16];
            clear.writeCloudProfiles(heights, 0, clearSun, clearMoon);
            for (float thunder : new float[]{0f, .5f, 1f}) {
                var wet = LightManager.evaluateSkyLightState(time, time + .5f, 0, 1f, thunder, 64f, 320f);
                float sun = wet.weather().sunBeam();
                float moon = wet.weather().moonBeam();
                float[] wetSun = new float[16], wetMoon = new float[16];
                wet.writeCloudProfiles(heights, 0, wetSun, wetMoon);
                for (int channel = 0; channel < 3; channel++) {
                    assertEquals(clear.sunDirectRadiance()[channel] * sun, wet.sunDirectRadiance()[channel], 1e-6f);
                    assertEquals(clear.moonDirectRadiance()[channel] * moon, wet.moonDirectRadiance()[channel], 1e-6f);
                    assertEquals(wet.sunCloudRadiance()[channel], wetSun[channel], 1e-6f);
                    assertEquals(wet.moonCloudRadiance()[channel], wetMoon[channel], 1e-6f);
                    for (int i = channel; i < 16; i += 4) {
                        assertEquals(clearSun[i] * sun, wetSun[i], 1e-6f);
                        assertEquals(clearMoon[i] * moon, wetMoon[i], 1e-6f);
                    }
                }
            }
        }
    }

    @Test
    void classicAndDisabledCloudsRetainAuthoredWeatherAttenuation() {
        var clear = LightManager.evaluateSkyLightState(0f, .5f, 0, 0f, 0f, 64f, Float.NaN);
        var rainy = LightManager.evaluateSkyLightState(0f, .5f, 0, 1f, 0f, 64f, Float.NaN);
        var storm = LightManager.evaluateSkyLightState(0f, .5f, 0, 1f, 1f, 64f, Float.NaN);
        assertEquals(clear.sunDirectRadiance()[0] * rainy.weather().sunBeam(), rainy.sunDirectRadiance()[0], 1e-5f);
        assertEquals(clear.sunDirectRadiance()[0] * storm.weather().sunBeam(), storm.sunDirectRadiance()[0], 1e-5f);
        assertNull(storm.sunCloudRadiance());
        assertNull(storm.moonCloudRadiance());
    }
}
