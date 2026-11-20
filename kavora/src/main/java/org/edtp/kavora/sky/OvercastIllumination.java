/* SPDX-License-Identifier: Apache-2.0 */
package org.edtp.kavora.sky;

/** Incident illumination outside the resolved cloud volumes. Beam fractions include
 * clear sky. Diffuse coefficients describe the covered part, before dome alpha blending. */
public record OvercastIllumination(float opacity, float sunBeam, float moonBeam,
                                   float diffuseSky, float diffuseSun, float diffuseMoon) {
    public static final OvercastIllumination CLEAR = new OvercastIllumination(0, 1, 1, 0, 0, 0);
    // Vertical optical depths from the authored overhead beam transmission.
    private static final double RAIN_DEPTH = -Math.log(.0025);
    private static final double STORM_DEPTH = -Math.log(.001);
    private static final double STREAM_COSINE = 1 / Math.sqrt(3);

    public static OvercastIllumination evaluate(float rain, float thunder, float sunUp, float moonUp) {
        float storm = Math.clamp(thunder, 0f, 1f);
        float rainOnly = Math.clamp(rain, 0f, 1f) * (1 - storm);
        float covered = rainOnly + storm;
        if (covered == 0) return CLEAR;
        double rainSun = beam(RAIN_DEPTH, sunUp), stormSun = beam(STORM_DEPTH, sunUp);
        double rainMoon = beam(RAIN_DEPTH, moonUp), stormMoon = beam(STORM_DEPTH, moonUp);
        return new OvercastIllumination(covered,
                (float) (1 - covered + rainOnly * rainSun + storm * stormSun),
                (float) (1 - covered + rainOnly * rainMoon + storm * stormMoon),
                (float) ((rainOnly * skyTransmission(RAIN_DEPTH) + storm * skyTransmission(STORM_DEPTH)) / covered),
                (float) ((rainOnly * scatteredBeam(RAIN_DEPTH, sunUp, rainSun)
                        + storm * scatteredBeam(STORM_DEPTH, sunUp, stormSun)) / covered),
                (float) ((rainOnly * scatteredBeam(RAIN_DEPTH, moonUp, rainMoon)
                        + storm * scatteredBeam(STORM_DEPTH, moonUp, stormMoon)) / covered));
    }

    private static double beam(double depth, float cosine) {
        return cosine > 0 ? Math.exp(-depth / cosine) : 0;
    }

    private static double skyTransmission(double depth) {
        return 2 * STREAM_COSINE / (2 * STREAM_COSINE + depth);
    }

    // Conservative two-stream slab. A first collision at optical depth z has
    // downward escape probability (z + mu_d) / (depth + 2*mu_d). Integrate that
    // probability against the attenuated incident beam and project onto the slab.
    private static double scatteredBeam(double depth, float cosine, double transmission) {
        double incidentCosine = Math.max(0f, cosine);
        return incidentCosine * ((STREAM_COSINE + incidentCosine) * (1 - transmission) - depth * transmission)
                / (2 * STREAM_COSINE + depth);
    }
}
