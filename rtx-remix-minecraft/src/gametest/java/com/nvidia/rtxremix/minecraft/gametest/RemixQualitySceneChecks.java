/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gametest;

import com.google.gson.GsonBuilder;
import com.nvidia.rtxremix.minecraft.QualityPreset;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.UnfocusedFramerateLimit;
import com.nvidia.rtxremix.minecraft.settings.RtxQualityPresets;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CloudStatus;
import net.minecraft.world.level.gamerules.GameRules;

/** Ground-level material, water and sky coverage through live profile switches. */
final class RemixQualitySceneChecks {
    private record Measurement(String profile, long frames, double millisecondsPerPresentedFrame,
                               long firstPresent, long lastPresent, Map<String, String> resolved) {}

    static void run(ClientGameTestContext context, TestSingleplayerContext world) {
        int backgroundLimit = context.computeOnClient(client -> UnfocusedFramerateLimit.get());
        try {
            context.runOnClient(client -> UnfocusedFramerateLimit.set(UnfocusedFramerateLimit.UNLIMITED));
            runUncapped(context, world);
        } finally {
            context.runOnClient(client -> UnfocusedFramerateLimit.set(backgroundLimit));
        }
    }

    private static void runUncapped(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runOnServer(server -> {
            server.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
            server.getGameRules().set(GameRules.ADVANCE_WEATHER, false, server);
            RemixSceneFixtures.commands(server, "weather clear", "time set 6000", "gamemode creative @a",
                    "forceload add -80 -8 96 64",
                    "fill -32 -64 -8 32 -61 64 minecraft:stone_bricks",
                    "fill -28 -63 4 28 -61 60 minecraft:water",
                    "fill -28 -64 4 28 -64 60 minecraft:sandstone",
                    "fill -8 -60 12 -6 -57 14 minecraft:gold_block",
                    "fill 6 -60 12 8 -57 14 minecraft:iron_block",
                    "fill -12 -60 18 -10 -56 20 minecraft:oak_leaves[persistent=true]",
                    "tp @a 0.5 -60 1.5 0 10");
            var player = server.getPlayerList().getPlayers().getFirst();
            player.getAbilities().flying = false;
            player.onUpdateAbilities();
        });
        context.runOnClient(client -> {
            client.options.cloudStatus().set(CloudStatus.FANCY);
            if (!client.gui.hud.isHidden()) client.gui.hud.toggle();
            var api = RtxRemixMod.renderer.getRemixApi();
            api.setConfigVariable("rtx.upscalerType", "1");
            api.setConfigVariable("rtx.qualityDLSS", "5");
            api.setConfigVariable("rtx.enableRayReconstruction", "true");
            api.setConfigVariable("rtx.dlfg.enable", "false");
        });
        List<Measurement> measurements = new ArrayList<>();
        for (var preset : new QualityPreset[] {QualityPreset.ULTRA, QualityPreset.HIGH, QualityPreset.MEDIUM,
                QualityPreset.LOW, QualityPreset.ULTRA}) {
            context.runOnClient(client -> RtxQualityPresets.apply(RtxRemixMod.getRtxSettings(), preset));
            int mode = preset == QualityPreset.ULTRA ? 0 : 1;
            context.waitFor(client -> read("rtx.clouds.transportMode").equals(Integer.toString(mode))
                    && read("rtx.clouds.sceneEnabled").equalsIgnoreCase("true")
                    && read("rtx.qualityDLSS").equals("5")
                    && !Boolean.parseBoolean(read("rtx.dlfg.enable")), 200);
            waitFrames(context, 32);
            long[] start = context.computeOnClient(client -> new long[] {RemixFrameProbe.presents, System.nanoTime()});
            waitFrames(context, 64);
            long[] end = context.computeOnClient(client -> new long[] {RemixFrameProbe.presents, System.nanoTime()});
            long frames = end[0] - start[0];
            String name = preset.id() + "-" + measurements.size();
            measurements.add(new Measurement(name, frames, (end[1] - start[1]) / 1e6 / frames,
                    start[0], end[0], context.computeOnClient(client -> resolvedQuality())));
            Path image = RemixWorldGameTest.captureCompletedFrame(context, "quality-" + name);
            RemixWorldGameTest.requireVisibleWorld(image);
            requireWaterTransmission(image);
        }
        context.runOnClient(client -> {
            try {
                Files.writeString(client.gameDirectory.toPath().resolve("quality-present-times.json"),
                        new GsonBuilder().setPrettyPrinting().create().toJson(measurements));
            } catch (IOException e) {
                throw new AssertionError("Cannot save quality scene presentation measurements", e);
            }
        });
        verifyWaterMiss(context, world);
        verifyNaturalMaterials(context, world);
        verifyFogQuality(context, world);
    }

