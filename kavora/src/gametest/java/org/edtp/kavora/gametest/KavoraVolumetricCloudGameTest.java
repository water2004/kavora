/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.gametest;

import org.edtp.kavora.KavoraMod;
import org.edtp.kavora.KavoraPreparationScreen;
import org.edtp.kavora.UnfocusedFramerateLimit;
import org.edtp.kavora.logic.WeatherVisibility;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;

/** Exercises the same cloud density field below, within and above its world-space volume. */
public final class KavoraVolumetricCloudGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        if (!Boolean.getBoolean("kavora.gametest.cloudsOnly")) return;
        context.getInput().resizeWindow(1920, 1080);
        String profileStartFile = System.getProperty("kavora.gametest.cloudProfileStartFile");
        if (profileStartFile != null) {
            profileOceanClouds(context, Path.of(profileStartFile));
            return;
        }
        if (Boolean.getBoolean("kavora.gametest.sunsetRecovery")) {
            verifySunsetRecovery(context);
            return;
        }
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
            if (Boolean.getBoolean("kavora.gametest.overcastOnly")) {
                captureOvercast(context, world);
                return;
            }
            if (Boolean.getBoolean("kavora.gametest.highAltitudeSun")) {
                context.runOnClient(client -> {
                    if (!client.gui.hud.isHidden()) client.gui.hud.toggle();
                });
                setCloudsEnabled(context, true);
                setWeather(context, world, "clear");
                Path image = captureCelestialView(context, world, 7000, "clear", "high-altitude-sun", 6000, false);
                Path lowSun = captureCelestialView(context, world, 7000, "clear", "low-sun", 12750, false);
                requireClearSolarView(image);
                requireWarmerLowSun(image, lowSun);
                return;
            }
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
                    captureCloudFrameGeneration(context, world, base - 260);
                }
                return;
            }
            if (Boolean.getBoolean("kavora.gametest.cloudLightingOnly")) {
                captureLighting(context, world);
                captureCelestialView(context, world, 140, "clear", "sunset", 11500, false);
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

    private static void verifySunsetRecovery(ClientGameTestContext context) {
        context.waitFor(client -> KavoraMod.renderer != null && KavoraMod.renderer.isInitialized(), 2400);
        context.runOnClient(client -> {
            client.options.renderDistance().set(12);
            client.options.pauseOnLostFocus = false;
            UnfocusedFramerateLimit.set(UnfocusedFramerateLimit.UNLIMITED);
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
            api.setConfigVariable("rtx.dlfg.enable", "false");
            api.setConfigVariable("rtx.display.hdrMode", "0");
            api.setConfigVariable("rtx.gbuffer.logDispatch", "true");
            api.setConfigVariable("rtx.clouds.windSpeed", "0");
            KavoraMod.renderer.getCloudRenderer().setProceduralClouds(true);
            client.options.cloudStatus().set(CloudStatus.FANCY);
        });
        boolean captureComparison = Boolean.getBoolean("kavora.gametest.captureCloudComparison");
        if (captureComparison) {
            RuntimeOptionScope.apply(context, Map.of(
                    "rtx.clouds.maxDistanceMeters", "24000", "rtx.clouds.highMaxDistanceMeters", "24000",
                    "rtx.clouds.coverage", "0.52"));
        }
        String[] fixtures = System.getProperty("kavora.gametest.sunsetFixtures", "sunset-flat,sunset-ocean").split(",");
        for (String fixture : fixtures) {
            MinecraftServer server = openCloudFixture(context, fixture, captureComparison);
            try {
                context.runOnClient(client -> {
                    client.gui.setScreen(null);
                    if (!client.gui.hud.isHidden()) client.gui.hud.toggle();
                });
                var pose = CompletableFuture.runAsync(() -> {
                    server.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
                    KavoraSceneFixtures.commands(server, "weather clear");
                    positionOceanCamera(server);
                }, server);
                context.waitFor(client -> pose.isDone(), 200);
                pose.join();
                waitForOceanCamera(context);
                setCloudsEnabled(context, true);
                System.out.println("Sunset fixture ready: " + fixture + " (1920x1080 DLAA, Ultra, no FG)");
                if (captureComparison && fixture.equals("sunset-ocean")) {
                    captureOceanClouds(context, server, 6000, -8, "cloud-ocean-noon");
                    captureOceanClouds(context, server, 12750, -8, "cloud-ocean-sunset-1845");
                    var sunrisePose = CompletableFuture.runAsync(() -> positionOceanCamera(server, -90), server);
                    context.waitFor(client -> sunrisePose.isDone(), 200);
                    sunrisePose.join();
                    waitForOceanCamera(context, -90);
                    captureOceanClouds(context, server, 23250, 8, "cloud-ocean-sunrise-0515");
                    KavoraWorldGameTest.require(context.computeOnClient(client -> KavoraFrameProbe.failures == 0),
                            "Ocean cloud fixture encountered a native frame failure");
                    continue;
                }
                int ticks = fixture.equals("sunset-ocean") ? 12250 : 12750;
                setFixtureTime(context, server, ticks);
                long first = context.computeOnClient(client -> KavoraFrameProbe.raytracedPresents);
                for (int count = 24; count <= 96; count += 24) {
                    long target = first + count;
                    context.waitFor(client -> KavoraFrameProbe.raytracedPresents >= target
                            && KavoraMod.renderer.isSceneReady(), 2400);
                    System.out.println("Sunset stability: " + fixture + " time=" + ticks + " " + count + " ray-traced presentations");
                }
            } finally {
                closeCloudFixture(context, server);
            }
        }
    }

    private static MinecraftServer openCloudFixture(ClientGameTestContext context, String fixture, boolean allowColdCompile) {
        context.runOnClient(client -> client.createWorldOpenFlows().openWorld(fixture, () -> {
            throw new AssertionError("World open cancelled: " + fixture);
        }));
        context.waitFor(client -> {
            if (client.level == null || client.player == null || client.getSingleplayerServer() == null) {
                return false;
            }
            // Only the fixture bypasses the interactive timeout during cold pipeline compilation.
            if (allowColdCompile && client.gui.screen() instanceof KavoraPreparationScreen) {
                client.gui.setScreen(null);
            }
            return KavoraMod.renderer.isSceneReady();
        }, allowColdCompile ? 12000 : 2400);
        return context.computeOnClient(client -> client.getSingleplayerServer());
    }

    private static void closeCloudFixture(ClientGameTestContext context, MinecraftServer server) {
        context.runOnClient(client -> {
            var saving = Component.translatable("menu.savingLevel");
            client.level.disconnect(saving);
            client.disconnect(new GenericMessageScreen(saving), false);
        });
        context.waitFor(client -> !server.getRunningThread().isAlive(), 1200);
        context.setScreen(TitleScreen::new);
    }

    private static void positionOceanCamera(MinecraftServer server) {
        positionOceanCamera(server, 90);
    }

    private static void positionOceanCamera(MinecraftServer server, float yaw) {
        var player = server.getPlayerList().getPlayers().getFirst();
        player.setGameMode(GameType.CREATIVE);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        KavoraWorldGameTest.require(player.teleportTo(server.overworld(), .5, 75, .5, Set.of(), yaw, 0, true),
                "Cloud fixture camera teleport failed");
    }

    private static void waitForOceanCamera(ClientGameTestContext context) {
        waitForOceanCamera(context, 90);
    }

    private static void waitForOceanCamera(ClientGameTestContext context, float yaw) {
        context.waitFor(client -> client.player.position().distanceToSqr(.5, 75, .5) < .01
                && Math.abs(Mth.wrapDegrees(client.player.getYRot() - yaw)) < .1
                && Math.abs(client.player.getXRot()) < .1, 200);
    }

    private static void setFixtureTime(ClientGameTestContext context, MinecraftServer server, int ticks) {
        var clock = CompletableFuture.runAsync(() -> server.clockManager().setTotalTicks(server.registryAccess()
                .get(net.minecraft.world.clock.WorldClocks.OVERWORLD).orElseThrow(), ticks), server);
        context.waitFor(client -> clock.isDone() && client.level.getOverworldClockTime() == ticks, 200);
        clock.join();
    }

    private static void waitForOceanTerrain(ClientGameTestContext context, int minimumColumns) {
        context.waitFor(client -> {
            var chunks = KavoraMod.renderer.getChunkManager();
            var terrain = chunks.getTerrainDiagnostics();
            return terrain.columnsPublished() >= minimumColumns && terrain.columnsWithGeometry() > 0
                    && terrain.columnsMissingGeometry() == 0;
        }, 2400);
    }

    private static void waitForRaytracedPresents(ClientGameTestContext context, int frames) {
        long target = context.computeOnClient(client -> KavoraFrameProbe.raytracedPresents) + frames;
        context.waitFor(client -> KavoraFrameProbe.raytracedPresents >= target && KavoraMod.renderer.isSceneReady(), 2400);
    }

    private static void captureOceanClouds(ClientGameTestContext context, MinecraftServer server,
                                           int ticks, int viewChunkX, String label) {
        setFixtureTime(context, server, ticks);
        waitForOceanTerrain(context, 502);
        context.waitFor(client -> {
            var chunks = KavoraMod.renderer.getChunkManager();
            for (int z = -4; z <= 4; z++) {
                if (!chunks.hasTerrainGeometry(viewChunkX, z)) return false;
            }
            return true;
        }, 2400);
        long begin = System.nanoTime();
        waitForRaytracedPresents(context, 24);
        System.out.println("Cloud ocean stability: tick=" + ticks + " 24 presentations in "
                + (System.nanoTime() - begin) / 1_000_000 + " ms");
        Path screenshot = KavoraWorldGameTest.captureCompletedFrame(context, label);
        KavoraWorldGameTest.requireVisibleWorld(screenshot);
        System.out.println("Cloud ocean screenshot: tick=" + ticks + " " + screenshot);
    }

    private static void profileOceanClouds(ClientGameTestContext context, Path startFile) {
        context.waitFor(client -> KavoraMod.renderer != null && KavoraMod.renderer.isInitialized(), 2400);
        boolean fullDay = Boolean.getBoolean("kavora.gametest.cloudFullDayProfile");
        if (fullDay) {
            context.runOnClient(client -> {
                client.options.renderDistance().set(16);
                client.options.pauseOnLostFocus = false;
                UnfocusedFramerateLimit.set(UnfocusedFramerateLimit.UNLIMITED);
                if (!client.gui.hud.isHidden()) client.gui.hud.toggle();
            });
        }
        MinecraftServer server = openCloudFixture(context, "sunset-ocean", true);
        try {
            if (fullDay) {
                context.runOnClient(client -> {
                    var api = KavoraMod.renderer.getRemixApi();
                    api.setConfigVariable("rtx.graphicsPreset", "4");
                    api.setConfigVariable("rtx.dlssPreset", "2");
                });
                context.waitFor(client -> KavoraMod.renderer.getRemixApi()
                        .getConfigVariableResolved("rtx.graphicsPreset").equals("4"), 100);
                RuntimeOptionScope.apply(context, Map.of("rtx.upscalerType", "1", "rtx.qualityDLSS", "5",
                        "rtx.enableRayReconstruction", "true", "rtx.dlfg.enable", "false"));
            }
            // The copied screenshot fixture left simulation time paused. Restore the ordinary game rules.
            var pose = CompletableFuture.runAsync(() -> {
                server.getGameRules().set(GameRules.ADVANCE_TIME, true, server);
                server.getGameRules().set(GameRules.ADVANCE_WEATHER, true, server);
                positionOceanCamera(server);
            }, server);
            context.waitFor(client -> pose.isDone(), 200);
            pose.join();
            waitForOceanCamera(context);
            setCloudsEnabled(context, true);
            if (fullDay) {
                positionOceanProfileView(context, server, 6000, 90);
            }
            waitForRaytracedPresents(context, 12);
            waitForOceanTerrain(context, fullDay ? 502 : 1);
            System.out.println("KAVORA_PROFILE_READY");
            context.waitFor(client -> Files.isRegularFile(startFile), 2400);
            context.runOnClient(client -> {
                var api = KavoraMod.renderer.getRemixApi();
                for (String key : List.of("rtx.graphicsPreset", "rtx.upscalerType", "rtx.qualityDLSS",
                        "rtx.enableRayReconstruction", "rtx.dlfg.enable", "rtx.clouds.sceneEnabled",
                        "rtx.clouds.windSpeed", "rtx.clouds.coverage",
                        "rtx.clouds.maxDistanceMeters", "rtx.clouds.highMaxDistanceMeters",
                        "rtx.clouds.steps", "rtx.clouds.highSteps", "rtx.clouds.lightSteps")) {
                    System.out.println("KAVORA_PROFILE_OPTION " + key + "=" + api.getConfigVariableResolved(key));
                }
                var diagnostics = KavoraMod.renderer.getChunkManager().getTerrainDiagnostics();
                System.out.println("KAVORA_PROFILE_TERRAIN " + diagnostics);
            });
            if (fullDay) {
                prepareOceanProfileView(context, server, 6000, 90, 32_000);
            }
            measureOceanProfileView(context, fullDay ? "noon" : "live", fullDay);
            if (fullDay) {
                prepareOceanProfileView(context, server, 23250, -90, 32_200);
                measureOceanProfileView(context, "sunrise", true);
                prepareOceanProfileView(context, server, 12750, 90, 32_400);
                measureOceanProfileView(context, "sunset", true);
            }
            KavoraWorldGameTest.require(context.computeOnClient(client -> KavoraFrameProbe.failures == 0),
                    "Ocean cloud profile encountered a native frame failure");
        } finally {
            closeCloudFixture(context, server);
        }
    }

    private static void prepareOceanProfileView(ClientGameTestContext context, MinecraftServer server,
                                                int tick, float yaw, long gameTick) {
        positionOceanProfileView(context, server, tick, yaw);
        // Wind follows elapsed world time independently of the solar clock.
        var wind = CompletableFuture.runAsync(() -> {
            server.getWorldData().overworldData().setGameTime(gameTick);
            server.forceGameTimeSynchronization();
        }, server);
        context.waitFor(client -> wind.isDone() && Math.abs(cloudValue("worldTimeSeconds") * 20 - gameTick) < 20, 200);
        wind.join();
        waitForRaytracedPresents(context, 8);
        resumeOceanProfileTime(context, server);
    }

    private static void positionOceanProfileView(ClientGameTestContext context, MinecraftServer server,
                                                 int tick, float yaw) {
        var setup = CompletableFuture.runAsync(() -> {
            server.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
            positionOceanCamera(server, yaw);
        }, server);
        context.waitFor(client -> setup.isDone(), 200);
        setup.join();
        waitForOceanCamera(context, yaw);
        setFixtureTime(context, server, tick);
        waitForRaytracedPresents(context, 8);
    }

    private static void resumeOceanProfileTime(ClientGameTestContext context, MinecraftServer server) {
        var resume = CompletableFuture.runAsync(() -> server.getGameRules().set(GameRules.ADVANCE_TIME, true, server), server);
        context.waitFor(client -> resume.isDone(), 200);
        resume.join();
    }

    private static void measureOceanProfileView(ClientGameTestContext context, String view, boolean screenshot) {
        var begin = markCloudProfile(context, "BEGIN", view);
        waitForRaytracedPresents(context, 24);
        var end = markCloudProfile(context, "END", view);
        long frames = end.presentations() - begin.presentations();
        double frameMillis = (double) (end.nanoTime() - begin.nanoTime()) / frames / 1_000_000;
        System.out.printf(java.util.Locale.ROOT, "Cloud ocean timing: %s, %d presentations, %.2f ms/frame, %.1f fps%n",
                view, frames, frameMillis, 1000 / frameMillis);
        KavoraWorldGameTest.require(end.tick() > begin.tick(), "Cloud profiling requires advancing simulation time");
        if (screenshot) {
            Path image = KavoraWorldGameTest.captureCompletedFrame(context, "cloud-profile-" + view);
            KavoraWorldGameTest.requireVisibleWorld(image);
        }
    }

    private record CloudProfileMark(long tick, long presentations, long nanoTime) {}

    private static CloudProfileMark markCloudProfile(ClientGameTestContext context, String phase, String view) {
        return context.computeOnClient(client -> {
            long clock = client.level.getOverworldClockTime();
            var mark = new CloudProfileMark(clock, KavoraFrameProbe.raytracedPresents, System.nanoTime());
            var api = KavoraMod.renderer.getRemixApi();
            System.out.println("KAVORA_PROFILE_" + phase + " " + clock + " " + System.currentTimeMillis()
                    + " view=" + view + " cloudsTime=" + api.getConfigVariableResolved("rtx.clouds.worldTimeSeconds")
                    + " rain=" + api.getConfigVariableResolved("rtx.clouds.weather")
                    + " thunder=" + client.level.getThunderLevel(0f));
            return mark;
        });
    }

    private static void checkCameraTranslation(ClientGameTestContext context, TestSingleplayerContext world, double height) {
        try (var upscaler = RuntimeOptionScope.acquire(context, "rtx.upscalerType", "0");
             var reconstruction = RuntimeOptionScope.acquire(context, "rtx.enableRayReconstruction", "false");
             var coverage = RuntimeOptionScope.acquire(context, "rtx.clouds.coverage", "0.65")) {
            pose(context, world, 0, height, -2000, 0, -25);
            waitPresentedFrames(context, 12);
            try (var exposure = RuntimeOptionScope.acquire(context, "rtx.autoExposure.autoExposureSpeed", "0")) {
                setCloudsEnabled(context, false);
                waitPresentedFrames(context, 8);
                Path clear = KavoraWorldGameTest.captureCompletedFrame(context, "cloud-position-clear");
                setCloudsEnabled(context, true);
                waitPresentedFrames(context, 12);
                Path start = KavoraWorldGameTest.captureCompletedFrame(context, "cloud-position-start");
                requireDifference(clear, start);
                pose(context, world, 256, height, -2000, 0, -25);
                waitPresentedFrames(context, 12);
                Path translated = KavoraWorldGameTest.captureCompletedFrame(context, "cloud-position-translated");
                pose(context, world, 0, height, -2000, 0, -25);
                waitPresentedFrames(context, 12);
                Path returned = KavoraWorldGameTest.captureCompletedFrame(context, "cloud-position-returned");
                double moved = skyImageDifference(start, translated);
                double restored = skyImageDifference(start, returned);
                KavoraWorldGameTest.require(moved > 10 && moved > 2 * restored,
                        "Cloud field did not follow world-space parallax: " + moved + " vs " + restored);
                checkCloudMotionVectors(context);
            }
        }
    }

    private static void checkCloudMotionVectors(ClientGameTestContext context) {
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
                    .info("Cloud motion guide difference during translation: {}", difference);
            KavoraWorldGameTest.require(difference > 4, "Cloud pixels lost the camera's translation");
        }
    }

    private static void captureCloudFrameGeneration(ClientGameTestContext context, TestSingleplayerContext world, double height) {
        try (var coverage = RuntimeOptionScope.acquire(context, "rtx.clouds.coverage", "0.65");
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
                    "Cloud lighting: scattering={}, lightSteps={}, sun={}, radiance={}",
                    cloudValue("multipleScattering"), cloudValue("lightSteps"),
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
                && Math.abs(client.level.getThunderLevel(0f) - thunder) < 0.01, 300);
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

    private static void captureOvercast(ClientGameTestContext context, TestSingleplayerContext world) {
        context.runOnClient(client -> {
            if (!client.gui.hud.isHidden()) client.gui.hud.toggle();
            KavoraMod.renderer.getCloudRenderer().setProceduralClouds(true);
        });
        setCloudsEnabled(context, true);
        setClock(context, world, 6000);
        world.getServer().runOnServer(server -> KavoraSceneFixtures.commands(server,
                "fill -9 -61 -9 9 -61 22 minecraft:smooth_quartz"));
        pose(context, world, 0, -60, -4, 25, 0);
        for (String weather : List.of("clear", "rain", "thunder")) {
            setWeather(context, world, weather);
            waitPresentedFrames(context, 48);
            logFogProfile(context, weather);
            captureSkyBeforePrecipitation(context, "overcast-" + weather + "-sky");
            KavoraWorldGameTest.requireVisibleWorld(
                    KavoraWorldGameTest.captureCompletedFrame(context, "overcast-" + weather));
            if (weather.equals("rain")) verifyRainVisibility(context);
        }
        captureTransmittance(context, "overcast-thunder-horizon-transmission");
        world.getServer().runOnServer(server ->
                server.getPlayerList().getPlayers().getFirst().setGameMode(GameType.SPECTATOR));
        pose(context, world, 0, 1600, -4, 25, 18);
        waitPresentedFrames(context, 48);
        KavoraWorldGameTest.require(context.computeOnClient(client -> Math.abs(client.player.getY() - 1600) < 0.01),
                "Overcast high-altitude camera moved");
        captureSkyBeforePrecipitation(context, "overcast-thunder-aerial-sky");
        KavoraWorldGameTest.requireVisibleWorld(
                KavoraWorldGameTest.captureCompletedFrame(context, "overcast-thunder-aerial"));
        // The 1600 m camera can be inside the cumulus field. Inspect its upper surface
        // from the clear gap between the actual low and high layer settings as well.
        double aboveLow = context.computeOnClient(client -> {
            double top = cloudValue("worldBaseHeightMeters") + cloudValue("altitudeOffsetMeters")
                    + cloudValue("thicknessMeters");
            return (top + Math.max(top + 1000.0, cloudValue("highAltitudeMeters"))) * .5;
        });
        pose(context, world, 0, aboveLow, -4, 25, 35);
        waitPresentedFrames(context, 48);
        captureSkyBeforePrecipitation(context, "overcast-thunder-above-clouds-sky");

        // The level is flat at -60. At this pitch the centre ray crosses
        // ground height hundreds of blocks beyond the client's two chunks.
        setWeather(context, world, "clear");
        setClock(context, world, 24500);
        world.getServer().runOnServer(server -> KavoraSceneFixtures.commands(server,
                "fillbiome -8 64 -12 8 71 4 minecraft:forest"));
        pose(context, world, 0, 65, -4, 25, 11);
        context.waitFor(client -> Double.parseDouble(KavoraMod.renderer.getRemixApi()
                .getConfigVariableResolved("rtx.volumetrics.groundMistExtinctionPerMeter")) > 0.001, 300);
        waitPresentedFrames(context, 48);
        logFogProfile(context, "dawn-mist");
        captureSkyBeforePrecipitation(context, "dawn-mist-missing-terrain");
        boolean automatic = context.computeOnClient(client -> KavoraMod.renderer.getConfLayerDriver().isAutomaticMist());
        try {
            context.runOnClient(client -> KavoraMod.renderer.getConfLayerDriver().setAutomaticMist(false));
            context.waitFor(client -> Double.parseDouble(KavoraMod.renderer.getRemixApi()
                    .getConfigVariableResolved("rtx.volumetrics.groundMistExtinctionPerMeter")) < 0.0001, 300);
            waitPresentedFrames(context, 48);
            captureSkyBeforePrecipitation(context, "dawn-no-mist-missing-terrain");
        } finally {
            context.runOnClient(client -> KavoraMod.renderer.getConfLayerDriver().setAutomaticMist(automatic));
        }
    }

    private static void verifyRainVisibility(ClientGameTestContext context) {
        var rain = WeatherVisibility.RAIN;
        int original = context.computeOnClient(client -> KavoraMod.renderer.getConfLayerDriver().weatherVisibility(rain));
        try {
            context.runOnClient(client -> KavoraMod.renderer.getConfLayerDriver().setWeatherVisibility(rain, 32));
            context.waitFor(client -> {
                var api = KavoraMod.renderer.getRemixApi();
                String[] tint = api.getConfigVariableResolved("rtx.volumetrics.transmittanceColor").split(",");
                double length = Double.parseDouble(api.getConfigVariableResolved("rtx.volumetrics.transmittanceMeasurementDistanceMeters"));
                return Math.abs(Math.pow(Double.parseDouble(tint[1]), 2.2 * 32 / length) - 0.05) < 0.002;
            }, 250);
            waitPresentedFrames(context, 48);
            logFogProfile(context, "rain-32m");
            captureSkyBeforePrecipitation(context, "overcast-rain-32m");
        } finally {
            context.runOnClient(client -> KavoraMod.renderer.getConfLayerDriver().setWeatherVisibility(rain, original));
        }
    }

    private static void logFogProfile(ClientGameTestContext context, String weather) {
        context.runOnClient(client -> {
            var api = KavoraMod.renderer.getRemixApi();
            System.out.printf("Fog profile %s: enabled=%s finite=%s range=%s transmission=%s mist=%s fade=%s..%s%n", weather,
                    api.getConfigVariableResolved("rtx.volumetrics.enable"),
                    api.getConfigVariableResolved("rtx.volumetrics.enableAtmosphere"),
                    api.getConfigVariableResolved("rtx.volumetrics.transmittanceMeasurementDistanceMeters"),
                    api.getConfigVariableResolved("rtx.volumetrics.transmittanceColor"),
                    api.getConfigVariableResolved("rtx.volumetrics.groundMistExtinctionPerMeter"),
                    api.getConfigVariableResolved("rtx.viewDistance.distanceFadeMin"),
                    api.getConfigVariableResolved("rtx.viewDistance.distanceFadeMax"));
        });
    }

    private static void captureSkyBeforePrecipitation(ClientGameTestContext context, String name) {
        CompletableFuture<Path> capture = new CompletableFuture<>();
        context.runOnClient(client -> KavoraFrameProbe.beforePrecipitation = () -> {
            Path destination = client.gameDirectory.toPath().resolve("screenshots")
                    .resolve("remix-world-" + name + ".png");
            KavoraWorldGameTest.captureTarget(client.gameRenderer.mainRenderTarget(), destination)
                    .whenComplete((path, error) -> {
                        if (error == null) capture.complete(path);
                        else capture.completeExceptionally(error);
                    });
        });
        try {
            context.waitFor(client -> capture.isDone(), 100);
            KavoraWorldGameTest.requireVisibleWorld(capture.join());
        } finally {
            context.runOnClient(client -> KavoraFrameProbe.beforePrecipitation = null);
        }
    }

    private static Path captureCelestialView(ClientGameTestContext context, TestSingleplayerContext world,
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
        return KavoraWorldGameTest.captureCompletedFrame(context, "clouds-" + weather + "-" + phase);
    }

    private static void requireClearSolarView(Path screenshot) {
        try {
            var image = ImageIO.read(screenshot.toFile());
            int centerX = image.getWidth() / 2, centerY = image.getHeight() / 2;
            int bright = 0;
            for (int y = centerY - 4; y <= centerY + 4; y++) {
                for (int x = centerX - 4; x <= centerX + 4; x++) {
                    int rgb = image.getRGB(x, y);
                    if ((rgb >> 16 & 255) >= 220 && (rgb >> 8 & 255) >= 220 && (rgb & 255) >= 210) bright++;
                }
            }
            KavoraWorldGameTest.require(bright >= 73, "High-altitude solar core is dim: " + screenshot);
            // Locate the resolved edge of the eight-texel vanilla disc above the sky.
            int halfDisc = 0;
            while (centerX + halfDisc < image.getWidth()) {
                int rgb = image.getRGB(centerX + halfDisc, centerY);
                if ((rgb >> 16 & 255) < 180) break;
                halfDisc++;
            }
            // Use the disc edge, not the extent of the soft glow around it.
            int edge = 1;
            int greatestFall = 0;
            for (int x = 1; x < halfDisc; x++) {
                int fall = (image.getRGB(centerX + x - 1, centerY) >> 16 & 255)
                        - (image.getRGB(centerX + x, centerY) >> 16 & 255);
                if (fall > greatestFall) {
                    greatestFall = fall;
                    edge = x;
                }
            }
            int rim = Math.round(edge * 0.875f);
            for (int dx : new int[] {-rim, rim}) {
                for (int dy : new int[] {-rim, 0, rim}) {
                    int rgb = image.getRGB(centerX + dx, centerY + dy);
                    KavoraWorldGameTest.require((rgb >> 16 & 255) >= 215 && (rgb >> 8 & 255) >= 215,
                            "Solar disc has lost its square outline: " + screenshot);
                }
            }
            // The clear sky beyond the disc must remain blue all the way around it.
            // A baked sprite halo amplified as direct sunlight turns this region yellow.
            int radius = image.getHeight() / 3;
            for (int[] offset : new int[][] {{-radius, 0}, {radius, 0}, {0, -radius}, {0, radius}}) {
                int rgb = image.getRGB(centerX + offset[0], centerY + offset[1]);
                KavoraWorldGameTest.require((rgb & 255) > (rgb >> 16 & 255),
                        "High-altitude sky is discolored around the solar disc: " + screenshot);
            }
            int sky = image.getRGB(centerX + radius, centerY);
            int near = image.getRGB(centerX + 2 * edge, centerY);
            KavoraWorldGameTest.require((near >> 16 & 255) - (sky >> 16 & 255) >= 35,
                    "Daylight glare is confined to the solar sprite: " + screenshot);
            KavoraWorldGameTest.require((near & 255) + 5 >= (sky & 255),
                    "Daylight glare forms a dirty yellow ring in the blue sky: " + screenshot);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void requireWarmerLowSun(Path high, Path low) {
        try {
            var day = ImageIO.read(high.toFile());
            var dusk = ImageIO.read(low.toFile());
            // The core and inner rim clip on SDR. Read the resolved glare on both
            // sides above the disc centre, clear of the low sun's cloud horizon.
            int cx = day.getWidth() / 2, cy = day.getHeight() / 2;
            int radius = 0;
            while (cx + radius < day.getWidth() && (day.getRGB(cx + radius, cy) >> 16 & 255) >= 240) radius++;
            KavoraWorldGameTest.require(radius > 0 && radius < day.getWidth() / 4, "Missing resolved daylight disc: " + high);
            for (int side : new int[] {-1, 1}) {
                int x = cx + side * (3 * radius / 2), y = cy - radius / 2;
                int a = day.getRGB(x, y), b = dusk.getRGB(x, y);
                int dayRed = a >> 16 & 255, duskRed = b >> 16 & 255;
                KavoraWorldGameTest.require(dayRed > 0 && dayRed < 245 && duskRed > 100 && duskRed < 245
                        && (b & 255) / (double) duskRed + 0.05 < (a & 255) / (double) dayRed,
                        "Low-sun glare did not redden along the longer air path: " + low);
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
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
