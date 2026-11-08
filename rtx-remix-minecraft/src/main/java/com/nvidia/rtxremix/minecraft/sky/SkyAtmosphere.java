/* SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

/** Air density and celestial transport. Sea level is a density reference, not an opaque surface. */
public final class SkyAtmosphere {
    // Lengths are kilometres and extinction is inverse kilometres.
    private static final double SEA_LEVEL_RADIUS = 6371.0;
    // The lower integration boundary lies beyond 500 vertical optical depths of aerosol.
    private static final double LOWER_DEPTH = 12.0;
    private static final double INNER_RADIUS = SEA_LEVEL_RADIUS - LOWER_DEPTH;
    private static final double ATMOSPHERE_HEIGHT = 100.0 + LOWER_DEPTH;
    private static final double RAYLEIGH_HEIGHT = 8.0;
    private static final double MIE_HEIGHT = 1.2;
    private static final double RAYLEIGH_R = 0.0055;
    private static final double RAYLEIGH_G = 0.0130;
    private static final double RAYLEIGH_B = 0.0224;
    private static final double MIE_EXTINCTION = 0.021;
    private static final double MIE_ALBEDO = 0.9;
    private static final int LIGHT_STEPS = 64;
    private static final double[] DISK_NODES = {-0.861136312, -0.339981044, 0.339981044, 0.861136312};
    private static final double[] DISK_WEIGHTS = {0.347854845, 0.652145155, 0.652145155, 0.347854845};

    private SkyAtmosphere() {}

    public static float observerHeight(double heightAboveSeaLevel) {
        return (float) Math.clamp(heightAboveSeaLevel, 1.0 - LOWER_DEPTH * 1000, 99000.0);
    }

    /** Full-disk mean RGB air transmission; input height above sea level is in metres. */
    public static float[] transmittanceToLight(float observerHeight, float lightCosine, float angularRadius) {
        double height = observerHeight(observerHeight) * .001 + LOWER_DEPTH;
        double cosine = Math.clamp(lightCosine, -1.0, 1.0);
        double horizon = -Math.sqrt(height * (2.0 * INNER_RADIUS + height)) / (INNER_RADIUS + height);
        double extent = Math.sin(angularRadius) * Math.sqrt(Math.max(0.0, 1.0 - cosine * cosine));
        double first = Math.clamp((horizon - cosine) / Math.max(extent, 1e-6), -1.0, 1.0);
        double visible = (Math.acos(first) - first * Math.sqrt(Math.max(0.0, 1.0 - first * first))) / Math.PI;
        if (visible == 0.0) return new float[3];
        double red = 0.0, green = 0.0, blue = 0.0, normalization = 0.0;
        for (int index = 0; index < DISK_NODES.length; index++) {
            double chord = .5 * (1.0 + first) + .5 * (1.0 - first) * DISK_NODES[index];
            double weight = DISK_WEIGHTS[index] * Math.sqrt(Math.max(0.0, 1.0 - chord * chord));
            double mu = Math.max(horizon, cosine + extent * chord);
            double b = (INNER_RADIUS + height) * mu;
            double c = height * (2.0 * INNER_RADIUS + height)
                    - ATMOSPHERE_HEIGHT * (2.0 * INNER_RADIUS + ATMOSPHERE_HEIGHT);
            double distance = -b + Math.sqrt(b * b - c);
            double rayleighDepth = 0.0, mieDepth = 0.0, previous = 0.0;
            double previousRayleigh = Math.exp(-(height - LOWER_DEPTH) / RAYLEIGH_HEIGHT);
            double previousMie = Math.exp(-(height - LOWER_DEPTH) / MIE_HEIGHT);
            for (int step = 1; step <= LIGHT_STEPS; step++) {
                double fraction = (double) step / LIGHT_STEPS;
                double next = distance * fraction * fraction;
                double middleHeight = altitude(height, b, .5 * (previous + next));
                double nextHeight = altitude(height, b, next);
                double nextRayleigh = Math.exp(-nextHeight / RAYLEIGH_HEIGHT);
                double nextMie = Math.exp(-nextHeight / MIE_HEIGHT);
                double segmentWeight = (next - previous) / 6.0;
                rayleighDepth += (previousRayleigh + 4.0 * Math.exp(-middleHeight / RAYLEIGH_HEIGHT)
                        + nextRayleigh) * segmentWeight;
                mieDepth += (previousMie + 4.0 * Math.exp(-middleHeight / MIE_HEIGHT) + nextMie) * segmentWeight;
                previousRayleigh = nextRayleigh;
                previousMie = nextMie;
                previous = next;
            }
            red += weight * Math.exp(-RAYLEIGH_R * rayleighDepth - MIE_EXTINCTION * mieDepth);
            green += weight * Math.exp(-RAYLEIGH_G * rayleighDepth - MIE_EXTINCTION * mieDepth);
            blue += weight * Math.exp(-RAYLEIGH_B * rayleighDepth - MIE_EXTINCTION * mieDepth);
            normalization += weight;
        }
        double scale = visible / Math.max(normalization, 1e-8);
        return new float[]{(float) (red * scale), (float) (green * scale), (float) (blue * scale)};
    }

    private static double altitude(double originHeight, double projection, double distance) {
        double difference = originHeight * (2.0 * INNER_RADIUS + originHeight)
                + distance * (distance + 2.0 * projection);
        return difference / (Math.sqrt(INNER_RADIUS * INNER_RADIUS + difference) + INNER_RADIUS) - LOWER_DEPTH;
    }

    /** Injected after #version, keeping the CPU and GPU optical coefficients identical. */
    static String shaderDefinitions() {
        return "#define INNER_RADIUS " + INNER_RADIUS + "\n"
                + "#define LOWER_DEPTH " + LOWER_DEPTH + "\n"
                + "#define ATMOSPHERE_HEIGHT " + ATMOSPHERE_HEIGHT + "\n"
                + "#define RAYLEIGH_HEIGHT " + RAYLEIGH_HEIGHT + "\n"
                + "#define MIE_HEIGHT " + MIE_HEIGHT + "\n"
                + "#define BETA_R vec3(" + RAYLEIGH_R + ", " + RAYLEIGH_G + ", " + RAYLEIGH_B + ")\n"
                + "#define BETA_M " + MIE_EXTINCTION + "\n"
                + "#define MIE_ALBEDO " + MIE_ALBEDO + "\n"
                + "#define LIGHT_STEPS " + LIGHT_STEPS + "\n"
                + "#define DISK_NODES " + vector(DISK_NODES) + "\n"
                + "#define DISK_WEIGHTS " + vector(DISK_WEIGHTS) + "\n";
    }

    private static String vector(double[] values) {
        return "vec4(" + values[0] + ", " + values[1] + ", " + values[2] + ", " + values[3] + ")";
    }
}
