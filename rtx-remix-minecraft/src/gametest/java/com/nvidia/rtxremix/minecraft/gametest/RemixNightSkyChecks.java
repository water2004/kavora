/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gametest;

import com.google.gson.Gson;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.logic.ConfLayerDriver;
import com.nvidia.rtxremix.minecraft.logic.LogicChannels;
import com.nvidia.rtxremix.minecraft.sky.SkyAtmosphere;
import com.nvidia.rtxremix.minecraft.settings.RtxSodiumConfig;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;

/** Separates celestial radiance from the static night environment in primary and reflected rays. */
final class RemixNightSkyChecks {
    private static final String NIGHT = "rtx.nightSky.";
    private static final String DISPLAY = """
            rtx.upscalerType = 1
            rtx.qualityDLSS = 5
            rtx.enableRayReconstruction = true
            rtx.dlfg.enable = false
            rtx.debugView.debugViewIdx = 0
            rtx.clouds.transportMode = 1
            rtx.clouds.skyResolution = 768
            rtx.clouds.windSpeed = 0
            """;
    private static final String MEASUREMENT = """
            rtx.rngSeedWithFrameIndex = false
            rtx.tonemappingMode = 0
            rtx.tonemap.tonemappingEnabled = false
            rtx.tonemap.exposureBias = 0
            rtx.tonemap.colorGradingEnabled = false
            rtx.autoExposure.enabled = false
            rtx.animation.timeOverrideMs = 1000
            rtx.volumetrics.maxAccumulationFrames = 1
            """;
    private static final List<String> KEYS = List.of(NIGHT + "enabled", NIGHT + "starIntensity",
            NIGHT + "milkyWayIntensity", NIGHT + "meteorRate", NIGHT + "meteorIntensity",
            "rtx.upscalerType", "rtx.qualityDLSS", "rtx.enableRayReconstruction", "rtx.dlfg.enable",
            "rtx.debugView.debugViewIdx", "rtx.clouds.transportMode", "rtx.clouds.skyResolution",
            "rtx.clouds.windSpeed", "rtx.clouds.coverage", "rtx.clouds.extinctionPerMeter",
            "rtx.autoExposure.autoExposureSpeed", "rtx.rngSeedWithFrameIndex", "rtx.tonemappingMode",
            "rtx.tonemap.tonemappingEnabled", "rtx.tonemap.exposureBias", "rtx.tonemap.colorGradingEnabled",
            "rtx.autoExposure.enabled", "rtx.animation.timeOverrideMs", "rtx.volumetrics.maxAccumulationFrames",
            "rtx.debugView.displayType", "rtx.debugView.pseudoColorMode", "rtx.debugView.enableGammaCorrection",
            "rtx.debugView.minValue", "rtx.debugView.maxValue", "rtx.debugView.replaceCompositeOutput",
            "rtx.debugView.accumulation.enable");
    private static final List<String> ENVIRONMENT_KEYS = List.of("rtx.volumetrics.enable",
            "rtx.volumetrics.transmittanceColor", "rtx.volumetrics.transmittanceMeasurementDistanceMeters",
            "rtx.volumetrics.singleScatteringAlbedo", "rtx.volumetrics.maxAccumulationFrames",
            "rtx.volumetrics.heightFogBaseHeightMeters", "rtx.volumetrics.heightFogFalloffMeters",
            "rtx.volumetrics.groundMistExtinctionPerMeter", "rtx.bloom.burnIntensity", "rtx.bloom.luminanceThreshold",
            "rtx.autoExposure.evMinValue", "rtx.autoExposure.evMaxValue", "rtx.localtonemap.exposure",
            "rtx.viewDistance.distanceFadeMin", "rtx.viewDistance.distanceFadeMax", "rtx.viewDistance.distanceMode");

    private record Delta(double mean, double changedFraction, long brightPoints) {}
    private record Blank(double median, double mad) {}
    private record StarMetrics(int independentPeaks, int repeatedPeaks, double peakCutoff,
                               double noiseMaximum, Blank baselineBlank, Blank starBlank) {}
    private record CloudProbe(int x, int y, String mask, double clearContrast, double cloudContrast,
                              int minimumTCode, int maximumTCode) {}
    private record CloudMetrics(int opaqueProbes, int suppressedOpaque, int transparentProbes, int retainedTransparent,
                                int partialProbes, int opaquePixels, int transparentPixels, double probeCutoff,
                                double cloudNoiseMaximum, List<CloudProbe> probes) {}
    private record StarPlane(int width, int height, int radius, double[] difference, double[] contrast, boolean[] peaks) {}
    private record FrameStamp(long nanoTime, long epochMillis, long submits, long presents) {}
    private record SceneInputs(String dimension, double x, double y, double z, float yaw, float pitch,
                               long gameTime, long clockTime, float sunAngle, float moonAngle, float starAngle,
                               float starBrightness, float rain, float thunder, float observerHeight,
                               List<ConfLayerDriver.LayerStatus> layers,
                               List<Float> skyLight, Map<String, String> resolved) {}

    private static final class Captures implements AutoCloseable {
        final List<Object> records = new ArrayList<>();
        private Layer active;
        private int priority = 13000;

        void add(Object record) { records.add(record); }

        void activate(ClientGameTestContext context, String name, String options) {
            Layer next = Layer.acquire(context, name, options, priority++);
            Layer previous = active;
            active = next;
            if (previous != null) previous.close();
        }

        @Override
        public void close() {
            if (active != null) {
                active.close();
                active = null;
            }
        }
    }

    private RemixNightSkyChecks() {}

