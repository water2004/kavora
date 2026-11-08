/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.gametest;

import com.google.gson.Gson;
import org.edtp.kavora.KavoraMod;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Pixel readback covers shared grading and the dimension profile's lifetime. */
final class KavoraColourChecks {
    private static final String MODE = "rtx.tonemappingMode";
    private static final String GRADING = "rtx.tonemap.colorGradingEnabled";
    private static final String SATURATION = "rtx.tonemap.saturation";
    private static final List<String> KEYS = List.of(MODE, GRADING, SATURATION,
            "rtx.tonemap.contrast", "rtx.tonemap.colorBalance", "rtx.useLegacyACES",
            "rtx.localtonemap.finalizeWithACES", "rtx.volumetrics.transmittanceColor",
            "rtx.volumetrics.singleScatteringAlbedo", "rtx.upscalerType", "rtx.qualityDLSS",
            "rtx.enableRayReconstruction", "rtx.dlfg.enable");
    private static final List<Object> measurements = new ArrayList<>();

    private record Metrics(int width, int height, long visiblePixels, double grayscaleFraction,
                           double meanRed, double meanGreen, double meanBlue, double linearChroma) {}
    private record Capture(String name, Map<String, String> resolved, Metrics pixels) {}

    static void run(ClientGameTestContext context) {
        Map<String, String> original = resolved(context);
        try (Layer display = Layer.acquire(context, "display", """
                rtx.upscalerType = 1
                rtx.qualityDLSS = 5
                rtx.enableRayReconstruction = true
                rtx.dlfg.enable = false
                """)) {
            context.waitFor(client -> read("rtx.qualityDLSS").equals("5")
                    && read("rtx.upscalerType").equals("1")
                    && Boolean.parseBoolean(read("rtx.enableRayReconstruction"))
                    && !Boolean.parseBoolean(read("rtx.dlfg.enable")), 200);
            Path local = capture(context, "local-ungraded", 1, false, 1);
            Path local85 = capture(context, "local-saturation-085", 1, true, .85);
            Path local75 = capture(context, "local-saturation-075", 1, true, .75);
            measureChromaRatio(local, local85, .85);
            measureChromaRatio(local, local75, .75);
            requireGrayscale(capture(context, "local-grayscale", 1, true, 0));
            requireColour(capture(context, "local-restored", 1, true, 1));
            Path global = capture(context, "global-ungraded", 0, false, 1);
            requireGrayscale(capture(context, "global-grayscale", 0, true, 0));
            Path global75 = capture(context, "global-saturation-075", 0, true, .75);
            requireSingleGrade(global, global75, .75);
            requireColour(capture(context, "global-restored", 0, true, 1));
        } finally {
            waitFrames(context);
            Map<String, String> restored = resolved(context);
            if (!restored.equals(original)) {
                throw new AssertionError("Colour fixture leaked config opinions: before=" + original + ", after=" + restored);
            }
            measurements.add(Map.of("name", "nether-restored", "resolved", restored));
            writeMeasurements(context);
        }
    }

    static void recordDimension(ClientGameTestContext context, String name) {
        Map<String, String> values = resolved(context);
        measurements.add(Map.of("name", name, "resolved", values));
        writeMeasurements(context);
        boolean nether = name.equals("nether");
        double saturation = nether ? .85 : 1;
        if (Boolean.parseBoolean(values.get(GRADING)) != nether
                || Math.abs(Double.parseDouble(values.get(SATURATION)) - saturation) > .0001) {
            throw new AssertionError("Unexpected dimension colour profile in " + name + ": " + values);
        }
    }

    private static Path capture(ClientGameTestContext context, String name, int mode, boolean grading, double saturation) {
        String options = MODE + " = " + mode + "\n" + GRADING + " = " + grading + "\n"
                + SATURATION + " = " + saturation + "\n" + """
                rtx.tonemap.contrast = 1
                rtx.tonemap.colorBalance = 1, 1, 1
                rtx.useLegacyACES = false
                rtx.localtonemap.finalizeWithACES = true
                """;
        try (Layer layer = Layer.acquire(context, name, options)) {
            context.waitFor(client -> Integer.parseInt(read(MODE)) == mode
                    && Boolean.parseBoolean(read(GRADING)) == grading
                    && Math.abs(Double.parseDouble(read(SATURATION)) - saturation) < .0001, 200);
            waitFrames(context);
            Path image = KavoraWorldGameTest.captureCompletedFrame(context, "colour-nether-" + name);
            KavoraWorldGameTest.requireVisibleWorld(image);
            measurements.add(new Capture(name, resolved(context), metrics(image)));
            writeMeasurements(context);
            return image;
        }
    }

    private static void requireGrayscale(Path image) {
        Metrics pixels = metrics(image);
        if (pixels.grayscaleFraction < .98) {
            throw new AssertionError("Grading did not make the visible scene grayscale: " + image + " " + pixels);
        }
    }

    private static void requireColour(Path image) {
        Metrics pixels = metrics(image);
        if (pixels.grayscaleFraction > .8 || pixels.linearChroma < .01) {
            throw new AssertionError("Saturation 1 did not restore scene colour: " + image + " " + pixels);
        }
    }

    private static void requireSingleGrade(Path baseline, Path candidate, double saturation) {
        double ratio = measureChromaRatio(baseline, candidate, saturation);
        if (Math.abs(ratio - saturation) >= Math.abs(ratio - saturation * saturation)
                || Math.abs(ratio - saturation) > .06) {
            throw new AssertionError("Expected saturation applied once: " + candidate + " ratio=" + ratio
                    + ", once=" + saturation + ", twice=" + saturation * saturation);
        }
    }