    private static Map<String, String> resolvedQuality() {
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : List.of("rtx.clouds.transportMode", "rtx.clouds.steps", "rtx.clouds.lightSteps",
                "rtx.volumetrics.fogIntegrationMode", "rtx.volumetrics.fogIntegrationPixelStride",
                "rtx.volumetrics.initialRISSampleCount", "rtx.volumetrics.spatialReuseMaxSampleCount",
                "rtx.volumetrics.restirGridScale", "rtx.volumetrics.restirFroxelDepthSlices", "rtx.risLightSampleCount",
                "rtx.displacement.mode", "rtx.displacement.maxIterations", "rtx.di.initialSampleCount",
                "rtx.pathMaxBounces", "rtx.enablePSRR", "rtx.enablePSTR", "rtx.upscalerType",
                "rtx.qualityDLSS", "rtx.enableRayReconstruction", "rtx.dlfg.enable")) {
            values.put(key, read(key));
        }
        return values;
    }

    private static void verifyNaturalMaterials(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runOnServer(server -> RemixSceneFixtures.commands(server,
                "fill -80 -61 -8 -48 -61 35 minecraft:stone_bricks",
                "fill -80 -61 4 -48 -61 11 minecraft:grass_block",
                "fill -80 -61 12 -48 -61 19 minecraft:dirt",
                "fill -80 -61 20 -48 -61 27 minecraft:mud",
                "fill -80 -61 28 -48 -61 35 minecraft:sand",
                "time set 3000", "tp @a -64.5 -60 0.5 0 25"));
        context.runOnClient(client -> RtxQualityPresets.apply(RtxRemixMod.getRtxSettings(), QualityPreset.HIGH));
        boolean original = context.computeOnClient(client -> BlockTextureManager.get().isAutoPbr());
        try {
            for (boolean enabled : new boolean[]{false, true}) {
                setAutomaticMaterials(context, enabled);
                waitFrames(context, 32);
                RemixWorldGameTest.requireVisibleWorld(RemixWorldGameTest.captureCompletedFrame(context,
                        "natural-materials-auto-" + enabled));
            }
            try (var view = RuntimeOptionScope.acquire(context, "rtx.debugView.debugViewIdx", "89");
                 var replace = RuntimeOptionScope.acquire(context, "rtx.debugView.replaceCompositeOutput", "true")) {
                waitFrames(context, 8);
                RemixWorldGameTest.captureCompletedFrame(context, "natural-materials-perceptual-roughness");
            }
        } finally {
            setAutomaticMaterials(context, original);
        }
    }

    private static void setAutomaticMaterials(ClientGameTestContext context, boolean enabled) {
        if (context.computeOnClient(client -> BlockTextureManager.get().isAutoPbr() == enabled)) return;
        long previous = context.computeOnClient(client -> BlockTextureManager.get().getRoughnessAtlasTexture().getHandle());
        context.runOnClient(client -> {
            var textures = BlockTextureManager.get();
            textures.setAutoPbr(enabled);
            if (textures.commitMaterialSettings()) RtxRemixMod.rebuildTerrainForMaterialSettings();
        });
        context.waitFor(client -> BlockTextureManager.get().isInitialized()
                && BlockTextureManager.get().getRoughnessAtlasTexture().getHandle() != previous, 400);
        context.waitFor(client -> {
            var terrain = RtxRemixMod.renderer.getChunkManager().getTerrainDiagnostics();
            return terrain.columnsPublished() > 0 && terrain.pendingBuilds() == 0 && terrain.readyMeshes() == 0;
        }, 400);
    }

    private static void verifyFogQuality(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runOnServer(server -> RemixSceneFixtures.commands(server,
                "fill 64 -61 -8 96 -61 40 minecraft:stone_bricks",
                "fill 64 -60 -8 64 -45 40 minecraft:stone_bricks",
                "fill 96 -60 -8 96 -45 40 minecraft:stone_bricks",
                "fill 64 -60 40 96 -45 40 minecraft:stone_bricks",
                "fill 64 -44 -8 96 -44 40 minecraft:stone_bricks",
                "fill 77 -44 12 84 -44 20 minecraft:air",
                "time set 4000", "tp @a 80.5 -60 0.5 0 -8"));
        try (var color = RuntimeOptionScope.acquire(context, "rtx.volumetrics.transmittanceColor", "0.94, 0.94, 0.94");
             var distance = RuntimeOptionScope.acquire(context, "rtx.volumetrics.transmittanceMeasurementDistanceMeters", "80");
             var height = RuntimeOptionScope.acquire(context, "rtx.volumetrics.heightFogBaseHeightMeters", "-60");
             var falloff = RuntimeOptionScope.acquire(context, "rtx.volumetrics.heightFogFalloffMeters", "32")) {
            for (var preset : new QualityPreset[]{QualityPreset.ULTRA, QualityPreset.HIGH, QualityPreset.MEDIUM, QualityPreset.LOW}) {
                context.runOnClient(client -> RtxQualityPresets.apply(RtxRemixMod.getRtxSettings(), preset));
                waitFrames(context, 48);
                RemixWorldGameTest.requireVisibleWorld(RemixWorldGameTest.captureCompletedFrame(context,
                        "quality-fog-" + preset.id()));
            }
        }
    }

    private static void verifyWaterMiss(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runOnServer(server -> RemixSceneFixtures.commands(server,
                "fill -28 -64 4 28 -64 60 minecraft:air"));
        for (var preset : new QualityPreset[]{QualityPreset.ULTRA, QualityPreset.HIGH}) {
            context.runOnClient(client -> RtxQualityPresets.apply(RtxRemixMod.getRtxSettings(), preset));
            waitFrames(context, 32);
            requireWaterTransmission(RemixWorldGameTest.captureCompletedFrame(context,
                    "quality-water-open-bottom-" + preset.id()));
        }
        world.getServer().runOnServer(server -> RemixSceneFixtures.commands(server,
                "fill -28 -64 4 28 -64 60 minecraft:sandstone"));
        context.runOnClient(client -> RtxQualityPresets.apply(RtxRemixMod.getRtxSettings(), QualityPreset.ULTRA));
        waitFrames(context, 32);
        requireWaterTransmission(RemixWorldGameTest.captureCompletedFrame(context, "quality-water-closed-bottom"));
    }

    private static void requireWaterTransmission(Path path) {
        try {
            var image = ImageIO.read(path.toFile());
            long red = 0;
            long green = 0;
            long pixels = 0;
            for (int y = image.getHeight() * 2 / 3; y < image.getHeight() * 3 / 4; y++) {
                for (int x = image.getWidth() * 2 / 5; x < image.getWidth() * 3 / 5; x++) {
                    int color = image.getRGB(x, y);
                    red += (color >>> 16) & 255;
                    green += (color >>> 8) & 255;
                    pixels++;
                }
            }
            if (green - red < pixels * 10) {
                throw new AssertionError("Water transmission was replaced by an untinted background: " + path
                        + " (mean red=" + (double) red / pixels + ", green=" + (double) green / pixels + ")");
            }
        } catch (IOException e) {
            throw new AssertionError("Cannot inspect water transmission: " + path, e);
        }
    }

    private static void waitFrames(ClientGameTestContext context, int frames) {
        long start = context.computeOnClient(client -> RemixFrameProbe.presents);
        context.waitFor(client -> RemixFrameProbe.presents >= start + frames, 400);
    }

    private static String read(String key) { return RtxRemixMod.renderer.getRemixApi().getConfigVariableResolved(key); }
}