    static void run(ClientGameTestContext context, TestSingleplayerContext world) {
        if (Boolean.getBoolean("rtxremix.gametest.nightSkyNormalDisplay")) {
            normalDisplay(context, world);
            return;
        }
        Map<String, String> original = resolved(context, KEYS);
        CloudStatus clouds = context.computeOnClient(client -> client.options.cloudStatus().get());
        int fov = context.computeOnClient(client -> client.options.fov().get());
        int renderDistance = context.computeOnClient(client -> client.options.renderDistance().get());
        boolean hidden = context.computeOnClient(client -> client.gui.hud.isHidden());
        boolean procedural = context.computeOnClient(client -> RtxRemixMod.renderer.getCloudRenderer().isProceduralClouds());
        Captures evidence = new Captures();
        try (RuntimeOptionScope transport = RuntimeOptionScope.acquire(context, "rtx.clouds.transportMode", "1");
             Layer display = Layer.acquire(context, "display", DISPLAY); evidence) {
            context.runOnClient(client -> {
                client.options.cloudStatus().set(CloudStatus.OFF);
                client.options.fov().set(90);
                client.options.renderDistance().set(Integer.getInteger("rtxremix.gametest.renderDistance", 16));
                client.options.broadcastOptions();
                if (!client.gui.hud.isHidden()) client.gui.hud.toggle();
                client.gui.toastManager().clear();
                RtxRemixMod.renderer.getCloudRenderer().setProceduralClouds(true);
            });
            prepare(context, world, true);
            settleClock(context, world, 6000);
            capture(context, evidence, "midday-default", true, 1, 1);
            settleClock(context, world, 18000);
            capture(context, evidence, "night-default", true, 1, 1);
            if (Boolean.getBoolean("rtxremix.gametest.nightSkyAppearanceOnly")) {
                settleClock(context, world, 12780);
                context.runOnClient(client -> {
                    float[] sun = RtxRemixMod.renderer.getLightManager().getSkyLightState().sunPosition();
                    client.player.setYRot((float) Math.toDegrees(Math.atan2(-sun[0], sun[2])));
                });
                capture(context, evidence, "sunset-default", true, 1, 1);
                if (Boolean.getBoolean("rtxremix.gametest.celestialAppearance")) {
                    celestialAppearance(context, world, evidence);
                }
                if (Boolean.getBoolean("rtxremix.gametest.horizonReferenceOcean")) {
                    referenceOcean(context, world, evidence);
                }
                if (Boolean.getBoolean("rtxremix.gametest.horizonFlight")) {
                    horizonFlight(context, world, evidence);
                }
                if (Boolean.getBoolean("rtxremix.gametest.horizonDiagnostics")) {
                    try (Layer fade = Layer.acquire(context, "horizon-no-fade", "rtx.viewDistance.distanceMode = 0")) {
                        capture(context, evidence, "sunset-no-distance-fade", true, 1, 1);
                    }
                    try (Layer clear = Layer.acquire(context, "horizon-no-fog", "rtx.volumetrics.enable = false")) {
                        capture(context, evidence, "sunset-no-fog", true, 1, 1);
                        try (Layer global = Layer.acquire(context, "horizon-global-tone", "rtx.tonemappingMode = 0")) {
                            capture(context, evidence, "sunset-global-tone", true, 1, 1);
                        }
                    }
                    try (RuntimeOptionScope reflection = RuntimeOptionScope.acquire(context, "rtx.enablePSRR", "false");
                         RuntimeOptionScope transmission = RuntimeOptionScope.acquire(context, "rtx.enablePSTR", "false");
                         RuntimeOptionScope debug = RuntimeOptionScope.acquire(context, "rtx.debugView.debugViewIdx", "22");
                         Layer material = Layer.acquire(context, "horizon-material", """
                            rtx.debugView.replaceCompositeOutput = true
                            rtx.debugView.displayType = 0
                            rtx.debugView.enableGammaCorrection = false
                            rtx.debugView.minValue = 0
                            rtx.debugView.maxValue = 1
                            """)) {
                        capture(context, evidence, "sunset-primary-material", true, 1, 1);
                    }
                }
                return;
            }
            // Both local tone mapping and the global histogram fit depend on scene content.
            // Compare fixed-exposure radiance through only the shared output's gamma curve.
            try (RuntimeOptionScope history = RuntimeOptionScope.acquire(context, "rtx.volumetrics.maxAccumulationFrames", "1");
                 Layer measurement = Layer.acquire(context, "measurement", MEASUREMENT)) {
                Path off = capture(context, evidence, "night-off", false, 0, 0);
                Path repeat = capture(context, evidence, "night-off-repeat", false, 0, 0);
                Delta noise = delta(off, repeat, false);
                Delta reflectionNoise = delta(off, repeat, true);
                evidence.add(Map.of("name", "baseline-noise", "sky", noise, "reflection", reflectionNoise));
                Path stars = capture(context, evidence, "night-stars", true, 4, 0);
                Path starsRepeat = capture(context, evidence, "night-stars-repeat", true, 4, 0);
                Delta starSignal = delta(off, stars, false);
                StarMetrics starMetrics = starMetrics(off, repeat, stars, starsRepeat);
                evidence.add(Map.of("name", "star-delta", "signal", starSignal, "noise", noise, "local", starMetrics));
                requireStars(starMetrics);
                Path galaxy = capture(context, evidence, "night-milky-way", true, 0, 4);
                Delta galaxySignal = delta(off, galaxy, false);
                requireSignal("Milky Way", galaxySignal, noise);
                if (galaxySignal.changedFraction < .01) {
                    throw new AssertionError("Milky Way has no extended radiance: " + galaxySignal);
                }
                Path both = capture(context, evidence, "night-reflection", true, 4, 4);
                requireSignal("Reflected night sky", delta(off, both, true), reflectionNoise);
                evidence.add(Map.of("name", "feature-deltas", "noise", noise, "reflectionNoise", reflectionNoise,
                        "stars", starSignal, "milkyWay", galaxySignal, "reflection", delta(off, both, true)));
                cloudOcclusion(context, evidence, off, repeat, stars, starsRepeat);
                meteors(context, evidence);
                clock(context, world, 6000);
                Path dayOff = capture(context, evidence, "day-off", false, 0, 0);
                Path dayRepeat = capture(context, evidence, "day-off-repeat", false, 0, 0);
                Path dayOn = capture(context, evidence, "day-on", true, 4, 4);
                requireSuppressed("Daylight", delta(dayOff, dayOn, false), delta(dayOff, dayRepeat, false));
                clock(context, world, 18000);
                dimensionCleanup(context, world, evidence);
            }
        } finally {
            RuntimeOptionScope.apply(context, Map.of("rtx.volumetrics.maxAccumulationFrames", original.get("rtx.volumetrics.maxAccumulationFrames"),
                    "rtx.clouds.transportMode", original.get("rtx.clouds.transportMode")));
            world.getServer().runOnServer(server -> RemixSceneFixtures.commands(server, "tick unfreeze"));
            context.runOnClient(client -> {
                client.options.cloudStatus().set(clouds);
                client.options.fov().set(fov);
                client.options.renderDistance().set(renderDistance);
                client.options.broadcastOptions();
                RtxRemixMod.renderer.getCloudRenderer().setProceduralClouds(procedural);
                if (client.gui.hud.isHidden() != hidden) client.gui.hud.toggle();
            });
            waitFrames(context);
            Map<String, String> restored = resolved(context, KEYS);
            evidence.add(Map.of("name", "restored", "resolved", restored));
            writeEvidence(context, evidence.records);
            if (!original.equals(restored)) {
                throw new AssertionError("Night sky fixture leaked config opinions: before=" + original + ", after=" + restored);
            }
        }
    }

    /** Uses the player's complete graphics configuration and a running world clock. */
    private static void normalDisplay(ClientGameTestContext context, TestSingleplayerContext world) {
        boolean hidden = context.computeOnClient(client -> client.gui.hud.isHidden());
        int fov = context.computeOnClient(client -> client.options.fov().get());
        int distance = context.computeOnClient(client -> client.options.renderDistance().get());
        Captures evidence = new Captures();
        try {
            context.runOnClient(client -> {
                if (!client.gui.hud.isHidden()) client.gui.hud.toggle();
                client.gui.toastManager().clear();
                client.options.fov().set(90);
                client.options.renderDistance().set(Integer.getInteger("rtxremix.gametest.renderDistance", 16));
                client.options.broadcastOptions();
            });
            if (Boolean.getBoolean("rtxremix.gametest.endSkyAudit")) {
                recordEndSkies(context, world, evidence);
                return;
            }
            prepare(context, world, false);
            for (int time : new int[]{6000, 18000}) {
                world.getServer().runOnServer(server -> RemixSceneFixtures.commands(server, "time set " + time));
                if (time == 18000 && Boolean.getBoolean("rtxremix.gametest.nightSkyHistoryAudit")) {
                    recordHistoryTransition(context, evidence);
                }
                recordNormalDisplay(context, evidence, time == 6000 ? "normal-day" : "normal-night");
            }
            recordNormalDisplay(context, evidence, "normal-night-continuing");
            if (Boolean.getBoolean("rtxremix.gametest.nightSkyReflectionAudit")) {
                recordNormalReflection(context, evidence);
                return;
            }
            if (Boolean.getBoolean("rtxremix.gametest.nightSkyHistoryAudit")) return;
            for (var dimension : List.of(Level.NETHER, Level.END)) {
                world.getServer().runOnServer(server -> RemixDimensionGameTest.buildAndEnter(server, dimension));
                context.waitFor(client -> client.level.dimension().equals(dimension) && client.gui.screen() == null, 2400);
                recordNormalDisplay(context, evidence, "normal-" + dimension.identifier().getPath());
            }
        } finally {
            context.runOnClient(client -> {
                if (client.gui.hud.isHidden() != hidden) client.gui.hud.toggle();
                client.options.fov().set(fov);
                client.options.renderDistance().set(distance);
                client.options.broadcastOptions();
            });
            writeEvidence(context, evidence.records);
        }
    }

