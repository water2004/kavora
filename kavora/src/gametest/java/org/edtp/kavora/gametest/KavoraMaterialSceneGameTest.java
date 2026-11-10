/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.gametest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import javax.imageio.ImageIO;
import org.edtp.kavora.KavoraMod;
import org.edtp.kavora.render.RenderBackend;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;

/** Fixed camera views exercise the same geometry under sunlight, water, weather and emitters. */
public final class KavoraMaterialSceneGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        if (Boolean.getBoolean("kavora.gametest.nightSkyOnly")) return;
        if (Boolean.getBoolean("kavora.gametest.dimensionColourOnly")) return;
        if (Boolean.getBoolean("kavora.gametest.cloudsOnly")) return;
        if (Boolean.getBoolean("kavora.gametest.settingsOnly")
                || Boolean.getBoolean("kavora.gametest.entityMaterialsOnly")
                || Boolean.getBoolean("kavora.gametest.qualityVisualOnly")
                || Boolean.getBoolean("kavora.gametest.terrainReloadOnly")
                || Boolean.getBoolean("kavora.gametest.particleOnly")
                || Boolean.getBoolean("kavora.gametest.streamingOnly")) return;
        boolean hudWasHidden = context.computeOnClient(client -> client.gui.hud.isHidden());
        int width = Integer.getInteger("kavora.gametest.width", 0);
        int height = Integer.getInteger("kavora.gametest.height", 2160);
        if (width > 0) {
            context.getInput().resizeWindow(width, height);
            context.waitFor(client -> client.getWindow().getWidth() == width && client.getWindow().getHeight() == height, 200);
        }
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            if (Boolean.getBoolean("kavora.gametest.retainedOnly")) {
                KavoraWorldGameTest.assertWorldRenders(context, world, "retained-fixture");
                if (System.getProperty("kavora.gametest.textureMipFixtures") != null) {
                    KavoraTextureMipChecks.run(context);
                    return;
                }
                context.runOnClient(client -> {
                    if (!client.gui.hud.isHidden()) client.gui.hud.toggle();
                });
                KavoraRetainedSceneChecks.run(context, world);
                return;
            }
            if (Boolean.getBoolean("kavora.gametest.beaconOnly")) {
                checkVanillaBeacon(context, world);
                return;
            }
            if (Boolean.getBoolean("kavora.gametest.stainedGlassOnly")) {
                checkStainedGlass(context, world);
                return;
            }
            if (Boolean.getBoolean("kavora.gametest.lightingOnly")) {
                if (Boolean.getBoolean("kavora.gametest.atmosphereScenes")) KavoraAtmosphereChecks.run(context, world);
                else KavoraLightingChecks.run(context, world);
                return;
            }
            context.runOnClient(client -> {
                if (!client.gui.hud.isHidden()) client.gui.hud.toggle();
                var state = net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState();
                if (client.getBlockColors().getTintSource(state, 0) != null) {
                    throw new AssertionError("The missing-tint fixture unexpectedly has a tint source");
                }
                var parts = new java.util.ArrayList<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart>();
                client.getModelManager().getBlockStateModelSet().get(state)
                        .collectParts(net.minecraft.util.RandomSource.create(0), parts);
                if (parts.isEmpty() || parts.getFirst().getQuads(net.minecraft.core.Direction.UP).isEmpty()
                        || parts.getFirst().getQuads(net.minecraft.core.Direction.UP).getFirst().materialInfo().tintIndex() != 0) {
                    throw new AssertionError("The missing-tint model fixture did not load");
                }
            });
            world.getServer().runOnServer(KavoraSceneFixtures::buildGallery);
            pose(world, 0, -60, -12, 0, 12);
            context.waitTicks(100);
            assertStoneFloor(capture(context, world, "materials-day"));
            assertTerrainVisibility(context);
            assertReliefFiltering(context, world);
            assertRadianceCacheModes(context, world);
            KavoraRetainedSceneChecks.run(context, world);

            pose(world, -11, -62.5, 0, 0, 0);
            context.waitTicks(30);
            capture(context, world, "materials-underwater");

            pose(world, -11, -59, 0, 0, 35);
            context.waitTicks(30);
            capture(context, world, "materials-foliage-refraction");

            world.getServer().runOnServer(server -> KavoraSceneFixtures.commands(server, "time set 18000"));
            pose(world, 0, -60, 17, 0, 6);
            context.waitTicks(60);
            capture(context, world, "materials-emission-night");

            world.getServer().runOnServer(server -> KavoraSceneFixtures.commands(server,
                    "setblock -10 -60 24 minecraft:air",
                    "setblock -5 -60 24 minecraft:air",
                    "setblock 5 -60 24 minecraft:air",
                    "setblock 10 -60 24 minecraft:air"));
            context.waitTicks(60);
            capture(context, world, "materials-glowstone-only");

            world.getServer().runOnServer(server -> KavoraSceneFixtures.commands(server,
                    "setblock 12 -60 27 kavora:copper_beacon"));
            pose(world, 12, -60, 17, 0, 6);
            context.waitTicks(60);
            capture(context, world, "materials-beacon");

            world.getServer().runOnServer(server -> KavoraSceneFixtures.commands(server,
                    "fill 19 -61 7 21 -61 9 minecraft:iron_block",
                    "setblock 20 -60 8 minecraft:beacon",
                    "setblock 20 -56 8 minecraft:red_stained_glass"));
            pose(world, 20, -60, 0, 0, -25);
            context.waitTicks(100);
            assertContinuousBeacon(capture(context, world, "materials-vanilla-beacon-night"));

            world.getServer().runOnServer(server -> KavoraSceneFixtures.commands(server, "time set 6000"));
            context.waitTicks(60);
            capture(context, world, "materials-vanilla-beacon-day");
            context.runOnClient(client -> KavoraWorldGameTest.require(
                    KavoraMod.renderer.getInteropCopiedSnapshotBytes() == 0,
                    "Dry world copied unused interop snapshots"));

            world.getServer().runOnServer(server -> KavoraSceneFixtures.commands(server,
                    "weather rain"));
            pose(world, 0, -60, -12, 0, 12);
            context.waitTicks(120);
            capture(context, world, "materials-rain");
            context.runOnClient(client -> {
                var renderer = KavoraMod.renderer;
                KavoraWorldGameTest.require(renderer.getWeatherRenderer().getLastDrawCount() > 0,
                        "Rain fixture did not draw precipitation");
                KavoraWorldGameTest.require(renderer.getInteropCopiedSnapshotBytes() > 0,
                        "Refractive precipitation did not acquire its sampled color and depth");
            });
        } finally {
            context.runOnClient(client -> {
                if (client.gui.hud.isHidden() != hudWasHidden) client.gui.hud.toggle();
            });
        }
    }

    private static void checkStainedGlass(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runOnServer(server -> {
            var rules = server.getGameRules();
            rules.set(GameRules.ADVANCE_TIME, false, server);
            rules.set(GameRules.SPAWN_MOBS, false, server);
            for (var mob : server.overworld().getEntitiesOfClass(Mob.class, new AABB(-14, -64, -14, 14, -50, 10))) {
                mob.discard();
            }
            KavoraSceneFixtures.commands(server,
                    "gamemode creative @a", "time set 6000", "weather clear",
                    "fill -10 -61 -8 10 -61 6 minecraft:smooth_quartz",
                    "fill -10 -60 4 10 -54 4 minecraft:white_concrete",
                    "fill -9 -60 1 -7 -54 1 minecraft:glass",
                    "fill -6 -60 1 -4 -54 1 minecraft:red_stained_glass",
                    "fill -3 -60 1 -1 -54 1 minecraft:red_stained_glass_pane",
                    "fill 0 -60 1 2 -54 1 minecraft:blue_stained_glass_pane",
                    "fill 3 -60 1 5 -54 1 minecraft:blue_stained_glass",
                    "fill 6 -60 1 8 -54 1 minecraft:glass_pane");
        });
        context.runOnClient(client -> { if (!client.gui.hud.isHidden()) client.gui.hud.toggle(); });
        pose(world, -4.5, -58.5, -5.0, 0, 0);
        Path redBlock = capture(context, world, "glass-red-block");
        Path redPane = captureGlassPanel(context, world, -1.5, "glass-red-pane");
        Path bluePane = captureGlassPanel(context, world, 1.5, "glass-blue-pane");
        Path blueBlock = captureGlassPanel(context, world, 4.5, "glass-blue-block");
        double clearBlockTint = glassTint(captureGlassPanel(context, world, -7.5, "glass-clear-block"));
        double clearPaneTint = glassTint(captureGlassPanel(context, world, 7.5, "glass-clear-pane"));

        double redBlockTint = glassTint(redBlock) - clearBlockTint;
        double redPaneTint = glassTint(redPane) - clearPaneTint;
        double bluePaneTint = clearPaneTint - glassTint(bluePane);
        double blueBlockTint = clearBlockTint - glassTint(blueBlock);
        if (redPaneTint < 0.02 || bluePaneTint < 0.02
                || redBlockTint < 0.02 || blueBlockTint < 0.02) {
            throw new AssertionError("Incorrect glass transmission (red block, red pane, blue pane, blue block) "
                    + redBlockTint + ", " + redPaneTint + ", " + bluePaneTint + ", " + blueBlockTint);
        }
    }

    private static Path captureGlassPanel(ClientGameTestContext context, TestSingleplayerContext world,
                                          double x, String name) {
        pose(world, x, -58.5, -5.0, 0, 0);
        long presents = context.computeOnClient(client -> KavoraFrameProbe.presents);
        context.waitFor(client -> KavoraFrameProbe.presents >= presents + 40, 2400);
        return KavoraWorldGameTest.captureCompletedFrame(context, name + "-vulkan");
    }

    private static double glassTint(Path path) {
        try {
            var image = ImageIO.read(path.toFile());
            long red = 0, green = 0, blue = 0;
            int width = image.getWidth(), height = image.getHeight();
            for (int y = height * 47 / 100; y < height * 53 / 100; y++) {
                for (int x = width * 47 / 100; x < width * 53 / 100; x++) {
                    int rgb = image.getRGB(x, y);
                    red += (rgb >>> 16) & 255;
                    green += (rgb >>> 8) & 255;
                    blue += rgb & 255;
                }
            }
            return (double) (red - blue) / Math.max(1, red + green + blue);
        } catch (IOException error) {
            throw new AssertionError("Cannot inspect stained glass screenshot " + path, error);
        }
    }

    private static void checkVanillaBeacon(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runOnServer(server -> KavoraSceneFixtures.commands(server,
                "gamemode creative @a", "time set 18000",
                "fill 17 -61 4 23 -61 11 minecraft:stone_bricks",
                "fill 19 -61 7 21 -61 9 minecraft:iron_block",
                "setblock 20 -60 8 minecraft:beacon",
                "setblock 20 -56 8 minecraft:red_stained_glass"));
        context.runOnClient(client -> { if (!client.gui.hud.isHidden()) client.gui.hud.toggle(); });
        pose(world, 20, -60, 0, 0, -25);
        context.waitTicks(100);
        assertContinuousBeacon(capture(context, world, "beacon-filtered-night"));
        world.getServer().runOnServer(server -> KavoraSceneFixtures.commands(server,
                "setblock 20 -56 8 minecraft:air"));
        context.waitTicks(100);
        assertContinuousBeacon(capture(context, world, "beacon-unfiltered-night"));
        world.getServer().runOnServer(server -> KavoraSceneFixtures.commands(server,
                "setblock 20 -56 8 minecraft:red_stained_glass", "time set 6000"));
        context.waitTicks(100);
        capture(context, world, "beacon-filtered-day");
    }

    /** The night fixture points along the beam through its white-to-red glass transition. */
    private static void assertContinuousBeacon(java.nio.file.Path screenshot) {
        try {
            var image = javax.imageio.ImageIO.read(screenshot.toFile());
            int longestGap = 0, gap = 0;
            for (int y = 0; y < image.getHeight() * 4 / 5; y++) {
                boolean lit = false;
                for (int x = image.getWidth() * 43 / 100; x < image.getWidth() * 53 / 100; x++) {
                    int rgb = image.getRGB(x, y);
                    int r = rgb >>> 16 & 255, g = rgb >>> 8 & 255, b = rgb & 255;
                    if (Math.max(r, Math.max(g, b)) > 200 && r + g + b > 300) { lit = true; break; }
                }
                gap = lit ? 0 : gap + 1;
                longestGap = Math.max(longestGap, gap);
            }
            if (longestGap > Math.max(2, image.getHeight() / 80)) {
                throw new AssertionError("Beacon has a dark interruption of " + longestGap + " rows: " + screenshot);
            }
        } catch (java.io.IOException e) {
            throw new AssertionError("Cannot inspect beacon screenshot", e);
        }
    }

    private static void pose(TestSingleplayerContext world, double x, double y, double z,
                             float yaw, float pitch) {
        world.getServer().runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            if (!player.teleportTo(server.overworld(), x, y, z, Set.of(), yaw, pitch, true)) {
                throw new AssertionError("Scene camera teleport failed");
            }
        });
    }

    private static void assertRadianceCacheModes(ClientGameTestContext context, TestSingleplayerContext world) {
        String key = "rtx.integrateIndirectMode";
        String presetKey = "rtx.graphicsPreset";
        String previousPreset = context.computeOnClient(client ->
                org.edtp.kavora.KavoraMod.renderer.getRemixApi().getConfigVariableResolved(presetKey));
        String previous = context.computeOnClient(client ->
                org.edtp.kavora.KavoraMod.renderer.getRemixApi().getConfigVariableResolved(key));
        pose(world, -10, -59, 5, 0, 0);
        try {
            // Quality presets own the cache mode until Custom migrates their options to User Settings.
            context.runOnClient(client ->
                    org.edtp.kavora.KavoraMod.renderer.getRemixApi().setConfigVariable(presetKey, "4"));
            context.waitFor(client -> "4".equals(
                    org.edtp.kavora.KavoraMod.renderer.getRemixApi().getConfigVariableResolved(presetKey)), 100);
            for (int mode : new int[] { 3, 2 }) {
                String requested = Integer.toString(mode);
                context.runOnClient(client ->
                        org.edtp.kavora.KavoraMod.renderer.getRemixApi().setConfigVariable(key, requested));
                context.waitFor(client -> requested.equals(
                        org.edtp.kavora.KavoraMod.renderer.getRemixApi().getConfigVariableResolved(key)), 100);
                if (mode == 2) {
                    context.waitFor(client -> KavoraMod.renderer.getRemixApi()
                            .getNeuralRadianceCacheTrainingRecords() > 0, 100);
                }
                context.waitTicks(60);
                capture(context, world, mode == 3 ? "materials-foliage-sharc" : "materials-foliage-nrc");
                context.runOnClient(client -> {
                    var api = KavoraMod.renderer.getRemixApi();
                    KavoraWorldGameTest.require(requested.equals(api.getConfigVariableResolved(key)),
                            "Radiance cache mode changed while rendering: " + requested);
                    if (mode == 2) {
                        KavoraWorldGameTest.require(api.getNeuralRadianceCacheTrainingRecords() > 0,
                                "NRC screenshot rendered without an active training context");
                    }
                });
            }
        } finally {
            context.runOnClient(client ->
                    org.edtp.kavora.KavoraMod.renderer.getRemixApi().setConfigVariable(key, previous));
            context.waitFor(client -> previous.equals(
                    org.edtp.kavora.KavoraMod.renderer.getRemixApi().getConfigVariableResolved(key)), 100);
            context.runOnClient(client ->
                    org.edtp.kavora.KavoraMod.renderer.getRemixApi().setConfigVariable(presetKey, previousPreset));
            context.waitFor(client -> previousPreset.equals(
                    org.edtp.kavora.KavoraMod.renderer.getRemixApi().getConfigVariableResolved(presetKey)), 100);
        }
    }

    private static void assertReliefFiltering(ClientGameTestContext context, TestSingleplayerContext world) {
        String key = "rtx.materials.smoothReliefMapFiltering";
        String previous = context.computeOnClient(client ->
                org.edtp.kavora.KavoraMod.renderer.getRemixApi().getConfigVariableResolved(key));
        try {
            context.runOnClient(client ->
                    org.edtp.kavora.KavoraMod.renderer.getRemixApi().setConfigVariable(key, "True"));
            context.waitFor(client -> Boolean.parseBoolean(
                    org.edtp.kavora.KavoraMod.renderer.getRemixApi().getConfigVariableResolved(key)), 100);
            context.waitTicks(30);
            capture(context, world, "materials-relief-smooth");
            context.runOnClient(client ->
                    org.edtp.kavora.KavoraMod.renderer.getRemixApi().setConfigVariable(key, "False"));
            context.waitFor(client -> !Boolean.parseBoolean(
                    org.edtp.kavora.KavoraMod.renderer.getRemixApi().getConfigVariableResolved(key)), 100);
            context.waitTicks(30);
            capture(context, world, "materials-relief-point");
        } finally {
            context.runOnClient(client ->
                    org.edtp.kavora.KavoraMod.renderer.getRemixApi().setConfigVariable(key, previous));
        }
    }

    private static void assertTerrainVisibility(ClientGameTestContext context) {
        context.runOnClient(client -> {
            if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium")) return;
            var slots = (org.edtp.kavora.terrain.visibility.BuiltChunkStorageSectionAccess)
                    client.levelRenderer.viewArea();
            var section = slots.kavora$section(net.minecraft.core.SectionPos.asLong(0, -4, 0));
            if (section == null || section.getSectionMesh() == net.minecraft.client.renderer.chunk.CompiledSectionMesh.UNCOMPILED
                    || section.getSectionMesh().hasRenderableLayers()
                    || section.getSectionMesh().facesCanSeeEachother(net.minecraft.core.Direction.DOWN, net.minecraft.core.Direction.UP)) {
                throw new AssertionError("Captured floor lost vanilla occlusion or retained duplicate raster geometry");
            }
        });
    }

    private static void assertStoneFloor(java.nio.file.Path screenshot) {
        try {
            var image = javax.imageio.ImageIO.read(screenshot.toFile());
            int samples = 0, neutral = 0, edges = 0;
            int edgeStep = Math.max(1, image.getWidth() * 4 / 854);
            // The fixed gallery pose fills this region with stone bricks, below the horizon and props.
            for (int y = image.getHeight() * 2 / 3; y < image.getHeight() * 9 / 10; y++) {
                for (int x = image.getWidth() / 8; x < image.getWidth() * 7 / 8 - edgeStep; x++) {
                    int pixel = image.getRGB(x, y);
                    int next = image.getRGB(x + edgeStep, y);
                    int r = (pixel >>> 16) & 255, g = (pixel >>> 8) & 255, b = pixel & 255;
                    int nextSum = ((next >>> 16) & 255) + ((next >>> 8) & 255) + (next & 255);
                    samples++;
                    if (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 30 && r + g + b > 60) neutral++;
                    if (Math.abs(r + g + b - nextSum) > 36) edges++;
                }
            }
            if (neutral < samples * 0.7 || edges < samples * 0.05) {
                throw new AssertionError("The gallery floor is absent or untextured: " + screenshot
                        + " (neutral=" + neutral + ", edges=" + edges + ", samples=" + samples + ")");
            }
        } catch (java.io.IOException e) {
            throw new AssertionError("Cannot inspect gallery floor: " + screenshot, e);
        }
    }

    private static java.nio.file.Path capture(ClientGameTestContext context, TestSingleplayerContext world, String name) {
        context.waitFor(client -> {
            var terrain = org.edtp.kavora.KavoraMod.renderer.getChunkManager().getTerrainDiagnostics();
            return client.levelRenderer.sectionRenderDispatcher().isQueueEmpty()
                    && terrain.columnsMissingGeometry() == 0 && terrain.pendingBuilds() == 0
                    && terrain.readyMeshes() == 0 && terrain.captureQueue() == 0 && terrain.pendingMeshBytes() == 0;
        }, 2400);
        context.runOnClient(client -> client.gui.toastManager().clear());
        String backend = context.computeOnClient(client -> RenderBackend.current().name().toLowerCase(java.util.Locale.ROOT));
        return KavoraWorldGameTest.assertWorldRenders(context, world, name + "-" + backend);
    }
}
