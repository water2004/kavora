/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.gametest;

import org.edtp.kavora.KavoraMod;
import org.edtp.kavora.UnfocusedFramerateLimit;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CloudStatus;
import net.minecraft.util.Mth;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;

/** Exercises the same cloud density field below, within and above its world-space volume. */
public final class KavoraVolumetricCloudGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        if (!Boolean.getBoolean("kavora.gametest.cloudsOnly")) return;
        context.getInput().resizeWindow(1920, 1080);
        CloudStatus originalClouds = context.computeOnClient(client -> client.options.cloudStatus().get());
        boolean hidden = context.computeOnClient(client -> client.gui.hud.isHidden());
        int distance = context.computeOnClient(client -> client.options.renderDistance().get());
        int backgroundLimit = context.computeOnClient(client -> UnfocusedFramerateLimit.get());
        boolean style = context.computeOnClient(client -> KavoraMod.renderer.getCloudRenderer().isProceduralClouds());
        String originalPreset = context.computeOnClient(client -> KavoraMod.renderer.getRemixApi()
                .getConfigVariableResolved("rtx.graphicsPreset"));
        String originalDlssPreset = context.computeOnClient(client -> KavoraMod.renderer.getRemixApi()
                .getConfigVariableResolved("rtx.dlssPreset"));
        String[] controls = {"rtx.upscalerType", "rtx.qualityDLSS", "rtx.enableRayReconstruction", "rtx.sparseRendering.enableSparseRendering",
                "rtx.clouds.transportMode",
                "rtx.dlfg.enable", "rtx.clouds.windSpeed", "rtx.clouds.lightSteps", "rtx.clouds.multipleScattering", "rtx.autoExposure.autoExposureSpeed",
                "rtx.captureDebugImage", "rtx.captureNextFrame",
                "rtx.debugView.debugViewIdx", "rtx.debugView.maxValue", "rtx.debugView.replaceCompositeOutput"};
        String[] original = context.computeOnClient(client -> java.util.Arrays.stream(controls)
                .map(key -> KavoraMod.renderer.getRemixApi().getConfigVariableResolved(key)).toArray(String[]::new));
        context.runOnClient(client -> {
            client.options.renderDistance().set(2);
            UnfocusedFramerateLimit.set(UnfocusedFramerateLimit.UNLIMITED);
        });
        try (var world = context.worldBuilder().create()) {
            KavoraWorldGameTest.assertWorldRenders(context, world, "cloud-fixture");
            world.getServer().runOnServer(server -> {
                server.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
                var player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.CREATIVE);
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
            });
            setClock(context, world, 6000);
            context.runOnClient(client -> {
                var api = KavoraMod.renderer.getRemixApi();
                api.setConfigVariable("rtx.graphicsPreset", "4");
                api.setConfigVariable("rtx.dlssPreset", "2");
            });
            context.waitFor(client -> KavoraMod.renderer.getRemixApi()
                    .getConfigVariableResolved("rtx.graphicsPreset").equals("4"), 100);
            context.runOnClient(client -> {
                var api = KavoraMod.renderer.getRemixApi();
                api.setConfigVariable("rtx.upscalerType", "1");
                api.setConfigVariable("rtx.qualityDLSS", "5");
                api.setConfigVariable("rtx.enableRayReconstruction", "true");
                api.setConfigVariable("rtx.sparseRendering.enableSparseRendering", "true");
                api.setConfigVariable("rtx.clouds.transportMode", "0");
                api.setConfigVariable("rtx.dlfg.enable", "false");
                api.setConfigVariable("rtx.clouds.windSpeed", "0");
                api.setConfigVariable("rtx.debugView.debugViewIdx", "0");
                if (!client.gui.hud.isHidden()) client.gui.hud.toggle();
                KavoraMod.renderer.getCloudRenderer().setProceduralClouds(true);
            });
            context.waitFor(client -> {
                var api = KavoraMod.renderer.getRemixApi();
                return api.getConfigVariableResolved("rtx.upscalerType").equals("1")
                        && api.getConfigVariableResolved("rtx.qualityDLSS").equals("5")
                        && api.getConfigVariableResolved("rtx.enableRayReconstruction").equalsIgnoreCase("true")
                        && api.getConfigVariableResolved("rtx.sparseRendering.enableSparseRendering").equalsIgnoreCase("true")
                        && cloudValue("transportMode") == 0
                        && api.getConfigVariableResolved("rtx.dlfg.enable").equalsIgnoreCase("false")
                        && api.getConfigVariableResolved("rtx.debugView.debugViewIdx").equals("0")
                        && cloudValue("windSpeed") == 0;
            }, 100);
            setCloudsEnabled(context, true);
            setWeather(context, world, "clear");
            double base = context.computeOnClient(client -> cloudValue("worldBaseHeightMeters") + cloudValue("altitudeOffsetMeters"));
            double thickness = context.computeOnClient(client -> cloudValue("thicknessMeters"));
            KavoraWorldGameTest.require(Double.isFinite(base) && Double.isFinite(thickness) && thickness > 0,
                    "Cloud layer height or thickness did not resolve");
            if (Boolean.getBoolean("kavora.gametest.cloudWorldSpace")) {
                checkCameraTranslation(context, world, base - 260);
                if (System.getenv("RTX_DLFG_CAPTURE_PATH") != null) {
                    captureSkyMapFrameGeneration(context, world, base - 260);
                }
                return;
            }
            if (Boolean.getBoolean("kavora.gametest.cloudLightingOnly")) {
                captureLighting(context, world);
                return;
            }
            double belowHeight = base - 240;
            double nearHeight = base - 24;
            double insideHeight = base + 0.2 * thickness;
            double middleHeight = base + 0.4 * thickness;
            double aboveHeight = base + 1.05 * thickness;
            pose(context, world, 0, belowHeight, 0, 0, -30);
            setCloudsEnabled(context, false);
            waitPresentedFrames(context, 20);
            Path clear = KavoraWorldGameTest.captureCompletedFrame(context, "clouds-clear");
            setCloudsEnabled(context, true);
            waitPresentedFrames(context, 40);
            Path below = KavoraWorldGameTest.captureCompletedFrame(context, "clouds-volume-below");
            requireDifference(clear, below);
            context.runOnClient(client -> {
                var state = KavoraMod.renderer.getLightManager().getSkyLightState();
                org.slf4j.LoggerFactory.getLogger(KavoraVolumetricCloudGameTest.class).info(
                        "Cloud fixture: sun direction={}, radiance={}, layer base={}, max thickness={}",
                        java.util.Arrays.toString(state.sunPosition()), java.util.Arrays.toString(state.sunDirectRadiance()),
                        base, thickness);
                var api = KavoraMod.renderer.getRemixApi();
                api.setConfigVariable("rtx.debugView.debugViewIdx", "57");
                api.setConfigVariable("rtx.debugView.maxValue", "100");
                api.setConfigVariable("rtx.debugView.replaceCompositeOutput", "false");
            });
            waitPresentedFrames(context, 8);
            KavoraWorldGameTest.captureCompletedFrame(context, "clouds-light-irradiance");
            context.runOnClient(client -> KavoraMod.renderer.getRemixApi().setConfigVariable("rtx.debugView.debugViewIdx", "0"));
            pose(context, world, 0, nearHeight, 0, 0, -85);
            waitPresentedFrames(context, 16);
            KavoraWorldGameTest.captureCompletedFrame(context, "clouds-volume-near-zenith");
            pose(context, world, 0, insideHeight, 0, 0, 0);
            waitPresentedFrames(context, 16);
            KavoraWorldGameTest.captureCompletedFrame(context, "clouds-volume-inside");
            pose(context, world, 0, middleHeight, 0, 0, 12);
            waitPresentedFrames(context, 16);
            KavoraWorldGameTest.captureCompletedFrame(context, "clouds-volume-middle");
            pose(context, world, 0, aboveHeight, 0, 0, 35);
            waitPresentedFrames(context, 16);
            KavoraWorldGameTest.captureCompletedFrame(context, "clouds-volume-above");
            pose(context, world, 0, 140, -2000, 0, -31.5f);
            waitPresentedFrames(context, 16);
            String originalExposureSpeed = context.computeOnClient(client -> KavoraMod.renderer.getRemixApi()
                    .getConfigVariableResolved("rtx.autoExposure.autoExposureSpeed"));
            context.runOnClient(client -> KavoraMod.renderer.getRemixApi()
                    .setConfigVariable("rtx.autoExposure.autoExposureSpeed", "0"));
            context.waitFor(client -> Double.parseDouble(KavoraMod.renderer.getRemixApi()
                    .getConfigVariableResolved("rtx.autoExposure.autoExposureSpeed")) == 0, 100);
            KavoraWorldGameTest.captureCompletedFrame(context, "clouds-ground");
            int originalLightSteps = context.computeOnClient(client -> (int) cloudValue("lightSteps"));
            context.runOnClient(client -> KavoraMod.renderer.getRemixApi()
                    .setConfigVariable("rtx.clouds.lightSteps", "16"));
            context.waitFor(client -> cloudValue("lightSteps") == 16, 100);
            waitPresentedFrames(context, 16);
            KavoraWorldGameTest.captureCompletedFrame(context, "clouds-ground-light-reference");
            context.runOnClient(client -> KavoraMod.renderer.getRemixApi()
                    .setConfigVariable("rtx.clouds.lightSteps", Integer.toString(originalLightSteps)));
            context.runOnClient(client -> KavoraMod.renderer.getRemixApi()
                    .setConfigVariable("rtx.autoExposure.autoExposureSpeed", originalExposureSpeed));
            for (int[] view : new int[][] {{6000, 2800, -45, -25}, {-7200, -4100, 60, -25}, {6000, 2800, -45, 35}}) {
                double height = view[3] < 0 ? belowHeight : aboveHeight;
                pose(context, world, view[0], height, view[1], view[2], view[3]);
                waitPresentedFrames(context, 16);
                String name = "clouds-offset-" + view[0] + "-" + Math.round(height) + "-" + view[1];
                KavoraWorldGameTest.captureCompletedFrame(context, name);
                captureTransmittance(context, name + "-transmittance");
            }
            if (!Boolean.getBoolean("kavora.gametest.cloudVisualOnly")) {
                for (String weather : new String[] {"clear", "rain", "thunder"}) {
                    setWeather(context, world, weather);
                    captureCelestialView(context, world, nearHeight, weather, "day", 4000, false);
                    captureCelestialView(context, world, nearHeight, weather, "sunset", 11500, false);
                    captureCelestialView(context, world, nearHeight, weather, "moonlight", 18000, true);
                }
            }
            setWeather(context, world, "clear");
            setClock(context, world, 6000);
            pose(context, world, 0, nearHeight, 0, 0, -30);
            captureMotion(context, world);
            setCloudsEnabled(context, false);
            KavoraWorldGameTest.require(context.computeOnClient(client -> KavoraFrameProbe.failures == 0),
                    "Cloud fixture encountered a native frame failure");
        } finally {
            context.runOnClient(client -> {
                var api = KavoraMod.renderer.getRemixApi();
                for (int i = 0; i < controls.length; i++) api.setConfigVariable(controls[i], original[i]);
                api.setConfigVariable("rtx.dlssPreset", originalDlssPreset);
                api.setConfigVariable("rtx.graphicsPreset", originalPreset);
                client.options.cloudStatus().set(originalClouds);
                client.options.renderDistance().set(distance);
                UnfocusedFramerateLimit.set(backgroundLimit);
                KavoraMod.renderer.getCloudRenderer().setProceduralClouds(style);
                if (client.gui.hud.isHidden() != hidden) client.gui.hud.toggle();
            });
        }
    }

    private static void checkCameraTranslation(ClientGameTestContext context, TestSingleplayerContext world, double height) {
        try (var upscaler = RuntimeOptionScope.acquire(context, "rtx.upscalerType", "0");
             var reconstruction = RuntimeOptionScope.acquire(context, "rtx.enableRayReconstruction", "false");
             var coverage = RuntimeOptionScope.acquire(context, "rtx.clouds.coverage", "0.65")) {
            for (int mode : new int[] {1, 0}) {
                try (var transport = RuntimeOptionScope.acquire(context, "rtx.clouds.transportMode", Integer.toString(mode))) {
                    pose(context, world, 0, height, -2000, 0, -25);
                    waitPresentedFrames(context, 12);
                    try (var exposure = RuntimeOptionScope.acquire(context, "rtx.autoExposure.autoExposureSpeed", "0")) {
                        setCloudsEnabled(context, false);
                        waitPresentedFrames(context, 8);
                        Path clear = KavoraWorldGameTest.captureCompletedFrame(context, "cloud-position-" + mode + "-clear");
                        setCloudsEnabled(context, true);
                        waitPresentedFrames(context, 12);
                        Path start = KavoraWorldGameTest.captureCompletedFrame(context, "cloud-position-" + mode + "-start");
                        requireDifference(clear, start);
                        pose(context, world, 256, height, -2000, 0, -25);
                        waitPresentedFrames(context, 12);
                        Path translated = KavoraWorldGameTest.captureCompletedFrame(context, "cloud-position-" + mode + "-translated");
                        pose(context, world, 0, height, -2000, 0, -25);
                        waitPresentedFrames(context, 12);
                        Path returned = KavoraWorldGameTest.captureCompletedFrame(context, "cloud-position-" + mode + "-returned");
                        double moved = skyImageDifference(start, translated);
                        double restored = skyImageDifference(start, returned);
                        KavoraWorldGameTest.require(moved > 10 && moved > 2 * restored,
                                "Cloud field did not follow world-space parallax in mode " + mode + ": " + moved + " vs " + restored);
                        if (mode == 1) checkSkyMapMotionVectors(context);
                    }
                }
            }
        }
    }

    private static void checkSkyMapMotionVectors(ClientGameTestContext context) {
        try (var maximum = RuntimeOptionScope.acquire(context, "rtx.debugView.maxValue", "2");
             var view = RuntimeOptionScope.acquire(context, "rtx.debugView.debugViewIdx", "21")) {
            waitPresentedFrames(context, 8);
            Path stationary = KavoraWorldGameTest.captureCompletedFrame(context, "cloud-motion-stationary");
            double start = context.computeOnClient(client -> client.player.getX());
            context.getInput().holdKey(options -> options.keyRight);
            Path moving;
            try {
                context.waitFor(client -> Math.abs(client.player.getX() - start) > 4, 200);
                moving = KavoraWorldGameTest.captureCompletedFrame(context, "cloud-motion-translated");
            } finally {
                context.getInput().releaseKey(options -> options.keyRight);
            }
            double difference = skyImageDifference(stationary, moving);
            org.slf4j.LoggerFactory.getLogger(KavoraVolumetricCloudGameTest.class)
                    .info("Sky-map motion guide difference during translation: {}", difference);
            KavoraWorldGameTest.require(difference > 4, "Sky-map cloud pixels lost the camera's translation");
        }
    }

    private static void captureSkyMapFrameGeneration(ClientGameTestContext context, TestSingleplayerContext world, double height) {
        try (var transport = RuntimeOptionScope.acquire(context, "rtx.clouds.transportMode", "1");
             var coverage = RuntimeOptionScope.acquire(context, "rtx.clouds.coverage", "0.65");
             var quality = RuntimeOptionScope.acquire(context, "rtx.qualityDLSS", "0");
             var frames = RuntimeOptionScope.acquire(context, "rtx.dlfg.maxInterpolatedFrames", "1")) {
            pose(context, world, 0, height, -2000, 0, -25);
            context.getInput().holdKey(options -> options.keyRight);
            try (var enabled = RuntimeOptionScope.acquire(context, "rtx.dlfg.enable", "true")) {
                waitPresentedFrames(context, 40);
            } finally {
                context.getInput().releaseKey(options -> options.keyRight);
            }
        }
    }

    private static double skyImageDifference(Path first, Path second) {
        try {
            var a = ImageIO.read(first.toFile());
            var b = ImageIO.read(second.toFile());
            double sum = 0;
            int samples = 0;
            for (int y = 0; y < a.getHeight() * 4 / 5; y += 4) {
                for (int x = 0; x < a.getWidth(); x += 4) {
                    sum += Math.abs((a.getRGB(x, y) >>> 16 & 255) - (b.getRGB(x, y) >>> 16 & 255));
                    samples++;
                }
            }
            return sum / samples;
        } catch (IOException e) {
            throw new AssertionError("Cannot read cloud comparison frames", e);
        }
    }

    private static void captureLighting(ClientGameTestContext context, TestSingleplayerContext world) {
        context.runOnClient(client -> {
            var api = KavoraMod.renderer.getRemixApi();
            var sky = KavoraMod.renderer.getLightManager().getSkyLightState();
            org.slf4j.LoggerFactory.getLogger(KavoraVolumetricCloudGameTest.class).info(
                    "Cloud lighting: scattering={}, anisotropy={}, lightSteps={}, sun={}, radiance={}",
                    cloudValue("multipleScattering"), cloudValue("anisotropy"), cloudValue("lightSteps"),
                    java.util.Arrays.toString(sky.sunPosition()), java.util.Arrays.toString(sky.sunDirectRadiance()));
            for (String key : new String[] {"rtx.tonemappingMode", "rtx.useLegacyACES", "rtx.localtonemap.finalizeWithACES",
                    "rtx.localtonemap.exposure", "rtx.autoExposure.enabled"}) {
                org.slf4j.LoggerFactory.getLogger(KavoraVolumetricCloudGameTest.class)
                        .info("Cloud display: {}={}", key, api.getConfigVariableResolved(key));
            }
        });
        for (boolean reverse : new boolean[] {false, true}) {
            String name = reverse ? "clouds-reverse-view" : "clouds-sunward-view";
            pose(context, world, 0, 140, reverse ? 6000 : -2000, reverse ? 180 : 0, -31.5f);
            waitPresentedFrames(context, 16);
            KavoraWorldGameTest.captureCompletedFrame(context, name);
            if (Boolean.getBoolean("kavora.gametest.cloudScatteringComparison")) {
                String strength = context.computeOnClient(client -> KavoraMod.renderer.getRemixApi()
                        .getConfigVariableResolved("rtx.clouds.multipleScattering"));
                String exposureSpeed = context.computeOnClient(client -> KavoraMod.renderer.getRemixApi()
                        .getConfigVariableResolved("rtx.autoExposure.autoExposureSpeed"));
                context.runOnClient(client -> {
                    var api = KavoraMod.renderer.getRemixApi();
                    api.setConfigVariable("rtx.autoExposure.autoExposureSpeed", "0");
                    api.setConfigVariable("rtx.clouds.multipleScattering", "0.8");
                });
                context.waitFor(client -> Math.abs(cloudValue("multipleScattering") - 0.8) < 0.001, 100);
                waitPresentedFrames(context, 16);
                KavoraWorldGameTest.captureCompletedFrame(context, name + "-diffuse");
                context.runOnClient(client -> {
                    var api = KavoraMod.renderer.getRemixApi();
                    api.setConfigVariable("rtx.clouds.multipleScattering", strength);
                    api.setConfigVariable("rtx.autoExposure.autoExposureSpeed", exposureSpeed);
                });
            }
            if (!Boolean.getBoolean("kavora.gametest.cloudDumpLinear")) continue;
            context.runOnClient(client -> {
                var api = KavoraMod.renderer.getRemixApi();
                api.setConfigVariable("rtx.captureDebugImage", "true");
            });
            context.waitFor(client -> KavoraMod.renderer.getRemixApi()
                    .getConfigVariableResolved("rtx.captureDebugImage").equalsIgnoreCase("true"), 100);
            long captureFrame = context.computeOnClient(client -> {
                KavoraMod.renderer.getRemixApi().setConfigVariable("rtx.captureNextFrame", "true");
                return KavoraFrameProbe.presents;
            });
            context.waitFor(client -> KavoraFrameProbe.presents >= captureFrame + 2
                    && KavoraMod.renderer.getRemixApi().getConfigVariableResolved("rtx.captureNextFrame")
                            .equalsIgnoreCase("false"), 100);
            context.runOnClient(client -> KavoraMod.renderer.getRemixApi()
                    .setConfigVariable("rtx.captureDebugImage", "false"));
        }
    }

    private static void captureTransmittance(ClientGameTestContext context, String name) {
        context.runOnClient(client -> {
            var api = KavoraMod.renderer.getRemixApi();
            api.setConfigVariable("rtx.debugView.debugViewIdx", "54");
            api.setConfigVariable("rtx.debugView.maxValue", "1");
            api.setConfigVariable("rtx.debugView.replaceCompositeOutput", "false");
        });
        context.waitFor(client -> {
            var api = KavoraMod.renderer.getRemixApi();
            return api.getConfigVariableResolved("rtx.debugView.debugViewIdx").equals("54")
                    && Double.parseDouble(api.getConfigVariableResolved("rtx.debugView.maxValue")) == 1
                    && api.getConfigVariableResolved("rtx.debugView.replaceCompositeOutput").equalsIgnoreCase("false");
        }, 100);
        waitPresentedFrames(context, 8);
        KavoraWorldGameTest.captureCompletedFrame(context, name);
        context.runOnClient(client -> KavoraMod.renderer.getRemixApi().setConfigVariable("rtx.debugView.debugViewIdx", "0"));
    }

    private static void setCloudsEnabled(ClientGameTestContext context, boolean enabled) {
        context.runOnClient(client -> client.options.cloudStatus().set(enabled ? CloudStatus.FANCY : CloudStatus.OFF));
        context.waitFor(client -> KavoraMod.renderer.getRemixApi().getConfigVariableResolved("rtx.clouds.sceneEnabled")
                .equalsIgnoreCase(Boolean.toString(enabled)), 100);
    }

    private static double cloudValue(String key) {
        return Double.parseDouble(KavoraMod.renderer.getRemixApi().getConfigVariableResolved("rtx.clouds." + key));
    }

    private static void setWeather(ClientGameTestContext context, TestSingleplayerContext world, String weather) {
        world.getServer().runOnServer(server -> KavoraSceneFixtures.commands(server, "weather " + weather));
        double rain = weather.equals("clear") ? 0 : 1;
        double thunder = weather.equals("thunder") ? 1 : 0;
        context.waitFor(client -> Math.abs(cloudValue("weather") - rain) < 0.01
                && Math.abs(cloudValue("thunder") - thunder) < 0.01, 300);
    }

    private static void setClock(ClientGameTestContext context, TestSingleplayerContext world, long ticks) {
        world.getServer().runOnServer(server -> server.clockManager().setTotalTicks(server.registryAccess()
                .get(net.minecraft.world.clock.WorldClocks.OVERWORLD).orElseThrow(), ticks));
        context.waitFor(client -> client.level.getOverworldClockTime() == ticks, 200);
    }

    private static void pose(ClientGameTestContext context, TestSingleplayerContext world,
                             double x, double y, double z, float yaw, float pitch) {
        world.getServer().runOnServer(server -> {
            if (!server.getPlayerList().getPlayers().getFirst().teleportTo(server.overworld(), x, y, z,
                    Set.of(), yaw, pitch, true)) throw new AssertionError("Cloud camera teleport failed");
        });
        context.waitFor(client -> client.player.position().distanceToSqr(x, y, z) < 0.01
                && Math.abs(Mth.wrapDegrees(client.player.getYRot() - yaw)) < 0.1
                && Math.abs(client.player.getXRot() - pitch) < 0.1, 200);
    }

    private static void captureCelestialView(ClientGameTestContext context, TestSingleplayerContext world,
                                            double height, String weather, String phase, long ticks, boolean moon) {
        setClock(context, world, ticks);
        waitPresentedFrames(context, 8);
        float[] direction = context.computeOnClient(client -> {
            var sky = KavoraMod.renderer.getLightManager().getSkyLightState();
            return moon ? sky.moonPosition() : sky.sunPosition();
        });
        float yaw = (float) Math.toDegrees(Math.atan2(-direction[0], direction[2]));
        float pitch = (float) -Math.toDegrees(Math.atan2(direction[1], Math.hypot(direction[0], direction[2])));
        pose(context, world, 0, height, 0, yaw, pitch);
        waitPresentedFrames(context, 16);
        KavoraWorldGameTest.captureCompletedFrame(context, "clouds-" + weather + "-" + phase);
    }

    private static void captureMotion(ClientGameTestContext context, TestSingleplayerContext world) {
        context.runOnClient(client -> KavoraMod.renderer.getRemixApi().setConfigVariable("rtx.clouds.windSpeed", "1"));
        context.waitFor(client -> cloudValue("windSpeed") == 1, 100);
        float initialYaw = context.computeOnClient(client -> client.player.getYRot());
        try {
            long start = context.computeOnClient(client -> {
                long first = KavoraFrameProbe.presents;
                KavoraFrameProbe.frameAction = frame -> {
                    float yaw = initialYaw + (frame - first) * 0.25f;
                    client.player.setYRot(yaw);
                    client.player.yRotO = yaw;
                };
                return first;
            });
            world.getServer().runOnServer(server -> server.getGameRules().set(GameRules.ADVANCE_TIME, true, server));
            for (int frame : new int[] {8, 16, 24}) {
                long previousClock = context.computeOnClient(client -> client.level.getOverworldClockTime());
                double previousCloudTime = context.computeOnClient(client -> cloudValue("worldTimeSeconds"));
                context.waitFor(client -> KavoraFrameProbe.presents >= start + frame
                        && client.level.getOverworldClockTime() > previousClock
                        && cloudValue("worldTimeSeconds") > previousCloudTime, 400);
                KavoraWorldGameTest.captureCompletedFrame(context, "clouds-motion-" + frame);
            }
        } finally {
            context.runOnClient(client -> {
                KavoraFrameProbe.frameAction = null;
                client.player.setYRot(initialYaw);
                client.player.yRotO = initialYaw;
            });
            world.getServer().runOnServer(server -> server.getGameRules().set(GameRules.ADVANCE_TIME, false, server));
        }
    }

    private static void waitPresentedFrames(ClientGameTestContext context, int count) {
        long start = context.computeOnClient(client -> KavoraFrameProbe.presents);
        context.waitFor(client -> KavoraFrameProbe.presents >= start + count, 400);
    }

    private static void requireDifference(Path clear, Path clouds) {
        try {
            var a = ImageIO.read(clear.toFile());
            var b = ImageIO.read(clouds.toFile());
            long changed = 0;
            for (int y = 0; y < a.getHeight(); y++) {
                for (int x = 0; x < a.getWidth(); x++) {
                    int av = a.getRGB(x, y);
                    int bv = b.getRGB(x, y);
                    int difference = Math.abs((av & 255) - (bv & 255))
                            + Math.abs((av >>> 8 & 255) - (bv >>> 8 & 255))
                            + Math.abs((av >>> 16 & 255) - (bv >>> 16 & 255));
                    if (difference > 30) changed++;
                }
            }
            if (changed < (long) a.getWidth() * a.getHeight() / 100) {
                throw new AssertionError("No visible clouds beyond the two-chunk terrain distance");
            }
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
