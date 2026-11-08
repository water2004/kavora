/* SPDX-License-Identifier: Apache-2.0 */
package org.edtp.kavora.gametest;

import org.edtp.kavora.KavoraMod;
import org.edtp.kavora.UnfocusedFramerateLimit;
import org.edtp.kavora.light.BlockLightRuleManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
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
