/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.gametest;

import org.edtp.kavora.KavoraMod;
import org.edtp.kavora.logic.LogicChannels;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CloudStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.gamerules.GameRules;
import org.slf4j.LoggerFactory;

/** Views automatic forest mist and a roof aperture through the same ray-traced medium. */
final class KavoraAtmosphereChecks {
    private KavoraAtmosphereChecks() {}

    static void run(ClientGameTestContext context, TestSingleplayerContext world) {
        CloudStatus clouds = context.computeOnClient(client -> client.options.cloudStatus().get());
        boolean hidden = context.computeOnClient(client -> client.gui.hud.isHidden());
        Map<String, String> original = context.computeOnClient(client -> {
            var result = new LinkedHashMap<String, String>();
            for (String key : new String[] {"rtx.graphicsPreset", "rtx.dlssPreset", "rtx.upscalerType", "rtx.dlfg.enable",
                    "rtx.enableRayReconstruction", "rtx.sparseRendering.enableSparseRendering", "rtx.volumetrics.enable",
                    "rtx.volumetrics.transmittanceColor", "rtx.volumetrics.transmittanceMeasurementDistanceMeters",
                    "rtx.volumetrics.singleScatteringAlbedo", "rtx.volumetrics.anisotropy",
                    "rtx.volumetrics.heightFogBaseHeightMeters", "rtx.volumetrics.heightFogFalloffMeters",
                    "rtx.volumetrics.enableHeterogeneousFog", "rtx.volumetrics.enableReferenceMode",
                    "rtx.volumetrics.maxAccumulationFrames", "rtx.rngSeedWithFrameIndex",
                    "rtx.volumetrics.froxelGridResolutionScale", "rtx.volumetrics.froxelDepthSlices",
                    "rtx.volumetrics.enableInitialVisibility", "rtx.volumetrics.visibilityReuse",
                    "rtx.volumetrics.enableTemporalResampling", "rtx.volumetrics.enableSpatialResampling",
                    "rtx.debugView.debugViewIdx",
                    "rtx.debugView.maxValue", "rtx.debugView.replaceCompositeOutput"}) {
                result.put(key, KavoraMod.renderer.getRemixApi().getConfigVariableResolved(key));
            }
            return result;
        });
        try {
            context.getInput().resizeWindow(1600, 900);
            context.runOnClient(client -> {
                client.options.renderDistance().set(8);
                client.options.cloudStatus().set(CloudStatus.OFF);
                if (!client.gui.hud.isHidden()) client.gui.hud.toggle();
                var api = KavoraMod.renderer.getRemixApi();
                api.setConfigVariable("rtx.graphicsPreset", "4");
                api.setConfigVariable("rtx.dlssPreset", "2");
            });
            context.waitFor(client -> KavoraMod.renderer.getRemixApi()
                    .getConfigVariableResolved("rtx.graphicsPreset").equals("4"), 100);
            context.runOnClient(client -> {
                var api = KavoraMod.renderer.getRemixApi();
                api.setConfigVariable("rtx.upscalerType", "0");
                api.setConfigVariable("rtx.dlfg.enable", "false");
                api.setConfigVariable("rtx.enableRayReconstruction", "false");
                api.setConfigVariable("rtx.sparseRendering.enableSparseRendering", "false");
            });
            world.getServer().runOnServer(server -> {
                server.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
                server.getGameRules().set(GameRules.ADVANCE_WEATHER, false, server);
                server.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
                server.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 0, server);
                KavoraSceneFixtures.commands(server, "weather clear", "fill -32 63 -24 32 63 72 minecraft:moss_block",
                        "fillbiome -32 64 -24 32 67 72 minecraft:forest");
                var level = server.overworld();
                var leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
                var p = new BlockPos.MutableBlockPos();
                for (int row = 0; row < 6; row++) for (int column = 0; column < 6; column++) {
                    int x = -28 + column * 11 + (row % 3 - 1) * 2;
                    int z = 4 + row * 11 + (column % 3 - 1);
                    int height = 7 + (row + column) % 3;
                    for (int y = 64; y < 64 + height; y++) level.setBlockAndUpdate(p.set(x, y, z), Blocks.OAK_LOG.defaultBlockState());
                    for (int dz = -3; dz <= 3; dz++) for (int dx = -3; dx <= 3; dx++) {
                        if (dx * dx + dz * dz > 11) continue;
                        for (int dy = -1; dy <= 1; dy++) level.setBlockAndUpdate(p.set(x + dx, 63 + height + dy, z + dz), leaves);
                    }
                }
                var player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.CREATIVE);
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                if (!player.teleportTo(level, .5, 65, -14.5, Set.of(), 0, 1, true)) {
                    throw new AssertionError("Forest camera setup failed");
                }
                server.clockManager().setTotalTicks(server.registryAccess()
                        .get(net.minecraft.world.clock.WorldClocks.OVERWORLD).orElseThrow(), 24500);
            });
            KavoraWorldGameTest.assertWorldRenders(context, world, "atmosphere-forest");
            context.waitFor(client -> KavoraMod.renderer.getConfLayerDriver().snapshot().stream()
                    .anyMatch(layer -> layer.name().equals(LogicChannels.WEATHER_MIST) && layer.strength() > .4f), 200);
            context.waitFor(client -> Double.parseDouble(KavoraMod.renderer.getRemixApi()
                    .getConfigVariableResolved("rtx.volumetrics.groundMistExtinctionPerMeter")) > 0, 100);
            waitPresentedFrames(context, 64);
            KavoraWorldGameTest.captureCompletedFrame(context, "atmosphere-forest-dawn");
            captureForestSweep(context);
            captureTransport(context, "forest");
            context.runOnClient(client -> LoggerFactory.getLogger(KavoraAtmosphereChecks.class).info(
                    "Forest mist layers={}, extinction={}, enabled={}, cacheDistance={}",
                    KavoraMod.renderer.getConfLayerDriver().snapshot().stream()
                            .filter(layer -> layer.name().equals(LogicChannels.WEATHER_MIST)).toList(),
                    KavoraMod.renderer.getRemixApi().getConfigVariableResolved("rtx.volumetrics.groundMistExtinctionPerMeter"),
                    KavoraMod.renderer.getRemixApi().getConfigVariableResolved("rtx.volumetrics.enable"),
                    KavoraMod.renderer.getRemixApi().getConfigVariableResolved("rtx.volumetrics.froxelMaxDistanceMeters")));
            context.runOnClient(client -> KavoraMod.renderer.getRemixApi().setConfigVariable("rtx.volumetrics.enable", "false"));
            context.waitTicks(20);
            KavoraWorldGameTest.captureCompletedFrame(context, "atmosphere-forest-no-fog");
            world.getServer().runOnServer(server -> {
                if (!server.getPlayerList().getPlayers().getFirst().teleportTo(server.overworld(), -29.5, 65, 29.5,
                        Set.of(), -90, -3, true)) throw new AssertionError("Sunward forest camera setup failed");
            });
            context.runOnClient(client -> KavoraMod.renderer.getRemixApi().setConfigVariable("rtx.volumetrics.enable", "true"));
            context.waitTicks(40);
            KavoraWorldGameTest.captureCompletedFrame(context, "atmosphere-forest-sunward");
            context.runOnClient(client -> KavoraMod.renderer.getRemixApi().setConfigVariable("rtx.volumetrics.enable", "false"));
            context.waitTicks(20);
            KavoraWorldGameTest.captureCompletedFrame(context, "atmosphere-forest-sunward-no-fog");
            world.getServer().runOnServer(server -> server.clockManager().setTotalTicks(server.registryAccess()
                    .get(net.minecraft.world.clock.WorldClocks.OVERWORLD).orElseThrow(), 26000));
            context.runOnClient(client -> {
                var api = KavoraMod.renderer.getRemixApi();
                api.setConfigVariable("rtx.volumetrics.enable", "true");
                api.setConfigVariable("rtx.volumetrics.maxAccumulationFrames", original.get("rtx.volumetrics.maxAccumulationFrames"));
            });
            waitPresentedFrames(context, 64);
            KavoraWorldGameTest.captureCompletedFrame(context, "atmosphere-forest-later");
            world.getServer().runOnServer(server -> {
                if (!server.getPlayerList().getPlayers().getFirst().teleportTo(server.overworld(), -12.5, 82, -14.5,
                        Set.of(), 0, 28, true)) throw new AssertionError("Aerial forest camera setup failed");
            });
            waitPresentedFrames(context, 64);
            KavoraWorldGameTest.captureCompletedFrame(context, "atmosphere-forest-aerial");
            world.getServer().runOnServer(server -> {
                KavoraSceneFixtures.commands(server, "fill -16 63 88 16 63 128 minecraft:stone_bricks",
                        "fill -16 76 88 16 78 128 minecraft:stone", "fill -16 64 88 -16 75 128 minecraft:stone",
                        "fill 16 64 88 16 75 128 minecraft:stone", "fill -16 64 128 16 75 128 minecraft:stone",
                        "fill -3 76 102 3 78 107 minecraft:air", "setblock -6 64 112 minecraft:torch");
                var player = server.getPlayerList().getPlayers().getFirst();
                if (!player.teleportTo(server.overworld(), .5, 65, 90.5, Set.of(), 0, 5, true)) {
                    throw new AssertionError("Cave camera setup failed");
                }
                server.clockManager().setTotalTicks(server.registryAccess()
                        .get(net.minecraft.world.clock.WorldClocks.OVERWORLD).orElseThrow(), 30000);
            });
            context.runOnClient(client -> {
                var api = KavoraMod.renderer.getRemixApi();
                api.setConfigVariable("rtx.volumetrics.enable", "true");
                api.setConfigVariable("rtx.volumetrics.transmittanceColor", "0.8, 0.8, 0.8");
                api.setConfigVariable("rtx.volumetrics.transmittanceMeasurementDistanceMeters", "20");
                api.setConfigVariable("rtx.volumetrics.singleScatteringAlbedo", "0.98, 0.98, 0.98");
                api.setConfigVariable("rtx.volumetrics.anisotropy", "0.5");
                api.setConfigVariable("rtx.volumetrics.heightFogBaseHeightMeters", "64");
                api.setConfigVariable("rtx.volumetrics.heightFogFalloffMeters", "16");
                api.setConfigVariable("rtx.volumetrics.enableHeterogeneousFog", "false");
            });
            context.waitTicks(60);
            KavoraWorldGameTest.assertWorldRenders(context, world, "atmosphere-cave");
            context.runOnClient(client -> KavoraMod.renderer.getRemixApi()
                    .setConfigVariable("rtx.rngSeedWithFrameIndex", "false"));
            waitPresentedFrames(context, 8);
            var production = captureTransport(context, "cave");
            context.runOnClient(client -> KavoraMod.renderer.getRemixApi()
                    .setConfigVariable("rtx.volumetrics.enableReferenceMode", "true"));
            context.waitTicks(40);
            var reference = captureTransport(context, "cave-reference");
            requireComparableScattering(production.get("shared"), reference.get("shared"));
            context.runOnClient(client -> {
                var api = KavoraMod.renderer.getRemixApi();
                api.setConfigVariable("rtx.volumetrics.enableReferenceMode", "false");
                api.setConfigVariable("rtx.rngSeedWithFrameIndex", "true");
            });
            context.runOnClient(client -> KavoraMod.renderer.getRemixApi()
                    .setConfigVariable("rtx.volumetrics.maxAccumulationFrames", original.get("rtx.volumetrics.maxAccumulationFrames")));
            waitPresentedFrames(context, 128);
            KavoraWorldGameTest.captureCompletedFrame(context, "atmosphere-cave-final");
            context.runOnClient(client -> KavoraMod.renderer.getRemixApi()
                    .setConfigVariable("rtx.volumetrics.froxelDepthSlices", "128"));
            waitPresentedFrames(context, 128);
            KavoraWorldGameTest.captureCompletedFrame(context, "atmosphere-cave-depth-grid");
            context.runOnClient(client -> {
                var api = KavoraMod.renderer.getRemixApi();
                api.setConfigVariable("rtx.volumetrics.froxelGridResolutionScale", "4");
            });
            waitPresentedFrames(context, 128);
            KavoraWorldGameTest.captureCompletedFrame(context, "atmosphere-cave-fine-grid");
            context.runOnClient(client -> {
                var api = KavoraMod.renderer.getRemixApi();
                api.setConfigVariable("rtx.volumetrics.froxelGridResolutionScale", original.get("rtx.volumetrics.froxelGridResolutionScale"));
                api.setConfigVariable("rtx.volumetrics.froxelDepthSlices", original.get("rtx.volumetrics.froxelDepthSlices"));
                api.setConfigVariable("rtx.volumetrics.transmittanceColor", "0.97, 0.97, 0.97");
            });
            waitPresentedFrames(context, 128);
            KavoraWorldGameTest.captureCompletedFrame(context, "atmosphere-cave-subtle");
            context.runOnClient(client -> KavoraMod.renderer.getRemixApi().setConfigVariable("rtx.volumetrics.enable", "false"));
            waitPresentedFrames(context, 16);
            KavoraWorldGameTest.captureCompletedFrame(context, "atmosphere-cave-no-fog");
            context.runOnClient(client -> KavoraMod.renderer.getRemixApi().setConfigVariable("rtx.volumetrics.enable", "true"));
            waitPresentedFrames(context, 128);
            setDebugView(context, 205);
            Path firstStable = KavoraWorldGameTest.captureCompletedFrame(context, "atmosphere-cave-stable-a");
            waitPresentedFrames(context, 16);
            Path secondStable = KavoraWorldGameTest.captureCompletedFrame(context, "atmosphere-cave-stable-b");
            logTemporalNoise(firstStable, secondStable);
            setDebugView(context, 68);
            KavoraWorldGameTest.captureCompletedFrame(context, "atmosphere-cave-history");
            setDebugView(context, 0);
            for (int x = 1; x <= 3; x++) {
                int offset = x;
                world.getServer().runOnServer(server -> {
                    if (!server.getPlayerList().getPlayers().getFirst().teleportTo(server.overworld(), offset + .5, 65, 90.5,
                            Set.of(), 0, 5, true)) throw new AssertionError("Moving fog camera failed");
                });
                waitPresentedFrames(context, 16);
                KavoraWorldGameTest.captureCompletedFrame(context, "atmosphere-cave-moving-" + x);
            }
            world.getServer().runOnServer(server -> {
                KavoraSceneFixtures.commands(server, "fill 48 63 88 80 78 128 minecraft:stone",
                        "fill 49 64 89 79 75 127 minecraft:air", "fill 49 63 89 79 63 127 minecraft:stone_bricks",
                        "fill 61 76 102 67 78 107 minecraft:air", "setblock 58 64 112 minecraft:torch");
                if (!server.getPlayerList().getPlayers().getFirst().teleportTo(server.overworld(), 64.5, 65, 90.5,
                        Set.of(), 0, 5, true)) throw new AssertionError("Enclosed cave camera failed");
            });
            KavoraWorldGameTest.assertWorldRenders(context, world, "atmosphere-cave-enclosed");
            waitPresentedFrames(context, 128);
            KavoraWorldGameTest.captureCompletedFrame(context, "atmosphere-cave-enclosed-subtle");
        } finally {
            context.runOnClient(client -> {
                original.forEach((key, value) -> KavoraMod.renderer.getRemixApi().setConfigVariable(key, value));
                client.options.cloudStatus().set(clouds);
                if (client.gui.hud.isHidden() != hidden) client.gui.hud.toggle();
            });
        }
    }

    private static void captureForestSweep(ClientGameTestContext context) {
        float initialYaw = context.computeOnClient(client -> client.player.getYRot());
        long first = context.computeOnClient(client -> KavoraFrameProbe.presents);
        try {
            context.runOnClient(client -> KavoraFrameProbe.frameAction = frame -> {
                float yaw = initialYaw + (frame - first) * 0.25f;
                client.player.setYRot(yaw);
                client.player.yRotO = yaw;
            });
            for (int frame : new int[] {8, 16, 24}) {
                context.waitFor(client -> KavoraFrameProbe.presents >= first + frame, 400);
                KavoraWorldGameTest.captureCompletedFrame(context, "atmosphere-forest-yaw-" + frame);
            }
        } finally {
            context.runOnClient(client -> {
                KavoraFrameProbe.frameAction = null;
                client.player.setYRot(initialYaw);
                client.player.yRotO = initialYaw;
            });
        }
        waitPresentedFrames(context, 64);
    }

    private static Map<String, Path> captureTransport(ClientGameTestContext context, String scene) {
        var captures = new LinkedHashMap<String, Path>();
        context.runOnClient(client -> {
            var api = KavoraMod.renderer.getRemixApi();
            api.setConfigVariable("rtx.volumetrics.maxAccumulationFrames", "1");
            api.setConfigVariable("rtx.debugView.maxValue", "1");
            api.setConfigVariable("rtx.debugView.replaceCompositeOutput", "false");
        });
        for (var view : java.util.List.of(Map.entry("transmittance", 53), Map.entry("incident", 48), Map.entry("shared", 205),
                Map.entry("reservoir", 58), Map.entry("light-map", 59))) {
            setDebugView(context, view.getValue());
            captures.put(view.getKey(), KavoraWorldGameTest.captureCompletedFrame(context, "atmosphere-" + scene + "-" + view.getKey()));
        }
        requireLightHistory(captures.get("light-map"));
        setDebugView(context, 0);
        return captures;
    }

    private static void requireLightHistory(Path path) {
        try {
            var image = ImageIO.read(path.toFile());
            int red = 0, green = 0;
            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                if ((rgb >>> 16 & 255) > 200 && (rgb >>> 8 & 255) < 20) red++;
                if ((rgb >>> 8 & 255) > 200 && (rgb >>> 16 & 255) < 20) green++;
            }
            KavoraWorldGameTest.require(green > 1000 && red == 0, "Distant lights lost their previous-frame mapping: " + red);
        } catch (IOException error) {
            throw new AssertionError("Cannot read light-history validation", error);
        }
    }

    private static void waitPresentedFrames(ClientGameTestContext context, int count) {
        long first = context.computeOnClient(client -> KavoraFrameProbe.presents);
        context.waitFor(client -> KavoraFrameProbe.presents >= first + count, 400);
    }

    private static void requireComparableScattering(Path production, Path reference) {
        try {
            var actual = ImageIO.read(production.toFile());
            var expected = ImageIO.read(reference.toFile());
            long a = 0, b = 0;
            int pixels = 0;
            for (int y = 200; y < 600; y++) for (int x = 600; x < 1000; x++) {
                int e = expected.getRGB(x, y);
                int eSum = (e >>> 16 & 255) + (e >>> 8 & 255) + (e & 255);
                if (eSum < 96) continue;
                int p = actual.getRGB(x, y);
                a += (p >>> 16 & 255) + (p >>> 8 & 255) + (p & 255);
                b += eSum;
                pixels++;
            }
            KavoraWorldGameTest.require(pixels > 1000, "Reference fixture has no light shaft");
            KavoraWorldGameTest.require(a > b * 0.65 && a < b * 1.35,
                    "Reused volume lighting disagrees with the all-light reference: " + a + " / " + b);
        } catch (IOException error) {
            throw new AssertionError("Cannot compare volumetric lighting", error);
        }
    }

    private static void logTemporalNoise(Path first, Path second) {
        try {
            var a = ImageIO.read(first.toFile());
            var b = ImageIO.read(second.toFile());
            long difference = 0, signal = 0;
            for (int y = 200; y < 600; y++) for (int x = 600; x < 1000; x++) {
                int p = a.getRGB(x, y), q = b.getRGB(x, y);
                for (int shift = 0; shift < 24; shift += 8) {
                    int av = p >>> shift & 255, bv = q >>> shift & 255;
                    difference += Math.abs(av - bv);
                    signal += av + bv;
                }
            }
            LoggerFactory.getLogger(KavoraAtmosphereChecks.class).info(
                    "Static volumetric frame delta={}", 2.0 * difference / Math.max(1, signal));
        } catch (IOException error) {
            throw new AssertionError("Cannot measure volumetric noise", error);
        }
    }

    private static void setDebugView(ClientGameTestContext context, int view) {
        String value = Integer.toString(view);
        long first = context.computeOnClient(client -> {
            KavoraMod.renderer.getRemixApi().setConfigVariable("rtx.debugView.debugViewIdx", value);
            return KavoraFrameProbe.presents;
        });
        context.waitFor(client -> KavoraFrameProbe.presents >= first + 8 && KavoraMod.renderer.getRemixApi()
                .getConfigVariableResolved("rtx.debugView.debugViewIdx").equals(value), 200);
    }
}
