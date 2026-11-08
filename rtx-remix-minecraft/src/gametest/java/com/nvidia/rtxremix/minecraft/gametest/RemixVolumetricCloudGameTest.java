/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gametest;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.UnfocusedFramerateLimit;
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
public final class RemixVolumetricCloudGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        if (!Boolean.getBoolean("rtxremix.gametest.cloudsOnly")) return;
        context.getInput().resizeWindow(1920, 1080);
        CloudStatus originalClouds = context.computeOnClient(client -> client.options.cloudStatus().get());
        boolean hidden = context.computeOnClient(client -> client.gui.hud.isHidden());
        int distance = context.computeOnClient(client -> client.options.renderDistance().get());
        int backgroundLimit = context.computeOnClient(client -> UnfocusedFramerateLimit.get());
        boolean style = context.computeOnClient(client -> RtxRemixMod.renderer.getCloudRenderer().isProceduralClouds());
        String originalPreset = context.computeOnClient(client -> RtxRemixMod.renderer.getRemixApi()
                .getConfigVariableResolved("rtx.graphicsPreset"));
        String originalDlssPreset = context.computeOnClient(client -> RtxRemixMod.renderer.getRemixApi()
                .getConfigVariableResolved("rtx.dlssPreset"));
        String[] controls = {"rtx.upscalerType", "rtx.qualityDLSS", "rtx.enableRayReconstruction", "rtx.sparseRendering.enableSparseRendering",
                "rtx.clouds.transportMode",
                "rtx.dlfg.enable", "rtx.clouds.windSpeed", "rtx.clouds.lightSteps", "rtx.clouds.multipleScattering", "rtx.autoExposure.autoExposureSpeed",
                "rtx.captureDebugImage", "rtx.captureNextFrame",
                "rtx.debugView.debugViewIdx", "rtx.debugView.maxValue", "rtx.debugView.replaceCompositeOutput"};
        String[] original = context.computeOnClient(client -> java.util.Arrays.stream(controls)
                .map(key -> RtxRemixMod.renderer.getRemixApi().getConfigVariableResolved(key)).toArray(String[]::new));
        context.runOnClient(client -> {
            client.options.renderDistance().set(2);
            UnfocusedFramerateLimit.set(UnfocusedFramerateLimit.UNLIMITED);
        });
        try (var world = context.worldBuilder().create()) {
            RemixWorldGameTest.assertWorldRenders(context, world, "cloud-fixture");
            world.getServer().runOnServer(server -> {
                server.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
                var player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.CREATIVE);
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
            });
            setClock(context, world, 6000);
            context.runOnClient(client -> {
                var api = RtxRemixMod.renderer.getRemixApi();
                api.setConfigVariable("rtx.graphicsPreset", "4");
                api.setConfigVariable("rtx.dlssPreset", "2");
            });
            context.waitFor(client -> RtxRemixMod.renderer.getRemixApi()
                    .getConfigVariableResolved("rtx.graphicsPreset").equals("4"), 100);
            context.runOnClient(client -> {
                var api = RtxRemixMod.renderer.getRemixApi();
                api.setConfigVariable("rtx.upscalerType", "1");
                api.setConfigVariable("rtx.qualityDLSS", "5");
                api.setConfigVariable("rtx.enableRayReconstruction", "true");
                api.setConfigVariable("rtx.sparseRendering.enableSparseRendering", "true");
                api.setConfigVariable("rtx.clouds.transportMode", "0");
                api.setConfigVariable("rtx.dlfg.enable", "false");
                api.setConfigVariable("rtx.clouds.windSpeed", "0");
                api.setConfigVariable("rtx.debugView.debugViewIdx", "0");
                if (!client.gui.hud.isHidden()) client.gui.hud.toggle();
                RtxRemixMod.renderer.getCloudRenderer().setProceduralClouds(true);
            });
            context.waitFor(client -> {
                var api = RtxRemixMod.renderer.getRemixApi();
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
            RemixWorldGameTest.require(Double.isFinite(base) && Double.isFinite(thickness) && thickness > 0,
                    "Cloud layer height or thickness did not resolve");
            if (Boolean.getBoolean("rtxremix.gametest.cloudLightingOnly")) {
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
            Path clear = RemixWorldGameTest.captureCompletedFrame(context, "clouds-clear");
            setCloudsEnabled(context, true);
            waitPresentedFrames(context, 40);
            Path below = RemixWorldGameTest.captureCompletedFrame(context, "clouds-volume-below");
            requireDifference(clear, below);
            context.runOnClient(client -> {
                var state = RtxRemixMod.renderer.getLightManager().getSkyLightState();
                org.slf4j.LoggerFactory.getLogger(RemixVolumetricCloudGameTest.class).info(
                        "Cloud fixture: sun direction={}, radiance={}, layer base={}, max thickness={}",
                        java.util.Arrays.toString(state.sunPosition()), java.util.Arrays.toString(state.sunDirectRadiance()),
                        base, thickness);
                var api = RtxRemixMod.renderer.getRemixApi();
                api.setConfigVariable("rtx.debugView.debugViewIdx", "57");
                api.setConfigVariable("rtx.debugView.maxValue", "100");
                api.setConfigVariable("rtx.debugView.replaceCompositeOutput", "false");
            });
            waitPresentedFrames(context, 8);
            RemixWorldGameTest.captureCompletedFrame(context, "clouds-light-irradiance");
            context.runOnClient(client -> RtxRemixMod.renderer.getRemixApi().setConfigVariable("rtx.debugView.debugViewIdx", "0"));
            pose(context, world, 0, nearHeight, 0, 0, -85);
            waitPresentedFrames(context, 16);
            RemixWorldGameTest.captureCompletedFrame(context, "clouds-volume-near-zenith");
            pose(context, world, 0, insideHeight, 0, 0, 0);
            waitPresentedFrames(context, 16);
            RemixWorldGameTest.captureCompletedFrame(context, "clouds-volume-inside");
            pose(context, world, 0, middleHeight, 0, 0, 12);
            waitPresentedFrames(context, 16);
            RemixWorldGameTest.captureCompletedFrame(context, "clouds-volume-middle");
            pose(context, world, 0, aboveHeight, 0, 0, 35);
            waitPresentedFrames(context, 16);
            RemixWorldGameTest.captureCompletedFrame(context, "clouds-volume-above");
            pose(context, world, 0, 140, -2000, 0, -31.5f);
            waitPresentedFrames(context, 16);
            String originalExposureSpeed = context.computeOnClient(client -> RtxRemixMod.renderer.getRemixApi()
                    .getConfigVariableResolved("rtx.autoExposure.autoExposureSpeed"));
            context.runOnClient(client -> RtxRemixMod.renderer.getRemixApi()
                    .setConfigVariable("rtx.autoExposure.autoExposureSpeed", "0"));
            context.waitFor(client -> Double.parseDouble(RtxRemixMod.renderer.getRemixApi()
                    .getConfigVariableResolved("rtx.autoExposure.autoExposureSpeed")) == 0, 100);
            RemixWorldGameTest.captureCompletedFrame(context, "clouds-ground");
            int originalLightSteps = context.computeOnClient(client -> (int) cloudValue("lightSteps"));
            context.runOnClient(client -> RtxRemixMod.renderer.getRemixApi()
                    .setConfigVariable("rtx.clouds.lightSteps", "16"));
            context.waitFor(client -> cloudValue("lightSteps") == 16, 100);
            waitPresentedFrames(context, 16);
            RemixWorldGameTest.captureCompletedFrame(context, "clouds-ground-light-reference");
            context.runOnClient(client -> RtxRemixMod.renderer.getRemixApi()
                    .setConfigVariable("rtx.clouds.lightSteps", Integer.toString(originalLightSteps)));
            context.runOnClient(client -> RtxRemixMod.renderer.getRemixApi()
                    .setConfigVariable("rtx.autoExposure.autoExposureSpeed", originalExposureSpeed));
            for (int[] view : new int[][] {{6000, 2800, -45, -25}, {-7200, -4100, 60, -25}, {6000, 2800, -45, 35}}) {
                double height = view[3] < 0 ? belowHeight : aboveHeight;
                pose(context, world, view[0], height, view[1], view[2], view[3]);
                waitPresentedFrames(context, 16);
                String name = "clouds-offset-" + view[0] + "-" + Math.round(height) + "-" + view[1];
                RemixWorldGameTest.captureCompletedFrame(context, name);
                captureTransmittance(context, name + "-transmittance");
            }
            if (!Boolean.getBoolean("rtxremix.gametest.cloudVisualOnly")) {
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
            RemixWorldGameTest.require(context.computeOnClient(client -> RemixFrameProbe.failures == 0),
                    "Cloud fixture encountered a native frame failure");
        } finally {
            context.runOnClient(client -> {
                var api = RtxRemixMod.renderer.getRemixApi();
                for (int i = 0; i < controls.length; i++) api.setConfigVariable(controls[i], original[i]);
                api.setConfigVariable("rtx.dlssPreset", originalDlssPreset);
                api.setConfigVariable("rtx.graphicsPreset", originalPreset);
                client.options.cloudStatus().set(originalClouds);
                client.options.renderDistance().set(distance);
                UnfocusedFramerateLimit.set(backgroundLimit);
                RtxRemixMod.renderer.getCloudRenderer().setProceduralClouds(style);
                if (client.gui.hud.isHidden() != hidden) client.gui.hud.toggle();
            });
        }
    }

    private static void captureLighting(ClientGameTestContext context, TestSingleplayerContext world) {
        context.runOnClient(client -> {
            var api = RtxRemixMod.renderer.getRemixApi();
            var sky = RtxRemixMod.renderer.getLightManager().getSkyLightState();
            org.slf4j.LoggerFactory.getLogger(RemixVolumetricCloudGameTest.class).info(
                    "Cloud lighting: scattering={}, anisotropy={}, lightSteps={}, sun={}, radiance={}",
                    cloudValue("multipleScattering"), cloudValue("anisotropy"), cloudValue("lightSteps"),
                    java.util.Arrays.toString(sky.sunPosition()), java.util.Arrays.toString(sky.sunDirectRadiance()));
            for (String key : new String[] {"rtx.tonemappingMode", "rtx.useLegacyACES", "rtx.localtonemap.finalizeWithACES",
                    "rtx.localtonemap.exposure", "rtx.autoExposure.enabled"}) {
                org.slf4j.LoggerFactory.getLogger(RemixVolumetricCloudGameTest.class)
                        .info("Cloud display: {}={}", key, api.getConfigVariableResolved(key));
            }
        });
        for (boolean reverse : new boolean[] {false, true}) {
            String name = reverse ? "clouds-reverse-view" : "clouds-sunward-view";
            pose(context, world, 0, 140, reverse ? 6000 : -2000, reverse ? 180 : 0, -31.5f);
            waitPresentedFrames(context, 16);
            RemixWorldGameTest.captureCompletedFrame(context, name);
            if (Boolean.getBoolean("rtxremix.gametest.cloudScatteringComparison")) {
                String strength = context.computeOnClient(client -> RtxRemixMod.renderer.getRemixApi()
                        .getConfigVariableResolved("rtx.clouds.multipleScattering"));
                String exposureSpeed = context.computeOnClient(client -> RtxRemixMod.renderer.getRemixApi()
                        .getConfigVariableResolved("rtx.autoExposure.autoExposureSpeed"));
                context.runOnClient(client -> {
                    var api = RtxRemixMod.renderer.getRemixApi();
                    api.setConfigVariable("rtx.autoExposure.autoExposureSpeed", "0");
                    api.setConfigVariable("rtx.clouds.multipleScattering", "0.8");
                });
                context.waitFor(client -> Math.abs(cloudValue("multipleScattering") - 0.8) < 0.001, 100);
                waitPresentedFrames(context, 16);
                RemixWorldGameTest.captureCompletedFrame(context, name + "-diffuse");
                context.runOnClient(client -> {
                    var api = RtxRemixMod.renderer.getRemixApi();
                    api.setConfigVariable("rtx.clouds.multipleScattering", strength);
                    api.setConfigVariable("rtx.autoExposure.autoExposureSpeed", exposureSpeed);
                });
            }
            if (!Boolean.getBoolean("rtxremix.gametest.cloudDumpLinear")) continue;
            context.runOnClient(client -> {
                var api = RtxRemixMod.renderer.getRemixApi();
                api.setConfigVariable("rtx.captureDebugImage", "true");
            });
            context.waitFor(client -> RtxRemixMod.renderer.getRemixApi()
                    .getConfigVariableResolved("rtx.captureDebugImage").equalsIgnoreCase("true"), 100);
            long captureFrame = context.computeOnClient(client -> {
                RtxRemixMod.renderer.getRemixApi().setConfigVariable("rtx.captureNextFrame", "true");
                return RemixFrameProbe.presents;
            });
            context.waitFor(client -> RemixFrameProbe.presents >= captureFrame + 2
                    && RtxRemixMod.renderer.getRemixApi().getConfigVariableResolved("rtx.captureNextFrame")
                            .equalsIgnoreCase("false"), 100);
            context.runOnClient(client -> RtxRemixMod.renderer.getRemixApi()
                    .setConfigVariable("rtx.captureDebugImage", "false"));
        }
    }

    private static void captureTransmittance(ClientGameTestContext context, String name) {
        context.runOnClient(client -> {
            var api = RtxRemixMod.renderer.getRemixApi();
            api.setConfigVariable("rtx.debugView.debugViewIdx", "54");
            api.setConfigVariable("rtx.debugView.maxValue", "1");
            api.setConfigVariable("rtx.debugView.replaceCompositeOutput", "false");
        });
        context.waitFor(client -> {
            var api = RtxRemixMod.renderer.getRemixApi();
            return api.getConfigVariableResolved("rtx.debugView.debugViewIdx").equals("54")
                    && Double.parseDouble(api.getConfigVariableResolved("rtx.debugView.maxValue")) == 1
                    && api.getConfigVariableResolved("rtx.debugView.replaceCompositeOutput").equalsIgnoreCase("false");
        }, 100);
        waitPresentedFrames(context, 8);
        RemixWorldGameTest.captureCompletedFrame(context, name);
        context.runOnClient(client -> RtxRemixMod.renderer.getRemixApi().setConfigVariable("rtx.debugView.debugViewIdx", "0"));
    }

    private static void setCloudsEnabled(ClientGameTestContext context, boolean enabled) {
        context.runOnClient(client -> client.options.cloudStatus().set(enabled ? CloudStatus.FANCY : CloudStatus.OFF));
        context.waitFor(client -> RtxRemixMod.renderer.getRemixApi().getConfigVariableResolved("rtx.clouds.sceneEnabled")
                .equalsIgnoreCase(Boolean.toString(enabled)), 100);
    }

    private static double cloudValue(String key) {
        return Double.parseDouble(RtxRemixMod.renderer.getRemixApi().getConfigVariableResolved("rtx.clouds." + key));
    }

    private static void setWeather(ClientGameTestContext context, TestSingleplayerContext world, String weather) {
        world.getServer().runOnServer(server -> RemixSceneFixtures.commands(server, "weather " + weather));
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
            var sky = RtxRemixMod.renderer.getLightManager().getSkyLightState();
            return moon ? sky.moonPosition() : sky.sunPosition();
        });
        float yaw = (float) Math.toDegrees(Math.atan2(-direction[0], direction[2]));
        float pitch = (float) -Math.toDegrees(Math.atan2(direction[1], Math.hypot(direction[0], direction[2])));
        pose(context, world, 0, height, 0, yaw, pitch);
        waitPresentedFrames(context, 16);
        RemixWorldGameTest.captureCompletedFrame(context, "clouds-" + weather + "-" + phase);
    }

    private static void captureMotion(ClientGameTestContext context, TestSingleplayerContext world) {
        context.runOnClient(client -> RtxRemixMod.renderer.getRemixApi().setConfigVariable("rtx.clouds.windSpeed", "1"));
        context.waitFor(client -> cloudValue("windSpeed") == 1, 100);
        float initialYaw = context.computeOnClient(client -> client.player.getYRot());
        try {
            long start = context.computeOnClient(client -> {
                long first = RemixFrameProbe.presents;
                RemixFrameProbe.frameAction = frame -> {
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
                context.waitFor(client -> RemixFrameProbe.presents >= start + frame
                        && client.level.getOverworldClockTime() > previousClock
                        && cloudValue("worldTimeSeconds") > previousCloudTime, 400);
                RemixWorldGameTest.captureCompletedFrame(context, "clouds-motion-" + frame);
            }
        } finally {
            context.runOnClient(client -> {
                RemixFrameProbe.frameAction = null;
                client.player.setYRot(initialYaw);
                client.player.yRotO = initialYaw;
            });
            world.getServer().runOnServer(server -> server.getGameRules().set(GameRules.ADVANCE_TIME, false, server));
        }
    }

    private static void waitPresentedFrames(ClientGameTestContext context, int count) {
        long start = context.computeOnClient(client -> RemixFrameProbe.presents);
        context.waitFor(client -> RemixFrameProbe.presents >= start + count, 400);
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