    private static double measureChromaRatio(Path baseline, Path candidate, double saturation) {
        BufferedImage before = load(baseline);
        BufferedImage after = load(candidate);
        double baseChroma = 0;
        double gradedChroma = 0;
        long pixels = 0;
        for (int y = before.getHeight() / 10; y < before.getHeight() * 9 / 10; y++) {
            for (int x = before.getWidth() / 10; x < before.getWidth() * 9 / 10; x++) {
                int a = before.getRGB(x, y);
                int b = after.getRGB(x, y);
                int red = (a >>> 16) & 255, green = (a >>> 8) & 255, blue = a & 255;
                int max = Math.max(red, Math.max(green, blue));
                int min = Math.min(red, Math.min(green, blue));
                // Avoid output clipping, where the ungraded image has lost linear chroma.
                if (min < 12 || max > 240 || max - min < 20) continue;
                baseChroma += chroma(a);
                gradedChroma += chroma(b);
                pixels++;
            }
        }
        if (pixels < 1000) throw new AssertionError("Insufficient unclipped colour pixels: " + baseline);
        double ratio = gradedChroma / baseChroma;
        measurements.add(Map.of("name", candidate.getFileName().toString(), "chromaRatio", ratio,
                "expectedOnce", saturation, "expectedTwice", saturation * saturation, "comparisonPixels", pixels));
        return ratio;
    }

    private static Metrics metrics(Path path) {
        BufferedImage image = load(path);
        long visible = 0, gray = 0, red = 0, green = 0, blue = 0;
        double chroma = 0;
        for (int y = image.getHeight() / 10; y < image.getHeight() * 9 / 10; y++) {
            for (int x = image.getWidth() / 10; x < image.getWidth() * 9 / 10; x++) {
                int color = image.getRGB(x, y);
                int r = (color >>> 16) & 255, g = (color >>> 8) & 255, b = color & 255;
                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));
                if (max < 12) continue;
                visible++;
                if (max - min <= 3) gray++;
                red += r;
                green += g;
                blue += b;
                chroma += chroma(color);
            }
        }
        if (visible < image.getWidth() * image.getHeight() / 10) {
            throw new AssertionError("Insufficient visible scene pixels: " + path);
        }
        return new Metrics(image.getWidth(), image.getHeight(), visible, (double) gray / visible,
                (double) red / visible, (double) green / visible, (double) blue / visible, chroma / visible);
    }

    private static double chroma(int color) {
        double r = linear((color >>> 16) & 255), g = linear((color >>> 8) & 255), b = linear(color & 255);
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
    }

    private static double linear(int channel) {
        // Remix's shared output uses a 2.2 power curve for linearToGamma.
        return Math.pow(channel / 255.0, 2.2);
    }

    private static BufferedImage load(Path path) {
        try {
            BufferedImage image = ImageIO.read(path.toFile());
            if (image == null) throw new AssertionError("Cannot decode screenshot: " + path);
            return image;
        } catch (IOException e) {
            throw new AssertionError("Cannot read colour screenshot: " + path, e);
        }
    }

    private static Map<String, String> resolved(ClientGameTestContext context) {
        return context.computeOnClient(client -> {
            Map<String, String> values = new LinkedHashMap<>();
            for (String key : KEYS) values.put(key, read(key));
            return values;
        });
    }

    private static String read(String key) {
        return KavoraMod.renderer.getRemixApi().getConfigVariableResolved(key);
    }

    private static void waitFrames(ClientGameTestContext context) {
        long start = context.computeOnClient(client -> KavoraFrameProbe.presents);
        context.waitFor(client -> KavoraFrameProbe.presents >= start + 32, 400);
    }

    private static void writeMeasurements(ClientGameTestContext context) {
        Path destination = context.computeOnClient(client -> client.gameDirectory.toPath().resolve("dimension-colour.json"));
        try {
            Files.writeString(destination, new Gson().toJson(measurements));
        } catch (IOException e) {
            throw new AssertionError("Cannot save colour measurements", e);
        }
    }

    private record Layer(ClientGameTestContext context, Path path, long handle) implements AutoCloseable {
        static Layer acquire(ClientGameTestContext context, String name, String options) {
            Path directory = context.computeOnClient(client -> client.gameDirectory.toPath());
            Path path;
            try {
                path = Files.createTempFile(directory, "gametest-colour-" + name + "-", ".conf");
                Files.writeString(path, options);
            } catch (IOException e) {
                throw new AssertionError("Cannot write temporary colour layer", e);
            }
            long handle;
            try {
                handle = context.computeOnClient(client -> KavoraMod.renderer.getRemixApi()
                        .acquireConfigLayer(path.toAbsolutePath().toString(), 12000, .5f));
            } catch (RuntimeException | Error failure) {
                delete(path);
                throw failure;
            }
            Layer layer = new Layer(context, path, handle);
            try {
                context.runOnClient(client -> KavoraMod.renderer.getRemixApi().setConfigLayerBlending(handle, 1, .5f));
                return layer;
            } catch (RuntimeException | Error failure) {
                layer.close();
                throw failure;
            }
        }

        @Override
        public void close() {
            try {
                context.runOnClient(client -> KavoraMod.renderer.getRemixApi().releaseConfigLayer(handle));
            } finally {
                delete(path);
            }
        }

        private static void delete(Path path) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                throw new AssertionError("Cannot remove temporary colour layer: " + path, e);
            }
        }
    }
}
