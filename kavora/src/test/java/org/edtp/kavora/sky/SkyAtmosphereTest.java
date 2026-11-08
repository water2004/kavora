/* SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.sky;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SkyAtmosphereTest {
    private static final float SUN_RADIUS = (float) Math.toRadians(.265);

    @Test void diskTransmissionMatchesIndependentAdaptiveQuadrature() {
        // Adaptive path quadrature and 128-point disk integration, without a lookup table.
        assertTransmission(2, 60, .9232817435, .8615203843, .7899133811);
        assertTransmission(2, .0283, .02382084428, .003037627883, .0002331019023);
        assertTransmission(2, -.2, .01158950262, .001235271841, .00007591475354);
        assertTransmission(10000, -2, .2876203310, .05576554142, .007317668287);
    }

    @Test void overheadTransmissionMatchesAnalyticExponentialDensity() {
        float[] transmission = SkyAtmosphere.transmittanceToLight(2, 1, SUN_RADIUS);
        double rayleighColumn = 8 * (Math.exp(-.002 / 8) - Math.exp(-100.0 / 8));
        double mieColumn = 1.2 * (Math.exp(-.002 / 1.2) - Math.exp(-100.0 / 1.2));
        double[] coefficients = {.0055, .0130, .0224};
        for (int channel = 0; channel < 3; channel++) {
            assertEquals(Math.exp(-coefficients[channel] * rayleighColumn - .021 * mieColumn),
                    transmission[channel], .0001);
        }
    }

    @Test void airAttenuationVariesContinuouslyAcrossSeaLevelTangent() {
        float below = (float) Math.sin(Math.toRadians(-1));
        float[] fromSeaLevel = SkyAtmosphere.transmittanceToLight(2, below, SUN_RADIUS);
        float[] fromMountain = SkyAtmosphere.transmittanceToLight(2000, below, SUN_RADIUS);
        assertTrue(fromMountain[0] > fromSeaLevel[0] && fromSeaLevel[0] > 0);
        float tangent = (float) -Math.sqrt(.002 * (2 * 6371 + .002)) / 6371.002f;
        float[] above = SkyAtmosphere.transmittanceToLight(2, tangent + 1e-7f, 0);
        float[] under = SkyAtmosphere.transmittanceToLight(2, tangent - 1e-7f, 0);
        for (int channel = 0; channel < 3; channel++) {
            assertTrue(under[channel] > 0);
            assertEquals(above[channel], under[channel], above[channel] * .0001);
        }
        float[] centred = SkyAtmosphere.transmittanceToLight(0, 0, SUN_RADIUS);
        assertTrue(centred[0] > centred[1] && centred[1] > centred[2] && centred[2] > 0);
    }

    private static void assertTransmission(float height, double elevation, double... expected) {
        float[] actual = SkyAtmosphere.transmittanceToLight(height,
                (float) Math.sin(Math.toRadians(elevation)), SUN_RADIUS);
        for (int channel = 0; channel < 3; channel++) {
            assertEquals(expected[channel], actual[channel], expected[channel] * .005,
                    "height=" + height + ", elevation=" + elevation + ", channel=" + channel);
        }
    }
}