    private static void recordNormalDisplay(ClientGameTestContext context, Captures evidence, String name) {
        context.waitFor(client -> RtxRemixMod.renderer.isSceneReady()
                && RtxRemixMod.renderer.getChunkManager().getTerrainDiagnostics().columnsMissingGeometry() == 0, 2400);
        // A viewing interval for interactive inspection, not a renderer-readiness condition.
        context.waitTicks(Integer.getInteger("rtxremix.gametest.normalDisplayTicks", 200));
        recordNormalFrame(context, evidence, name);
    }

    private static void recordEndSkies(ClientGameTestContext context, TestSingleplayerContext world, Captures evidence) {
        moveEndCamera(context, world, -112.5, 92, -72.5, -57, -6);
        context.waitFor(client -> client.level.dimension().equals(Level.END) && client.gui.screen() == null
                && "2".equals(read(NIGHT + "sceneType")), 2400);
        recordNormalDisplay(context, evidence, "end-central-island");
        evidence.add(Map.of("name", "end-options", "resolved", resolved(context,
                List.of("rtx.endSky.enabled", "rtx.endSky.intensity", "rtx.endSky.backgroundIntensity",
                        "rtx.endSky.haloIntensity", "rtx.endSky.animationSpeed", "rtx.nightSky.worldTimeSeconds",
                        "rtx.endSky.rotation", "rtx.endSky.elevation"))));
        aim(context, -63, -16);
        waitFrames(context);
        recordNormalDisplay(context, evidence, "end-accretion");
        aim(context, -105, -10);
        waitFrames(context);
        recordNormalDisplay(context, evidence, "end-ringed-planet");
        recordEndPlanetPhase(context, evidence, "end-planet-quarter-phase", 150);
        recordEndPlanetPhase(context, evidence, "end-planet-lit-phase", 300);
        moveEndCamera(context, world, 128.5, 82, -24.5, 100, 8);
        recordNormalDisplay(context, evidence, "end-island-side");
        moveEndCamera(context, world, 142.5, 74, 0.5, 90, 32);
        recordNormalDisplay(context, evidence, "end-void");
        moveEndCamera(context, world, 0.5, 20, -150.5, 0, -5);
        recordNormalDisplay(context, evidence, "end-below-island");
        aim(context, 180, 55);
        waitFrames(context);
        recordNormalDisplay(context, evidence, "end-downward-space");
        recordEndSettings(context, evidence);
        world.getServer().runOnServer(server -> RemixDimensionGameTest.buildAndEnter(server, Level.NETHER));
        context.waitFor(client -> client.level.dimension().equals(Level.NETHER) && client.gui.screen() == null
                && "0".equals(read(NIGHT + "sceneType")), 2400);
        recordNormalDisplay(context, evidence, "end-nether-transition");
        prepare(context, world, false);
        context.waitFor(client -> client.level.dimension().equals(Level.OVERWORLD) && client.gui.screen() == null
                && "1".equals(read(NIGHT + "sceneType")), 2400);
        recordNormalDisplay(context, evidence, "end-overworld-return");
    }

    private static void recordEndPlanetPhase(ClientGameTestContext context, Captures evidence, String name, int seconds) {
        try (Layer clock = Layer.acquire(context, name,
                "rtx.nightSky.worldTimeSeconds = " + seconds + "\nrtx.endSky.animationSpeed = 1")) {
            // Aim at the orbiting body's center while changing only its diagnostic clock.
            double phase = (seconds / 900.0 + .282) * Math.TAU;
            double tilt = Math.PI / 2 - .2 * Math.sin(phase + 3) + .1;
            double x = Math.sin(phase) * Math.sin(tilt);
            double y = -Math.cos(tilt);
            double z = Math.cos(phase) * Math.sin(tilt);
            aim(context, (float) Math.toDegrees(Math.atan2(-x, z)), (float) -Math.toDegrees(Math.asin(y)));
            waitFrames(context);
            recordNormalDisplay(context, evidence, name);
            evidence.add(Map.of("name", name + "-clock", "resolved", resolved(context,
                    List.of("rtx.nightSky.worldTimeSeconds", "rtx.endSky.animationSpeed"))));
        }
    }

