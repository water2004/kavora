/* SPDX-License-Identifier: Apache-2.0 */
package org.edtp.kavora.gametest;

import org.edtp.kavora.KavoraMod;
import org.edtp.kavora.UnfocusedFramerateLimit;
import org.edtp.kavora.light.BlockLightRuleManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A closed white room measures illumination separately from the visible emissive texels. */
final class KavoraNightIlluminationChecks {
    private static final Logger LOGGER = LoggerFactory.getLogger(KavoraNightIlluminationChecks.class);

    static void run(ClientGameTestContext context, TestSingleplayerContext world) {
        int limit = context.computeOnClient(client -> UnfocusedFramerateLimit.get());
        try {
            context.runOnClient(client -> UnfocusedFramerateLimit.set(UnfocusedFramerateLimit.UNLIMITED));
            if (Boolean.getBoolean("kavora.gametest.blockSources")) {
                boolean hidden = context.computeOnClient(client -> client.gui.hud.isHidden());
                if (!hidden) context.runOnClient(client -> client.gui.hud.toggle());
                try {
                    captureBlockSources(context, world);
                } finally {
                    if (!hidden) context.runOnClient(client -> client.gui.hud.toggle());
                }
                return;
            }
            inventory(context);
            world.getServer().runOnServer(server -> {
                server.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
                server.getGameRules().set(GameRules.ADVANCE_WEATHER, false, server);
                server.getGameRules().set(GameRules.BLOCK_DROPS, false, server);
                server.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 0, server);
                KavoraSceneFixtures.commands(server, "time set 18000", "weather clear",
                        "fill -7 -61 -1 7 -54 13 minecraft:white_concrete hollow");
                var player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.CREATIVE);
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                player.teleportTo(server.overworld(), .5, -60, 1.5, Set.of(), 0, 6, true);
            });
            String[] names = {"torch", "lantern", "copper-lantern", "glowstone", "glow-berries", "glow-lichen", "furnace"};
            String[] blocks = {"torch", "lantern", "copper_lantern", "glowstone", "cave_vines[berries=true]",
                    "glow_lichen[up=true]", "furnace[lit=true,facing=north]"};
            for (int i = 0; i < blocks.length; i++) {
                String block = blocks[i];
                world.getServer().runOnServer(server -> {
                    server.overworld().setBlock(new BlockPos(0, -59, 7), Blocks.AIR.defaultBlockState(), 3);
                    for (var pos : BlockPos.betweenClosed(-2, -60, 6, 2, -58, 8))
                        server.overworld().setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                    KavoraSceneFixtures.commands(server, "setblock 0 -60 7 minecraft:stone",
                            "setblock 0 -58 7 minecraft:stone", "setblock 0 -59 7 minecraft:" + block);
                    if (server.overworld().getBlockEntity(new BlockPos(0, -59, 7))
                            instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity furnace) {
                        furnace.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_ORE, 64));
                        furnace.setItem(1, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COAL, 64));
                    }
                });
                if (i == 0) KavoraWorldGameTest.assertWorldRenders(context, world, "night-room");
                long frame = context.computeOnClient(client -> KavoraFrameProbe.presents);
                context.waitFor(client -> KavoraFrameProbe.presents >= frame + 48, 1600);
                KavoraWorldGameTest.captureCompletedFrame(context, "night-emitter-" + names[i]);
                try (var ignored = RuntimeOptionScope.acquire(context, "rtx.enableDirectLighting", "False")) {
                    long target = context.computeOnClient(client -> KavoraFrameProbe.presents) + 48;
                    context.waitFor(client -> KavoraFrameProbe.presents >= target, 1600);
                    KavoraWorldGameTest.captureCompletedFrame(context, "night-emitter-" + names[i] + "-no-direct");
                }
            }
        } finally {
            context.runOnClient(client -> UnfocusedFramerateLimit.set(limit));
        }
    }

    private static void captureBlockSources(ClientGameTestContext context, TestSingleplayerContext world) {
        KavoraWorldGameTest.assertWorldRenders(context, world, "block-sources");
        world.getServer().runOnServer(server -> {
            server.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
            server.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
            KavoraSceneFixtures.commands(server, "time set 18000", "weather clear",
                    "fill -7 -61 -1 7 -54 13 minecraft:white_concrete hollow");
            var player = server.getPlayerList().getPlayers().getFirst();
            player.setGameMode(GameType.CREATIVE);
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            player.teleportTo(server.overworld(), .5, -60, 1.5, Set.of(), 0, 6, true);
        });
        for (String[] source : new String[][] {{"empty", "air"}, {"invisible", "light[level=15]"},
                {"zero", "light[level=0]"}, {"beacon", "beacon"}}) {
            if (!source[1].equals("air")) {
                world.getServer().runOnServer(server -> KavoraSceneFixtures.commands(server,
                        "setblock 0 -59 7 minecraft:" + source[1]));
            }
            waitForFrames(context, 32);
            var lights = context.computeOnClient(client -> KavoraMod.renderer.getChunkManager().lightStatsByRule());
            LOGGER.info("Block source {}: {}", source[0], lights);
            int drawn = lights.stream().mapToInt(org.edtp.kavora.terrain.ChunkManager.LightRuleStats::drawn).sum();
            boolean lit = source[0].equals("invisible") || source[0].equals("beacon");
            KavoraWorldGameTest.require((drawn > 0) == lit, "Wrong near-light submission for " + source[0]);
            KavoraWorldGameTest.captureCompletedFrame(context, "block-source-" + source[0]);
        }
        try (var debug = RuntimeOptionScope.acquire(context, "rtx.debugView.debugViewIdx", "30")) {
            waitForFrames(context, 8);
            Path emission = KavoraWorldGameTest.captureCompletedFrame(context, "beacon-core-emission");
            double core = sampleValue(emission, 0.5, 0.445);
            LOGGER.info("Beacon core emitted radiance sample: {}", core);
            KavoraWorldGameTest.require(core > 30, "Beacon core has no emitted radiance");
        }
        if (Boolean.getBoolean("kavora.gametest.farBlockSources")) captureFarBlockSource(context, world);
    }

    private static void captureFarBlockSource(ClientGameTestContext context, TestSingleplayerContext world) {
        int distance = context.computeOnClient(client -> client.options.renderDistance().get());
        context.runOnClient(client -> client.options.renderDistance().set(2));
        try {
            world.getServer().runOnServer(server -> {
                server.getPlayerList().setViewDistance(2);
                server.getPlayerList().getPlayers().getFirst()
                        .teleportTo(server.overworld(), .5, 94.88, 8.5, Set.of(), 0, 0, true);
            });
            context.waitFor(client -> client.player.getY() > 90
                    && client.level.getChunkSource().getChunk(0, 1, false) != null
                    && client.level.getChunkSource().getChunk(-1, 1, false) != null, 600);
            world.getServer().runOnServer(server -> KavoraSceneFixtures.commands(server,
                    "fill -5 91 17 5 102 17 minecraft:white_concrete",
                    "fill -5 95 12 5 95 15 minecraft:white_concrete", "setblock 0 96 14 minecraft:light[level=15]"));
            context.waitFor(client -> client.level.getBlockState(new BlockPos(0, 96, 17)).is(Blocks.CONCRETE.white())
                    && KavoraMod.renderer.getChunkManager().hasTerrainGeometry(0, 1)
                    && KavoraMod.renderer.getChunkManager()
                    .lightStatsByRule().stream().anyMatch(rule -> rule.drawn() == 1
                            && rule.ruleId().getPath().endsWith("/light.json")), 600);
            world.getServer().runOnServer(server -> server.getPlayerList().getPlayers().getFirst()
                    .teleportTo(server.overworld(), .5, 94.88, -113.5, Set.of(), 0, 0, true));
            try {
                context.waitFor(client -> client.level.getChunkSource().getChunk(0, 0, false) == null
                        && client.level.getChunkSource().getChunk(0, 1, false) == null
                        && !KavoraMod.renderer.getChunkManager().hasTerrainGeometry(0, 1)
                        && KavoraMod.renderer.getChunkManager().hasVisibleFarTerrainGeometry(0, 6, 1), 600);
            } catch (AssertionError failure) {
                context.runOnClient(client -> {
                    var terrain = KavoraMod.renderer.getChunkManager();
                    LOGGER.error("Far light fixture at {}: source loaded={}, wall loaded={}, near wall={}, {}",
                            client.player.position(), client.level.getChunkSource().getChunk(0, 0, false) != null,
                            client.level.getChunkSource().getChunk(0, 1, false) != null,
                            terrain.hasTerrainGeometry(0, 1), terrain.getFarSectionDiagnostics(0, 6, 1));
                });
                throw failure;
            }
            waitForFrames(context, 16);
            try (var debug = RuntimeOptionScope.acquire(context, "rtx.debugView.debugViewIdx", "32")) {
                waitForFrames(context, 8);
                Path albedo = KavoraWorldGameTest.captureCompletedFrame(context, "far-invisible-light-albedo");
                KavoraWorldGameTest.require(sampleValue(albedo, .5, .5) > 190,
                        "Far invisible Light block is hiding the white wall");
            }
            try (var exposure = RuntimeOptionScope.acquire(context, "rtx.autoExposure.autoExposureSpeed", "0")) {
                waitForFrames(context, 24);
                Path lit = KavoraWorldGameTest.captureCompletedFrame(context, "far-invisible-light-lit");
                try (var direct = RuntimeOptionScope.acquire(context, "rtx.enableDirectLighting", "false")) {
                    waitForFrames(context, 32);
                    Path dark = KavoraWorldGameTest.captureCompletedFrame(context, "far-invisible-light-no-direct");
                    double litValue = sampleValue(lit, .5, .5), darkValue = sampleValue(dark, .5, .5);
                    LOGGER.info("Far Light block wall sample: lit={}, no direct={}", litValue, darkValue);
                    KavoraWorldGameTest.require(litValue > darkValue + 15, "Far Light block did not illuminate the wall");
                }
            }
        } finally {
            world.getServer().runOnServer(server -> server.getPlayerList().setViewDistance(distance));
            context.runOnClient(client -> client.options.renderDistance().set(distance));
        }
    }

    private static void waitForFrames(ClientGameTestContext context, int frames) {
        long target = context.computeOnClient(client -> KavoraFrameProbe.presents) + frames;
        context.waitFor(client -> KavoraFrameProbe.presents >= target, 1600);
    }

    private static double sampleValue(Path file, double u, double v) {
        try {
            var image = ImageIO.read(file.toFile());
            int cx = (int) (u * image.getWidth()), cy = (int) (v * image.getHeight());
            int sum = 0;
            for (int y = cy - 8; y < cy + 8; y++) {
                for (int x = cx - 8; x < cx + 8; x++) {
                    int rgb = image.getRGB(x, y);
                    sum += (rgb >>> 16 & 255) + (rgb >>> 8 & 255) + (rgb & 255);
                }
            }
            return sum / (16.0 * 16 * 3);
        } catch (IOException e) {
            throw new AssertionError("Cannot read block-source capture " + file, e);
        }
    }

    private static void inventory(ClientGameTestContext context) {
        context.runOnClient(client -> {
            var rows = new ArrayList<String>();
            var sampler = BlockLightRuleManager.fixedCentroid(new float[] {.5f, .5f, .5f});
            for (var block : BuiltInRegistries.BLOCK) {
                int lit = 0, covered = 0, max = 0;
                for (var state : block.getStateDefinition().getPossibleStates()) {
                    if (state.getLightEmission() == 0) continue;
                    lit++;
                    max = Math.max(max, state.getLightEmission());
                    if (!BlockLightRuleManager.resolve(state, BlockPos.ZERO, sampler).isEmpty()) covered++;
                }
                if (lit != 0) {
                    var id = BuiltInRegistries.BLOCK.getKey(block);
                    if (id.getNamespace().equals("minecraft") && covered != lit)
                        throw new AssertionError("Luminous states without a sampled emitter: " + id + " " + covered + "/" + lit);
                    rows.add(id + " max=" + max + " litStates=" + lit + " analyticStates=" + covered);
                }
            }
            rows.sort(null);
            for (String row : rows) LOGGER.info("Emitter inventory: {}", row);
            var api = KavoraMod.renderer.getRemixApi();
            for (String key : new String[] {"rtx.autoExposure.evMinValue", "rtx.autoExposure.evMaxValue",
                    "rtx.localtonemap.exposure", "rtx.integrateIndirectMode", "rtx.enableDirectLighting"})
                LOGGER.info("Night configuration: {}={}", key, api.getConfigVariableResolved(key));
            String output = System.getProperty("kavora.gametest.emitterInventory");
            if (output != null) {
                try { Files.write(Path.of(output), rows); }
                catch (java.io.IOException error) { throw new AssertionError("Cannot save emitter inventory", error); }
            }
        });
    }
}
