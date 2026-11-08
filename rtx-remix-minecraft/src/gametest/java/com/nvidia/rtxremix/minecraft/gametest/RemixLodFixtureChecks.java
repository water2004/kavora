/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gametest;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.UnfocusedFramerateLimit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Measures first-view and repeated turns in a copied, already explored world and voxel cache. */
final class RemixLodFixtureChecks {
    private static final Logger LOGGER = LoggerFactory.getLogger(RemixLodFixtureChecks.class);

    private RemixLodFixtureChecks() {}

    static void run(ClientGameTestContext context, String fixture) {
        var position = savedPosition(context.computeOnClient(client ->
                client.getLevelSource().getLevelPath(fixture)));
        context.runOnClient(client -> client.createWorldOpenFlows().openWorld(fixture,
                () -> { throw new AssertionError("LOD fixture load cancelled"); }));
        context.waitFor(client -> client.level != null && client.player != null
                && client.getSingleplayerServer() != null && client.gui.screen() == null, 2400);
        var setup = context.computeOnClient(client -> {
            var server = client.getSingleplayerServer();
            return CompletableFuture.runAsync(() -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                var level = server.overworld();
                int x = (int) Math.floor(position[0]), z = (int) Math.floor(position[1]);
                int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                player.setGameMode(GameType.CREATIVE);
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                if (!player.teleportTo(level, position[0], surface + 24.0, position[1],
                        Set.of(), 0, 12, true)) throw new AssertionError("LOD fixture camera move failed");
                LOGGER.info("LOD fixture above surface: x={}, z={}, surfaceY={}", position[0], position[1], surface);
            }, server);
        });
        context.waitFor(client -> setup.isDone(), 400);
        setup.join();
        context.waitFor(client -> RtxRemixMod.renderer.isSceneReady(), 2400);
        int limit = context.computeOnClient(client -> UnfocusedFramerateLimit.get());
        context.runOnClient(client -> UnfocusedFramerateLimit.set(UnfocusedFramerateLimit.UNLIMITED));
        try (Recording recording = recording()) {
            for (int pass = 0; pass < 2; pass++) {
                for (int angle = 0; angle < 4; angle++) {
                    float yaw = angle * 90f;
                    context.runOnClient(client -> {
                        client.player.setYRot(yaw);
                        client.player.setXRot(12);
                    });
                    long start = System.nanoTime();
                    long frame = context.computeOnClient(client -> RemixFrameProbe.presents);
                    context.waitFor(client -> RemixFrameProbe.presents >= frame + 3, 400);
                    String name = "lod-turn-" + pass + "-" + angle;
                    log(context, name + "-first");
                    boolean refined = false;
                    for (int sample = 0; sample < 120; sample++) {
                        refined = context.computeOnClient(client ->
                                RtxRemixMod.renderer.getChunkManager().isFarViewRefined());
                        if (refined) break;
                        context.waitTicks(1);
                    }
                    LOGGER.info("LOD fixture turn result: name={}, refined={}, elapsedMs={}",
                            name, refined, (System.nanoTime() - start) * 1e-6);
                    log(context, name + "-settled");
                    RemixWorldGameTest.captureCompletedFrame(context, name);
                }
            }
            if (recording != null) recording.stop();
        } finally {
            context.runOnClient(client -> UnfocusedFramerateLimit.set(limit));
            var server = context.computeOnClient(client -> client.getSingleplayerServer());
            context.runOnClient(client -> {
                var message = Component.translatable("menu.savingLevel");
                client.level.disconnect(message);
                client.disconnect(new GenericMessageScreen(message), false);
            });
            context.waitFor(client -> !server.getRunningThread().isAlive(), 2400);
            context.setScreen(TitleScreen::new);
        }
    }

    private static void log(ClientGameTestContext context, String phase) {
        context.runOnClient(client -> LOGGER.info("LOD fixture {}: camera={}, {}", phase,
                client.player.position(), RtxRemixMod.renderer.getChunkManager().getLodHandoffDiagnostics()));
    }

    private static double[] savedPosition(Path world) {
        try {
            var root = NbtIo.readCompressed(world.resolve(LevelResource.LEVEL_DATA_FILE.id()), NbtAccounter.unlimitedHeap());
            var data = root.getCompound("Data").orElseThrow();
            var player = data.getCompound("Player").orElse(null);
            if (player == null) {
                try (var files = Files.walk(world.resolve(LevelResource.PLAYER_DATA_DIR.id()))) {
                    var path = files.filter(p -> p.toString().endsWith(".dat"))
                            .max(java.util.Comparator.comparingLong(p -> p.toFile().lastModified())).orElseThrow();
                    player = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
                }
            }
            var pos = player.getList("Pos").orElseThrow();
            return new double[] {pos.getDouble(0).orElseThrow(), pos.getDouble(2).orElseThrow()};
        } catch (IOException error) {
            throw new AssertionError("Cannot read LOD fixture position", error);
        }
    }

    private static Recording recording() {
        String path = System.getProperty("rtxremix.gametest.streamingJfr");
        if (path == null) return null;
        try {
            Recording recording = new Recording(Configuration.getConfiguration("profile"));
            recording.setDestination(Path.of(path));
            recording.setDumpOnExit(true);
            recording.start();
            return recording;
        } catch (IOException | java.text.ParseException error) {
            throw new AssertionError("Cannot record LOD fixture", error);
        }
    }
}