    private static void moveEndCamera(ClientGameTestContext context, TestSingleplayerContext world,
                                      double x, double y, double z, float yaw, float pitch) {
        world.getServer().runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            if (!player.teleportTo(server.getLevel(Level.END), x, y, z, Set.of(), yaw, pitch, true)) {
                throw new AssertionError("Cannot move the End inspection camera");
            }
        });
        context.waitFor(client -> client.level.dimension().equals(Level.END)
                && Math.abs(client.player.getX() - x) < .01 && Math.abs(client.player.getY() - y) < .01
                && Math.abs(client.player.getZ() - z) < .01 && client.player.getAbilities().flying, 400);
        waitFrames(context);
    }

    private static void recordEndSettings(ClientGameTestContext context, Captures evidence) {
        context.runOnClient(client -> {
            RtxSodiumConfig.openEffects(null);
            RemixSettingsChecks.press(client.gui.screen(), "rtxremix.settings.category.sky");
            RemixSettingsChecks.press(client.gui.screen(), "rtxremix.settings.group.endSky");
        });
        context.waitTick();
        recordNormalFrame(context, evidence, "end-settings");
        context.runOnClient(client -> client.gui.screen().onClose());
    }

    private static void recordNormalReflection(ClientGameTestContext context, Captures evidence) {
        float pitch = context.computeOnClient(client -> client.player.getXRot());
        float yaw = context.computeOnClient(client -> client.player.getYRot());
        try {
            context.runOnClient(client -> {
                client.player.setXRot(25);
                client.player.xRotO = 25;
            });
            if (Boolean.getBoolean("rtxremix.gametest.nightSkyReflectionSignal")) {
                try (RuntimeOptionScope stars = RuntimeOptionScope.acquire(context, NIGHT + "starIntensity", "0");
                     RuntimeOptionScope galaxy = RuntimeOptionScope.acquire(context, NIGHT + "milkyWayIntensity", "0")) {
                    waitFrames(context);
                    recordNormalFrame(context, evidence, "normal-reflection-zero");
                }
            }
            waitFrames(context);
            recordNormalFrame(context, evidence, "normal-reflection-restored");
            float[] moon = context.computeOnClient(client ->
                    RtxRemixMod.renderer.getLightManager().getSkyLightState().moonPosition().clone());
            float moonYaw = (float) Math.toDegrees(Math.atan2(-moon[0], moon[2]));
            float moonElevation = (float) Math.toDegrees(Math.asin(moon[1]));
            aim(context, moonYaw, -moonElevation);
            waitFrames(context);
            recordNormalFrame(context, evidence, "normal-moon-facing");
            aim(context, moonYaw, moonElevation);
            waitFrames(context);
            recordNormalFrame(context, evidence, "normal-reflection-moon");
        } finally {
            context.runOnClient(client -> {
                client.player.setXRot(pitch);
                client.player.xRotO = pitch;
                client.player.setYRot(yaw);
                client.player.yRotO = yaw;
            });
        }
    }

    private static void recordHistoryTransition(ClientGameTestContext context, Captures evidence) {
        context.waitFor(client -> Boolean.parseBoolean(read("rtx.volumetrics.historyInputsChanging")), 200);
        long start = context.computeOnClient(client -> RemixFrameProbe.presents);
        for (int offset : new int[]{2, 8, 32}) {
            context.waitFor(client -> RemixFrameProbe.presents >= start + offset, 200);
            evidence.add(Map.of("name", "history-state-" + offset,
                    "elapsedFrames", context.computeOnClient(client -> RemixFrameProbe.presents - start),
                    "changing", context.computeOnClient(client -> read("rtx.volumetrics.historyInputsChanging")),
                    "qualityLimit", context.computeOnClient(client -> read("rtx.volumetrics.maxAccumulationFrames"))));
            recordNormalFrame(context, evidence, "night-transition-" + offset);
        }
        context.waitFor(client -> !Boolean.parseBoolean(read("rtx.volumetrics.historyInputsChanging")), 200);
    }

    private static void recordNormalFrame(ClientGameTestContext context, Captures evidence, String name) {
        context.runOnClient(client -> RemixWorldGameTest.require(
                client.level.getBlockState(client.gameRenderer.mainCamera().blockPosition()).isAir(),
                "Visual inspection camera is inside a block: " + client.gameRenderer.mainCamera().blockPosition()));
        Path path = RemixWorldGameTest.captureCompletedFrame(context, "night-sky-" + name);
        RemixWorldGameTest.requireVisibleWorld(path);
        evidence.add(Map.of("name", name, "image", path.toAbsolutePath().toString(),
                "sceneInputs", context.computeOnClient(RemixNightSkyChecks::sceneInputs),
                "cameraFov", context.computeOnClient(client -> client.gameRenderer.mainCamera().getFov()),
                "renderDistance", context.computeOnClient(client -> client.options.getEffectiveRenderDistance())));
        copySkyPanorama(context, path);
        if (Boolean.getBoolean("rtxremix.gametest.normalDisplayCaptureLinear")
                && (name.equals("normal-day") || name.equals("normal-night") || name.startsWith("normal-reflection-"))) {
            try (RuntimeOptionScope capture = RuntimeOptionScope.acquire(context, "rtx.captureDebugImage", "true")) {
                long frame = context.computeOnClient(client -> {
                    RtxRemixMod.renderer.getRemixApi().setConfigVariable("rtx.captureNextFrame", "true");
                    return RemixFrameProbe.presents;
                });
                context.waitFor(client -> RemixFrameProbe.presents >= frame + 2
                        && "false".equalsIgnoreCase(read("rtx.captureNextFrame")), 200);
                evidence.add(Map.of("name", name + "-linear", "frame", frame));
            }
        }
        context.runOnClient(client -> RemixWorldGameTest.require(RemixFrameProbe.failures == 0,
                "Native frame failed during normal visual inspection: " + name));
    }

    private static void prepare(ClientGameTestContext context, TestSingleplayerContext world, boolean freeze) {
        int distance = context.computeOnClient(client -> client.options.renderDistance().get());
        world.getServer().runOnServer(server -> {
            server.getPlayerList().setViewDistance(distance);
            if (freeze) {
                server.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
                server.getGameRules().set(GameRules.ADVANCE_WEATHER, false, server);
                server.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
                RemixSceneFixtures.commands(server, "weather clear");
            }
            var player = server.getPlayerList().getPlayers().getFirst();
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            // Six flat layers end at minY + 6; the camera stays above water and within the build range.
            double feet = server.overworld().getMinY() + 8;
            if (!player.teleportTo(server.overworld(), .5, feet, .5, Set.of(), 0, -8, true)) {
                throw new AssertionError("Night sky camera setup failed");
            }
        });
        context.waitFor(client -> client.player.getAbilities().flying
                && client.player.getY() > client.level.getMinY() + 7
                && Math.abs(client.player.getXRot() + 8) < .01, 200);
        context.waitFor(client -> "1".equals(read(NIGHT + "sceneType")), 200);
        context.waitFor(client -> client.options.getEffectiveRenderDistance() == distance, 200);
        // ClientLevel.hasChunk is unconditional in 26.3. Wait for published geometry
        // in each capture direction before freezing delivery, not merely a loaded world.
        int reach = Math.max(1, distance - 3);
        for (int side = 0; side < 4; side++) {
            int yaw = side * 90;
            int cx = side == 1 ? -reach : side == 3 ? reach : 0;
            int cz = side == 0 ? reach : side == 2 ? -reach : 0;
            context.runOnClient(client -> {
                client.player.setYRot(yaw);
                client.player.yRotO = yaw;
            });
            try {
                context.waitFor(client -> RtxRemixMod.renderer.getChunkManager().hasTerrainGeometry(cx, cz), 1200);
            } catch (AssertionError timeout) {
                throw new AssertionError("Night sky fixture has no water geometry at " + cx + "," + cz
                        + ": " + context.computeOnClient(client -> RtxRemixMod.renderer.getChunkManager().getTerrainDiagnostics()), timeout);
            }
        }
        context.runOnClient(client -> {
            client.player.setYRot(0);
            client.player.yRotO = 0;
        });
        context.waitFor(client -> RtxRemixMod.renderer.getChunkManager().getTerrainDiagnostics().columnsMissingGeometry() == 0, 1200);
        if (freeze) world.getServer().runOnServer(server -> RemixSceneFixtures.commands(server, "tick freeze"));
    }

    private static void referenceOcean(ClientGameTestContext context, TestSingleplayerContext world, Captures evidence) {
        var original = context.computeOnClient(client -> client.player.position());
        float yaw = context.computeOnClient(client -> client.player.getYRot());
        float pitch = context.computeOnClient(client -> client.player.getXRot());
        int waterHeight = context.computeOnClient(client -> Math.max(client.level.getSeaLevel(), client.level.getMinY() + 6) + 32);
        RemixHorizonOcean ocean = context.computeOnClient(client -> new RemixHorizonOcean(waterHeight));
        try (RuntimeOptionScope distance = RuntimeOptionScope.acquire(context, "rtx.viewDistance.distanceMode", "0")) {
            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                if (!player.teleportTo(server.overworld(), .5, waterHeight + 1.6, .5, Set.of(), yaw, pitch, true)) {
                    throw new AssertionError("Reference ocean camera setup failed");
                }
            });
            context.waitFor(client -> Math.abs(client.player.getY() - (waterHeight + 1.6)) < .01
                    && !client.player.isUnderWater(), 200);
            evidence.add(Map.of("name", "reference-ocean-geometry", "radius", 8192, "height", waterHeight,
                    "purpose", "Explicit optical reference; not LOD or observed-world coverage"));
            capture(context, evidence, "sunset-reference-ocean", true, 1, 1);
            settleClock(context, world, 6000);
            capture(context, evidence, "midday-reference-ocean", true, 1, 1);
            settleClock(context, world, 18000);
            capture(context, evidence, "night-reference-ocean", true, 1, 1);
        } finally {
            context.runOnClient(client -> ocean.close());
            world.getServer().runOnServer(server -> server.getPlayerList().getPlayers().getFirst().teleportTo(
                    server.overworld(), original.x, original.y, original.z, Set.of(), yaw, pitch, true));
            context.waitFor(client -> Math.abs(client.player.getY() - original.y) < .01, 200);
            settleClock(context, world, 12780);
        }
    }

    private static void celestialAppearance(ClientGameTestContext context, TestSingleplayerContext world, Captures evidence) {
        settleClock(context, world, 6000);
        float[] sun = context.computeOnClient(client -> RtxRemixMod.renderer.getLightManager().getSkyLightState().sunPosition());
        aim(context, (float) Math.toDegrees(Math.atan2(-sun[0], sun[2])),
                (float) -Math.toDegrees(Math.asin(sun[1])));
        capture(context, evidence, "sun-facing-day", true, 1, 1);
        for (int phase : new int[]{0, 2, 4}) {
            settleClock(context, world, phase * 24000 + 18000);
            float[] moon = context.computeOnClient(client -> RtxRemixMod.renderer.getLightManager().getSkyLightState().moonPosition());
            aim(context, (float) Math.toDegrees(Math.atan2(-moon[0], moon[2])),
                    (float) -Math.toDegrees(Math.asin(moon[1])));
            capture(context, evidence, "moon-phase-" + phase, true, 1, 1);
            evidence.add(context.computeOnClient(client -> Map.of("name", "moon-phase-input-" + phase,
                    "phase", client.gameRenderer.mainCamera().attributeProbe().getValue(EnvironmentAttributes.MOON_PHASE, 0f).index(),
                    "source", RtxRemixMod.renderer.getLightManager().getSkyLightState().moonSourceRadiance().clone())));
        }
        settleClock(context, world, 18000);
        aim(context, 0, -8);
    }

    private static void horizonFlight(ClientGameTestContext context, TestSingleplayerContext world, Captures evidence) {
        var original = context.computeOnClient(client -> client.player.position());
        float eyeHeight = context.computeOnClient(client -> client.player.getEyeHeight());
        int seaLevel = context.computeOnClient(client -> client.level.getSeaLevel());
        try {
            for (int height : new int[]{128, 1024, 10000}) {
                double y = seaLevel + height - eyeHeight;
                world.getServer().runOnServer(server -> {
                    var player = server.getPlayerList().getPlayers().getFirst();
                    if (!player.teleportTo(server.overworld(), original.x, y, original.z, Set.of(), 0, 0, true)) {
                        throw new AssertionError("Flight camera setup failed");
                    }
                });
                context.waitFor(client -> Math.abs(client.player.getY() - y) < .01, 200);
                settleClock(context, world, 6000);
                capture(context, evidence, "midday-flight-" + height, true, 1, 1);
                settleClock(context, world, 18000);
                capture(context, evidence, "night-flight-" + height, true, 1, 1);
            }
        } finally {
            world.getServer().runOnServer(server -> server.getPlayerList().getPlayers().getFirst().teleportTo(
                    server.overworld(), original.x, original.y, original.z, Set.of(), 0, -8, true));
            context.waitFor(client -> Math.abs(client.player.getY() - original.y) < .01, 200);
        }
        float[] moon = context.computeOnClient(client -> RtxRemixMod.renderer.getLightManager().getSkyLightState().moonPosition());
        aim(context, (float) Math.toDegrees(Math.atan2(-moon[0], moon[2])),
                (float) -Math.toDegrees(Math.asin(moon[1])));
        capture(context, evidence, "moon-facing-night", true, 1, 1);
        aim(context, 0, -8);
    }

    private static void clock(ClientGameTestContext context, TestSingleplayerContext world, int ticks) {
        world.getServer().runOnServer(server -> {
            server.clockManager().setTotalTicks(server.registryAccess()
                    .get(net.minecraft.world.clock.WorldClocks.OVERWORLD).orElseThrow(), ticks);
            server.getPlayerList().broadcastAll(server.clockManager().createFullSyncPacket());
        });
        context.waitFor(client -> client.level.getOverworldClockTime() == ticks, 200);
    }

    private static void cloudOcclusion(ClientGameTestContext context, Captures evidence, Path clearOff,
                                       Path clearRepeat, Path clearStars, Path clearStarsRepeat) {
        try (Layer cloud = Layer.acquire(context, "overcast", """
                rtx.clouds.coverage = 1
                rtx.clouds.extinctionPerMeter = 0.2
                """)) {
            context.runOnClient(client -> client.options.cloudStatus().set(CloudStatus.FANCY));
            context.waitFor(client -> Boolean.parseBoolean(read("rtx.clouds.sceneEnabled")), 200);
            Path off = capture(context, evidence, "overcast-off", false, 0, 0);
            Path repeat = capture(context, evidence, "overcast-off-repeat", false, 0, 0);
            Path on = capture(context, evidence, "overcast-stars", true, 4, 0);
            Path transmittance = captureCloudTransmittance(context, evidence);
            CloudMetrics metrics = cloudMetrics(clearOff, clearRepeat, clearStars, clearStarsRepeat, off, repeat, on, transmittance);
            evidence.add(Map.of("name", "cloud-occlusion", "transmittanceImage", transmittance.toAbsolutePath().toString(),
                    "transmittanceEncoding", "T^(1/2.2); black code 0 selects deep opacity, codes >=254 select clear holes",
                    "probes", metrics));
            if (metrics.opaqueProbes < 10 || metrics.transparentProbes < 10) {
                throw new AssertionError("Cloud fixture lacks 10 strong repeatable stars in each actual opaque/transparent mask: " + metrics);
            }
            if (metrics.suppressedOpaque != metrics.opaqueProbes || metrics.retainedTransparent != metrics.transparentProbes) {
                throw new AssertionError("Cloud transmittance mask disagrees with celestial point-source occlusion: " + metrics);
            }
        } finally {
            context.runOnClient(client -> client.options.cloudStatus().set(CloudStatus.OFF));
            context.waitFor(client -> !Boolean.parseBoolean(read("rtx.clouds.sceneEnabled")), 200);
        }
    }

    private static Path captureCloudTransmittance(ClientGameTestContext context, Captures evidence) {
        Map<String, String> options = Map.of("rtx.debugView.debugViewIdx", "54", "rtx.debugView.displayType", "0",
                "rtx.debugView.pseudoColorMode", "0", "rtx.debugView.enableGammaCorrection", "true",
                "rtx.debugView.minValue", "0", "rtx.debugView.maxValue", "1",
                "rtx.debugView.replaceCompositeOutput", "false", "rtx.debugView.accumulation.enable", "false");
        Map<String, String> original = resolved(context, new ArrayList<>(options.keySet()));
        try {
            RuntimeOptionScope.apply(context, options);
            return readback(context, evidence, "cloud-transmittance");
        } finally {
            RuntimeOptionScope.apply(context, original);
        }
    }

    private static void settleClock(ClientGameTestContext context, TestSingleplayerContext world, int ticks) {
        clock(context, world, ticks);
        waitForStableInputs(context, "default-clock-" + ticks);
    }

    private static void dimensionCleanup(ClientGameTestContext context, TestSingleplayerContext world, Captures evidence) {
        for (var dimension : List.of(Level.NETHER, Level.END, Level.OVERWORLD)) {
            world.getServer().runOnServer(server -> {
                var level = server.getLevel(dimension);
                var player = server.getPlayerList().getPlayers().getFirst();
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                if (!player.teleportTo(level, .5, 100, .5, Set.of(), 0, -25, true)) {
                    throw new AssertionError("Night sky dimension transition failed: " + dimension);
                }
            });
            boolean overworld = dimension.equals(Level.OVERWORLD);
            context.waitFor(client -> client.level.dimension().equals(dimension) && client.gui.screen() == null
                    && Integer.parseInt(read(NIGHT + "sceneType")) == (overworld ? 1 : dimension.equals(Level.END) ? 2 : 0), 2400);
            if (overworld) {
                context.waitFor(client -> value(NIGHT + "visibility") > .45, 200);
                capture(context, evidence, "overworld-return", true, 1, 1);
            } else {
                String name = dimension.identifier().getPath();
                Path off = capture(context, evidence, name + "-off", false, 0, 0);
                Path repeat = capture(context, evidence, name + "-off-repeat", false, 0, 0);
                Path on = capture(context, evidence, name + "-on", true, 4, 4);
                requireSuppressed(name, delta(off, on, false), delta(off, repeat, false));
            }
        }
    }

    private static void meteors(ClientGameTestContext context, Captures evidence) {
        // Event zero is isolated at six events/minute. Pin its world clock in a higher layer,
        // so convergence and screenshot readback cannot advance past a short-lived event.
        double start = 7 * randomMeteor(739);
        double duration = .8 + .4 * randomMeteor(731);
        double azimuth = randomMeteor(732) * Math.PI * 2;
        double elevation = .35 + randomMeteor(733) * .8;
        double heading = (randomMeteor(734) - .5) * 1.6;
        double angle = .45 * .32;
        double x = Math.cos(azimuth) * Math.cos(elevation) * Math.cos(angle)
                + (-Math.sin(azimuth) * Math.cos(heading) + Math.cos(azimuth) * Math.sin(elevation) * Math.sin(heading)) * Math.sin(angle);
        double y = Math.sin(elevation) * Math.cos(angle) - Math.cos(elevation) * Math.sin(heading) * Math.sin(angle);
        double z = Math.sin(azimuth) * Math.cos(elevation) * Math.cos(angle)
                + (Math.cos(azimuth) * Math.cos(heading) + Math.sin(azimuth) * Math.sin(elevation) * Math.sin(heading)) * Math.sin(angle);
        float yaw = (float) Math.toDegrees(Math.atan2(-x, z));
        float pitch = (float) -Math.toDegrees(Math.asin(y)) + 18;
        try {
            aim(context, yaw, pitch);
            String base = NIGHT + "enabled = true\n" + NIGHT + "starIntensity = 0\n"
                    + NIGHT + "milkyWayIntensity = 0\n" + NIGHT + "meteorIntensity = 8\n";
            String time = NIGHT + "worldTimeSeconds = ";
            Path off = capture(context, evidence, "meteor-off", base + NIGHT + "meteorRate = 0\n" + time + (start + duration * .25));
            Path repeat = capture(context, evidence, "meteor-off-repeat", base + NIGHT + "meteorRate = 0\n" + time + (start + duration * .25));
            String active = base + NIGHT + "meteorRate = 6\n" + time;
            Path early = capture(context, evidence, "meteor-early", active + (start + duration * .25));
            Path late = capture(context, evidence, "meteor-late", active + (start + duration * .65));
            Path expired = capture(context, evidence, "meteor-expired", active + (start + duration + .1));
            Delta noise = delta(off, repeat, false);
            requireMeteor("Meteor", delta(off, early, false), noise);
            requireMeteor("Meteor movement", delta(early, late, false), noise);
            requireSuppressed("Expired meteor", delta(off, expired, false), noise);
            evidence.add(Map.of("name", "meteor-timing", "startSeconds", start, "durationSeconds", duration,
                    "yaw", yaw, "pitch", pitch, "early", delta(off, early, false),
                    "movement", delta(early, late, false), "expired", delta(off, expired, false)));
        } finally {
            aim(context, 0, -8);
        }
    }

    private static double randomMeteor(int value) {
        value ^= value >>> 16;
        value *= 0x7feb352d;
        value ^= value >>> 15;
        value *= 0x846ca68b;
        value ^= value >>> 16;
        return (value >>> 8) / 16777216.0;
    }

    private static void aim(ClientGameTestContext context, float yaw, float pitch) {
        context.runOnClient(client -> {
            client.player.setYRot(yaw);
            client.player.yRotO = yaw;
            client.player.setXRot(pitch);
            client.player.xRotO = pitch;
        });
    }

    private static Path capture(ClientGameTestContext context, Captures evidence, String name,
                                boolean enabled, int stars, int galaxy) {
        String options = NIGHT + "enabled = " + enabled + "\n" + NIGHT + "starIntensity = " + stars + "\n"
                + NIGHT + "milkyWayIntensity = " + galaxy + "\n" + NIGHT + "meteorRate = 0\n";
        return capture(context, evidence, name, options);
    }

    private static Path capture(ClientGameTestContext context, Captures evidence, String name, String options) {
        // Keep the old opinion active until its replacement resolves. Otherwise the frames
        // between screenshots restore default stars and contaminate RR and medium history.
        evidence.activate(context, name, options);
        return readback(context, evidence, name);
    }

    private static Path readback(ClientGameTestContext context, Captures evidence, String name) {
        SceneInputs inputs = waitForStableInputs(context, name);
        FrameStamp requested = stamp(context);
        Path path = RemixWorldGameTest.captureCompletedFrame(context, "night-sky-" + name);
        FrameStamp completed = stamp(context);
        List<String> keys = new ArrayList<>(KEYS);
        keys.addAll(List.of(NIGHT + "sceneType", NIGHT + "visibility", NIGHT + "starAngle", NIGHT + "worldTimeSeconds"));
        Map<String, Object> record = new LinkedHashMap<>(Map.of("name", name, "image", path.toAbsolutePath().toString(),
                "resolved", resolved(context, keys), "captureRequested", requested, "captureCompleted", completed, "sceneInputs", inputs));
        record.put("cameraFov", context.computeOnClient(client -> client.gameRenderer.mainCamera().getFov()));
        record.put("renderDistance", context.computeOnClient(client -> client.options.getEffectiveRenderDistance()));
        record.put("terrain", context.computeOnClient(client -> RtxRemixMod.renderer.getChunkManager().getTerrainDiagnostics()));
        record.put("farTerrain", context.computeOnClient(client -> RtxRemixMod.renderer.getChunkManager().getLodDiagnostics()));
        String panorama = copySkyPanorama(context, path);
        if (panorama != null) record.put("skyPanorama", panorama);
        evidence.add(record);
        context.runOnClient(client -> RemixWorldGameTest.require(RemixFrameProbe.failures == 0,
                "Native frame failed while checking night sky: " + name));
        return path;
    }

    private static String copySkyPanorama(ClientGameTestContext context, Path screenshot) {
        String directory = System.getProperty("rtxremix.skySnapshotDirectory");
        if (directory == null) return null;
        return context.computeOnClient(client -> {
            if (client.level == null || !client.level.dimension().equals(Level.OVERWORLD)) return null;
            Path source = Path.of(directory).resolve("sky-panorama.rgba16f");
            if (!Files.isRegularFile(source)) return null;
            Path destination = source.resolveSibling(screenshot.getFileName().toString().replace(".png", ".rgba16f"));
            try {
                // SkyRenderer writes this file on the same thread; copying here cannot race its next generation.
                Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                Files.copy(source.resolveSibling("sky-transmittance.rgba16f"),
                        source.resolveSibling(screenshot.getFileName().toString().replace(".png", "-transmittance.rgba16f")),
                        StandardCopyOption.REPLACE_EXISTING);
                Files.copy(source.resolveSibling("sky-irradiance.json"),
                        source.resolveSibling(screenshot.getFileName().toString().replace(".png", "-irradiance.json")),
                        StandardCopyOption.REPLACE_EXISTING);
                return destination.toAbsolutePath().toString();
            } catch (IOException e) {
                throw new AssertionError("Cannot preserve sky panorama for " + screenshot, e);
            }
        });
    }

    private static void requireSignal(String name, Delta signal, Delta noise) {
        if (signal.mean <= Math.max(.05, noise.mean * 3)
                || signal.changedFraction <= Math.max(.0005, noise.changedFraction * 2)) {
            throw new AssertionError(name + " radiance is absent: signal=" + signal + ", noise=" + noise);
        }
    }

    private static StarMetrics starMetrics(Path off, Path offRepeat, Path stars, Path starsRepeat) {
        StarPlane noise = starPlane(off, offRepeat);
        double noiseMaximum = Arrays.stream(noise.contrast).map(Math::abs).max().orElseThrow();
        double cutoff = Math.max(3, noiseMaximum);
        StarPlane first = peaks(starPlane(off, stars), cutoff);
        StarPlane second = peaks(starPlane(off, starsRepeat), cutoff);
        boolean[] excluded = new boolean[first.peaks.length];
        int count = 0, repeated = 0;
        for (int y = 0; y < first.height; y++) for (int x = 0; x < first.width; x++) {
            int index = y * first.width + x;
            if (first.peaks[index]) {
                count++;
                if (near(second.peaks, first.width, first.height, x, y, first.radius)) repeated++;
            }
            if (first.peaks[index] || second.peaks[index]) {
                fill(excluded, first.width, first.height, x, y, first.radius * 2);
            }
        }
        return new StarMetrics(count, repeated, cutoff, noiseMaximum,
                blank(noise, excluded), blank(first, excluded));
    }

    private static CloudMetrics cloudMetrics(Path clearOff, Path clearRepeat, Path clearStars, Path clearStarsRepeat,
                                              Path cloudOff, Path cloudRepeat, Path cloudStars, Path transmittance) {
        double clearNoise = Arrays.stream(starPlane(clearOff, clearRepeat).contrast).map(Math::abs).max().orElseThrow();
        double cloudNoise = Arrays.stream(starPlane(cloudOff, cloudRepeat).contrast).map(Math::abs).max().orElseThrow();
        double cutoff = Math.max(3, Math.max(clearNoise, cloudNoise));
        StarPlane clear = peaks(starPlane(clearOff, clearStars), cutoff);
        StarPlane repeated = peaks(starPlane(clearOff, clearStarsRepeat), cutoff);
        StarPlane covered = starPlane(cloudOff, cloudStars);
        BufferedImage map = load(transmittance), reference = load(clearOff);
        RemixWorldGameTest.require(map.getWidth() == reference.getWidth() && map.getHeight() == reference.getHeight(),
                "Cloud transmittance capture size changed");
        int x0 = map.getWidth() / 10, y0 = map.getHeight() * 8 / 100;
        int opaque = 0, suppressed = 0, transparent = 0, retained = 0, partial = 0;
        int opaquePixels = 0, transparentPixels = 0;
        List<CloudProbe> probes = new ArrayList<>();
        for (int y = 0; y < clear.height; y++) for (int x = 0; x < clear.width; x++) {
            int code = transmittanceCode(map, x + x0, y + y0);
            if (code == 0) opaquePixels++;
            if (code >= 254) transparentPixels++;
            int index = y * clear.width + x;
            if (!clear.peaks[index] || clear.contrast[index] <= cutoff * 3
                    || !near(repeated.peaks, clear.width, clear.height, x, y, clear.radius)) continue;
            int minimum = 255, maximum = 0;
            // Erode both masks past the stellar PSF and the cloud map's linear filter footprint.
            for (int dy = -clear.radius * 2; dy <= clear.radius * 2; dy++) {
                for (int dx = -clear.radius * 2; dx <= clear.radius * 2; dx++) {
                    int value = transmittanceCode(map, x + x0 + dx, y + y0 + dy);
                    minimum = Math.min(minimum, value);
                    maximum = Math.max(maximum, value);
                }
            }
            double signal = localMaximum(covered, x, y);
            String mask;
            if (maximum == 0) {
                mask = "opaque";
                opaque++;
                if (signal <= cutoff) suppressed++;
            } else if (minimum >= 254) {
                mask = "transparent";
                transparent++;
                if (signal > cutoff) retained++;
            } else {
                mask = "partial";
                partial++;
            }
            probes.add(new CloudProbe(x + x0, y + y0, mask, clear.contrast[index], signal, minimum, maximum));
        }
        return new CloudMetrics(opaque, suppressed, transparent, retained, partial, opaquePixels, transparentPixels,
                cutoff, cloudNoise, probes);
    }

    private static int transmittanceCode(BufferedImage map, int x, int y) {
        int rgb = map.getRGB(x, y);
        int red = (rgb >>> 16) & 255, green = (rgb >>> 8) & 255, blue = rgb & 255;
        RemixWorldGameTest.require(Math.abs(red - green) <= 1 && Math.abs(blue - green) <= 1,
                "Cloud transmittance debug image is not a scalar map");
        return green;
    }

    private static double localMaximum(StarPlane plane, int x, int y) {
        double maximum = Double.NEGATIVE_INFINITY;
        for (int yy = Math.max(0, y - plane.radius); yy <= Math.min(plane.height - 1, y + plane.radius); yy++) {
            for (int xx = Math.max(0, x - plane.radius); xx <= Math.min(plane.width - 1, x + plane.radius); xx++) {
                maximum = Math.max(maximum, plane.contrast[yy * plane.width + xx]);
            }
        }
        return maximum;
    }

    private static void requireStars(StarMetrics metrics) {
        double driftLimit = Math.max(.1, metrics.baselineBlank.mad * 3);
        if (Math.abs(metrics.baselineBlank.median) > driftLimit
                || Math.abs(metrics.starBlank.median) > driftLimit) {
            throw new AssertionError("Star baseline is drifting outside compact sources: " + metrics);
        }
        if (metrics.independentPeaks < 20 || metrics.repeatedPeaks < 20) {
            throw new AssertionError("Night sky has fewer than 20 independent, repeatable stars above control noise: " + metrics);
        }
    }

    private static StarPlane starPlane(Path baseline, Path candidate) {
        BufferedImage a = load(baseline), b = load(candidate);
        RemixWorldGameTest.require(a.getWidth() == b.getWidth() && a.getHeight() == b.getHeight(), "Star capture size changed");
        int x0 = a.getWidth() / 10, y0 = a.getHeight() * 8 / 100;
        int width = a.getWidth() * 9 / 10 - x0, height = a.getHeight() * 42 / 100 - y0;
        int radius = Math.max(1, Math.round(a.getHeight() / 360f));
        double[] difference = new double[width * height], contrast = new double[difference.length];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            difference[y * width + x] = luminance(b.getRGB(x + x0, y + y0)) - luminance(a.getRGB(x + x0, y + y0));
        }
        // A compact source raises its center above an annulus. Smooth exposure or fog
        // drift remains in the blank statistics instead of masquerading as star flux.
        for (int y = radius; y < height - radius; y++) for (int x = radius; x < width - radius; x++) {
            int i = y * width + x;
            contrast[i] = difference[i] - (difference[i - radius] + difference[i + radius]
                    + difference[i - radius * width] + difference[i + radius * width]) * .25;
        }
        return new StarPlane(width, height, radius, difference, contrast, new boolean[difference.length]);
    }

    private static StarPlane peaks(StarPlane plane, double cutoff) {
        boolean[] occupied = new boolean[plane.peaks.length];
        int margin = plane.radius + 1;
        for (int y = margin; y < plane.height - margin; y++) for (int x = margin; x < plane.width - margin; x++) {
            int index = y * plane.width + x;
            double center = plane.contrast[index];
            if (occupied[index] || center <= cutoff) continue;
            boolean maximum = true;
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                int neighbour = index + dy * plane.width + dx;
                if (plane.contrast[neighbour] > center
                        || (plane.contrast[neighbour] == center && neighbour < index)) maximum = false;
            }
            if (maximum) {
                plane.peaks[index] = true;
                fill(occupied, plane.width, plane.height, x, y, plane.radius);
            }
        }
        return plane;
    }

    private static boolean near(boolean[] mask, int width, int height, int x, int y, int radius) {
        for (int yy = Math.max(0, y - radius); yy <= Math.min(height - 1, y + radius); yy++) {
            for (int xx = Math.max(0, x - radius); xx <= Math.min(width - 1, x + radius); xx++) {
                if (mask[yy * width + xx]) return true;
            }
        }
        return false;
    }

    private static void fill(boolean[] mask, int width, int height, int x, int y, int radius) {
        for (int yy = Math.max(0, y - radius); yy <= Math.min(height - 1, y + radius); yy++) {
            Arrays.fill(mask, yy * width + Math.max(0, x - radius), yy * width + Math.min(width, x + radius + 1), true);
        }
    }

    private static Blank blank(StarPlane plane, boolean[] excluded) {
        double[] values = new double[plane.difference.length];
        int count = 0, margin = plane.radius + 1;
        for (int y = margin; y < plane.height - margin; y++) for (int x = margin; x < plane.width - margin; x++) {
            int index = y * plane.width + x;
            if (!excluded[index]) values[count++] = plane.difference[index];
        }
        Arrays.sort(values, 0, count);
        double median = values[count / 2];
        for (int i = 0; i < count; i++) values[i] = Math.abs(values[i] - median);
        Arrays.sort(values, 0, count);
        return new Blank(median, values[count / 2]);
    }

    private static void requireSuppressed(String name, Delta signal, Delta noise) {
        if (signal.mean > noise.mean * 3 + .1) {
            throw new AssertionError(name + " leaked night sky: signal=" + signal + ", noise=" + noise);
        }
    }

    private static void requireMeteor(String name, Delta signal, Delta noise) {
        if (signal.changedFraction <= Math.max(.00005, noise.changedFraction * 2)
                || signal.brightPoints <= Math.max(10, noise.brightPoints * 2)) {
            throw new AssertionError(name + " streak is absent: signal=" + signal + ", noise=" + noise);
        }
    }

    private static Delta delta(Path before, Path after, boolean reflection) {
        BufferedImage a = load(before), b = load(after);
        RemixWorldGameTest.require(a.getWidth() == b.getWidth() && a.getHeight() == b.getHeight(), "Night sky capture size changed");
        long count = 0, changed = 0, points = 0;
        double total = 0;
        int y0 = a.getHeight() * (reflection ? 72 : 8) / 100;
        int y1 = a.getHeight() * (reflection ? 92 : 42) / 100;
        for (int y = y0; y < y1; y++) for (int x = a.getWidth() / 10; x < a.getWidth() * 9 / 10; x++) {
            double difference = Math.abs(luminance(a.getRGB(x, y)) - luminance(b.getRGB(x, y)));
            total += difference;
            if (difference > 2) changed++;
            double center = luminance(b.getRGB(x, y));
            double surround = (luminance(b.getRGB(x - 1, y)) + luminance(b.getRGB(x + 1, y))
                    + luminance(b.getRGB(x, y - 1)) + luminance(b.getRGB(x, y + 1))) * .25;
            if (difference > 3 && center - surround > 2) points++;
            count++;
        }
        return new Delta(total / count, (double) changed / count, points);
    }

    private static double luminance(int rgb) {
        return ((rgb >>> 16) & 255) * .2126 + ((rgb >>> 8) & 255) * .7152 + (rgb & 255) * .0722;
    }

    private static BufferedImage load(Path path) {
        try {
            BufferedImage image = ImageIO.read(path.toFile());
            if (image == null) throw new AssertionError("Cannot decode night sky screenshot: " + path);
            return image;
        } catch (IOException e) {
            throw new AssertionError("Cannot read night sky screenshot: " + path, e);
        }
    }

    private static Map<String, String> resolved(ClientGameTestContext context, List<String> keys) {
        return context.computeOnClient(client -> {
            Map<String, String> values = new LinkedHashMap<>();
            for (String key : keys) values.put(key, read(key));
            return values;
        });
    }

    private static String read(String key) { return RtxRemixMod.renderer.getRemixApi().getConfigVariableResolved(key); }
    private static double value(String key) { return Double.parseDouble(read(key)); }

    private static FrameStamp stamp(ClientGameTestContext context) {
        return context.computeOnClient(client -> new FrameStamp(System.nanoTime(), System.currentTimeMillis(),
                RemixFrameProbe.submits, RemixFrameProbe.presents));
    }

    private static SceneInputs waitForStableInputs(ClientGameTestContext context, String name) {
        SceneInputs[] previous = {null};
        long[] stableSince = {-1};
        try {
            context.waitFor(client -> {
                SceneInputs current = sceneInputs(client);
                if (!current.equals(previous[0])) {
                    previous[0] = current;
                    stableSince[0] = RemixFrameProbe.presents;
                }
                return RtxRemixMod.renderer.isSceneReady()
                        && RtxRemixMod.renderer.getChunkManager().getTerrainDiagnostics().columnsMissingGeometry() == 0
                        && current.layers.stream().noneMatch(layer -> layer.name().endsWith(LogicChannels.VOLUMETRICS_SETTLING)
                                && layer.strength() != 0)
                        && RemixFrameProbe.presents >= stableSince[0] + 32;
            }, 1200);
        } catch (AssertionError timeout) {
            SceneInputs current = context.computeOnClient(RemixNightSkyChecks::sceneInputs);
            throw new AssertionError("Night sky inputs did not settle for " + name + ": previous="
                    + previous[0] + ", current=" + current, timeout);
        }
        return previous[0];
    }

    private static SceneInputs sceneInputs(Minecraft client) {
        var camera = client.gameRenderer.mainCamera();
        var probe = camera.attributeProbe();
        var position = camera.position();
        var renderer = RtxRemixMod.renderer;
        var sky = renderer.getLightManager().getSkyLightState();
        List<Float> light = new ArrayList<>();
        for (float[] vector : List.of(sky.sunPosition(), sky.moonPosition(), sky.sunSourceRadiance(),
                sky.moonSourceRadiance(), sky.sunDirectRadiance(), sky.moonDirectRadiance())) {
            for (float value : vector) light.add(value);
        }
        Map<String, String> options = new LinkedHashMap<>();
        for (String key : KEYS) options.put(key, read(key));
        for (String key : ENVIRONMENT_KEYS) options.put(key, read(key));
        return new SceneInputs(client.level.dimension().identifier().toString(), position.x, position.y, position.z,
                client.player.getYRot(), client.player.getXRot(), client.level.getGameTime(),
                client.level.getOverworldClockTime(), probe.getValue(EnvironmentAttributes.SUN_ANGLE, 0f),
                probe.getValue(EnvironmentAttributes.MOON_ANGLE, 0f), probe.getValue(EnvironmentAttributes.STAR_ANGLE, 0f),
                probe.getValue(EnvironmentAttributes.STAR_BRIGHTNESS, 0f), client.level.getRainLevel(0f),
                client.level.getThunderLevel(0f), SkyAtmosphere.observerHeight(position.y - client.level.getSeaLevel()),
                renderer.getConfLayerDriver().snapshot(), light, options);
    }

    private static void waitFrames(ClientGameTestContext context) {
        long first = context.computeOnClient(client -> RemixFrameProbe.presents);
        context.waitFor(client -> RemixFrameProbe.presents >= first + 32, 1200);
    }

    private static void writeEvidence(ClientGameTestContext context, List<Object> evidence) {
        Path path = context.computeOnClient(client -> client.gameDirectory.toPath().resolve("night-sky.json"));
        try {
            Files.writeString(path, new Gson().toJson(evidence));
        } catch (IOException e) {
            throw new AssertionError("Cannot save night sky evidence", e);
        }
    }

    private record Layer(ClientGameTestContext context, Path path, long handle) implements AutoCloseable {
        static Layer acquire(ClientGameTestContext context, String name, String options) {
            return acquire(context, name, options, 12000);
        }

        static Layer acquire(ClientGameTestContext context, String name, String options, int priority) {
            Path directory = context.computeOnClient(client -> client.gameDirectory.toPath());
            Path path;
            try {
                path = Files.createTempFile(directory, "gametest-night-" + name + "-", ".conf");
                Files.writeString(path, options);
            } catch (IOException e) {
                throw new AssertionError("Cannot create night sky config layer", e);
            }
            long handle;
            try {
                handle = context.computeOnClient(client -> RtxRemixMod.renderer.getRemixApi()
                        .acquireConfigLayer(path.toAbsolutePath().toString(), priority, .5f));
            } catch (RuntimeException | Error failure) {
                delete(path);
                throw failure;
            }
            Layer layer = new Layer(context, path, handle);
            try {
                context.runOnClient(client -> RtxRemixMod.renderer.getRemixApi().setConfigLayerBlending(handle, 1, .5f));
                Map<String, String> expected = new LinkedHashMap<>();
                options.lines().filter(line -> line.contains("=")).forEach(line -> {
                    String[] parts = line.split("=", 2);
                    expected.put(parts[0].strip(), parts[1].strip());
                });
                try {
                    context.waitFor(client -> RuntimeOptionScope.mismatches(expected).isEmpty(), 200);
                } catch (AssertionError timeout) {
                    Map<String, String> mismatch = context.computeOnClient(client -> RuntimeOptionScope.mismatches(expected));
                    throw new AssertionError("Night sky config layer '" + name + "' did not resolve: " + mismatch
                            + ". Dynamic conf layers resolve below native User Settings and Quality Presets;"
                            + " check the isolated fixture's saved settings for masking opinions.", timeout);
                }
                return layer;
            } catch (RuntimeException | Error failure) {
                layer.close();
                throw failure;
            }
        }

        @Override
        public void close() {
            try {
                context.runOnClient(client -> RtxRemixMod.renderer.getRemixApi().releaseConfigLayer(handle));
            } finally {
                delete(path);
            }
        }

        private static void delete(Path path) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                throw new AssertionError("Cannot remove night sky config layer: " + path, e);
            }
        }
    }
}
