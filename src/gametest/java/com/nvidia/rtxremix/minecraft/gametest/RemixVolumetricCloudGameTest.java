/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gametest;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.CloudStatus;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;

/** Exercises the same cloud density field below, within and above its world-space volume. */
public final class RemixVolumetricCloudGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        if (!Boolean.getBoolean("rtxremix.gametest.cloudsOnly")) return;
        context.getInput().resizeWindow(1280, 720);
        CloudStatus originalClouds = context.computeOnClient(client -> client.options.cloudStatus().get());
        boolean hidden = context.computeOnClient(client -> client.gui.hud.isHidden());
        int distance = context.computeOnClient(client -> client.options.renderDistance().get());
        boolean style = context.computeOnClient(client -> RtxRemixMod.renderer.getCloudRenderer().isProceduralClouds());
        String originalPreset = context.computeOnClient(client -> RtxRemixMod.renderer.getRemixApi()
                .getConfigVariableResolved("rtx.graphicsPreset"));
        String originalDlssPreset = context.computeOnClient(client -> RtxRemixMod.renderer.getRemixApi()
                .getConfigVariableResolved("rtx.dlssPreset"));
        String[] controls = {"rtx.upscalerType", "rtx.enableRayReconstruction", "rtx.sparseRendering.enableSparseRendering",
                "rtx.dlfg.enable", "rtx.clouds.windSpeed",
                "rtx.debugView.debugViewIdx", "rtx.debugView.maxValue", "rtx.debugView.replaceCompositeOutput"};
        String[] original = context.computeOnClient(client -> java.util.Arrays.stream(controls)
                .map(key -> RtxRemixMod.renderer.getRemixApi().getConfigVariableResolved(key)).toArray(String[]::new));
        context.runOnClient(client -> client.options.renderDistance().set(2));
        try (var world = context.worldBuilder().create()) {
            RemixWorldGameTest.assertWorldRenders(context, world, "cloud-fixture");
            world.getServer().runOnServer(server -> {
                server.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
                server.clockManager().setTotalTicks(server.registryAccess()
                        .get(net.minecraft.world.clock.WorldClocks.OVERWORLD).orElseThrow(), 6000);
                RemixSceneFixtures.commands(server, "weather clear");
                var player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.CREATIVE);
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                if (!player.teleportTo(server.overworld(), 0, 96, 0, Set.of(), 0, -30, true)) {
                    throw new AssertionError("Cloud camera teleport failed");
                }
            });
            context.waitFor(client -> Math.abs(client.player.getY() - 96) < 0.1, 200);
            context.runOnClient(client -> {
                var api = RtxRemixMod.renderer.getRemixApi();
                api.setConfigVariable("rtx.graphicsPreset", "4");
                api.setConfigVariable("rtx.dlssPreset", "2");
            });
            context.waitFor(client -> RtxRemixMod.renderer.getRemixApi()
                    .getConfigVariableResolved("rtx.graphicsPreset").equals("4"), 100);
            context.runOnClient(client -> {
                var api = RtxRemixMod.renderer.getRemixApi();
                api.setConfigVariable("rtx.upscalerType", "0");
                api.setConfigVariable("rtx.enableRayReconstruction", "false");
                api.setConfigVariable("rtx.sparseRendering.enableSparseRendering", "false");
                api.setConfigVariable("rtx.dlfg.enable", "false");
                api.setConfigVariable("rtx.clouds.windSpeed", "0");
                if (!client.gui.hud.isHidden()) client.gui.hud.toggle();
                client.options.cloudStatus().set(CloudStatus.OFF);
            });
            context.waitFor(client -> RtxRemixMod.renderer.getRemixApi()
                    .getConfigVariableResolved("rtx.upscalerType").equals("0")
                    && RtxRemixMod.renderer.getRemixApi().getConfigVariableResolved("rtx.enableRayReconstruction")
                    .equalsIgnoreCase("false"), 100);
            context.waitTicks(20);
            Path clear = RemixWorldGameTest.captureCompletedFrame(context, "clouds-clear");
            context.runOnClient(client -> {
                RtxRemixMod.renderer.getCloudRenderer().setProceduralClouds(true);
                client.options.cloudStatus().set(CloudStatus.FANCY);
            });
            context.waitFor(client -> RtxRemixMod.renderer.getRemixApi()
                    .getConfigVariableResolved("rtx.clouds.sceneEnabled").equalsIgnoreCase("true"), 100);
            context.waitTicks(40);
            Path below = RemixWorldGameTest.captureCompletedFrame(context, "clouds-volume-below");
            requireDifference(clear, below);
            context.runOnClient(client -> {
                var state = RtxRemixMod.renderer.getLightManager().getSkyLightState();
                org.slf4j.LoggerFactory.getLogger(RemixVolumetricCloudGameTest.class).info(
                        "Cloud fixture: sun direction={}, radiance={}, base height={}",
                        java.util.Arrays.toString(state.sunPosition()), java.util.Arrays.toString(state.sunDirectRadiance()),
                        RtxRemixMod.renderer.getRemixApi().getConfigVariableResolved("rtx.clouds.worldBaseHeightMeters"));
                var api = RtxRemixMod.renderer.getRemixApi();
                api.setConfigVariable("rtx.debugView.debugViewIdx", "57");
                api.setConfigVariable("rtx.debugView.maxValue", "100");
                api.setConfigVariable("rtx.debugView.replaceCompositeOutput", "false");
            });
            waitPresentedFrames(context, 8);
            RemixWorldGameTest.captureCompletedFrame(context, "clouds-light-irradiance");
            context.runOnClient(client -> RtxRemixMod.renderer.getRemixApi().setConfigVariable("rtx.debugView.debugViewIdx", "0"));
            for (int altitude : new int[] {228, 300}) {
                world.getServer().runOnServer(server -> {
                    var player = server.getPlayerList().getPlayers().getFirst();
                    if (!player.teleportTo(server.overworld(), 0, altitude, 0, Set.of(), 0, altitude == 228 ? 0 : 35, true)) {
                        throw new AssertionError("Cloud traversal teleport failed");
                    }
                });
                context.waitFor(client -> Math.abs(client.player.getY() - altitude) < 0.1, 200);
                context.waitTicks(20);
                RemixWorldGameTest.captureCompletedFrame(context, "clouds-volume-" + altitude);
            }
            for (int[] pose : new int[][] {{384, 96, 256, -45, -25}, {-384, 96, -256, 60, -25}, {256, 300, 384, -45, 35}}) {
                world.getServer().runOnServer(server -> {
                    if (!server.getPlayerList().getPlayers().getFirst().teleportTo(server.overworld(), pose[0], pose[1], pose[2],
                            Set.of(), pose[3], pose[4], true)) throw new AssertionError("Cloud field camera failed");
                });
                waitPresentedFrames(context, 16);
                String name = "clouds-offset-" + pose[0] + "-" + pose[1] + "-" + pose[2];
                RemixWorldGameTest.captureCompletedFrame(context, name);
                context.runOnClient(client -> RtxRemixMod.renderer.getRemixApi().setConfigVariable("rtx.debugView.debugViewIdx", "54"));
                waitPresentedFrames(context, 8);
                RemixWorldGameTest.captureCompletedFrame(context, name + "-transmittance");
                context.runOnClient(client -> RtxRemixMod.renderer.getRemixApi().setConfigVariable("rtx.debugView.debugViewIdx", "0"));
            }
            context.runOnClient(client -> client.options.cloudStatus().set(CloudStatus.OFF));
            context.waitFor(client -> RtxRemixMod.renderer.getRemixApi()
                    .getConfigVariableResolved("rtx.clouds.sceneEnabled").equalsIgnoreCase("false"), 100);
        } finally {
            context.runOnClient(client -> {
                var api = RtxRemixMod.renderer.getRemixApi();
                for (int i = 0; i < controls.length; i++) api.setConfigVariable(controls[i], original[i]);
                api.setConfigVariable("rtx.dlssPreset", originalDlssPreset);
                api.setConfigVariable("rtx.graphicsPreset", originalPreset);
                client.options.cloudStatus().set(originalClouds);
                client.options.renderDistance().set(distance);
                RtxRemixMod.renderer.getCloudRenderer().setProceduralClouds(style);
                if (client.gui.hud.isHidden() != hidden) client.gui.hud.toggle();
            });
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
