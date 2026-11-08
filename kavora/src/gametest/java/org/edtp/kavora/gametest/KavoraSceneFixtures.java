/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.gametest;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;

/** Original deterministic material and lighting gallery, built entirely through server commands. */
final class KavoraSceneFixtures {
    private KavoraSceneFixtures() {}

    static void buildGallery(MinecraftServer server) {
        var rules = server.getGameRules();
        rules.set(GameRules.ADVANCE_TIME, false, server);
        rules.set(GameRules.ADVANCE_WEATHER, false, server);
        rules.set(GameRules.SPAWN_MOBS, false, server);
        rules.set(GameRules.RANDOM_TICK_SPEED, 0, server);
        rules.set(GameRules.DROWNING_DAMAGE, false, server);
        commands(server,
                "gamemode creative @a",
                "weather clear",
                "time set 6000",
                "fill -24 -61 -20 24 -61 30 minecraft:stone_bricks",
                "fill -16 -64 -2 -6 -61 10 minecraft:stone_bricks",
                "fill -15 -63 -1 -7 -61 9 minecraft:water",
                "fill -13 -63 5 -9 -61 5 minecraft:oak_leaves[persistent=true,waterlogged=true]",
                "fill -16 -63 -1 -16 -61 9 minecraft:glass",
                "fill -15 -63 -2 -7 -61 -2 minecraft:glass",
                "fill 14 -61 -1 18 -60 4 minecraft:stone_bricks",
                "fill 15 -60 0 17 -60 3 minecraft:lava",
                "fill -4 -60 8 0 -56 8 minecraft:gold_block",
                "fill 2 -60 8 6 -56 8 minecraft:iron_block",
                "fill 8 -60 8 12 -56 8 minecraft:cut_copper",
                "fill 3 -60 1 3 -57 5 minecraft:glass",
                "fill 6 -60 1 6 -57 5 minecraft:red_stained_glass",
                "fill 9 -60 1 9 -57 5 minecraft:ice",
                "setblock -2 -60 1 minecraft:oak_leaves[persistent=true]",
                "fill -14 -60 12 -12 -56 12 minecraft:oak_leaves[persistent=true]",
                "fill -11 -60 12 -9 -56 12 minecraft:cherry_leaves[persistent=true]",
                "fill -8 -60 12 -6 -56 12 minecraft:birch_leaves[persistent=true]",
                "fill -14 -60 13 -6 -56 13 minecraft:white_concrete",
                "setblock -1 -60 1 minecraft:oak_fence",
                "setblock 0 -60 1 minecraft:stone_brick_stairs[facing=south]",
                "setblock 1 -60 1 minecraft:stone_brick_slab[type=bottom]",
                "setblock -3 -60 4 minecraft:chest[facing=north]",
                "setblock -1 -60 4 minecraft:white_banner[rotation=0]",
                "setblock 1 -60 4 minecraft:oak_sign[rotation=0]{front_text:{messages:['\"Kavora\"','\"Material test\"','\"26.3\"','\"\"']}}",
                "summon minecraft:cow -4 -60 3 {NoAI:1b,Invulnerable:1b,PersistenceRequired:1b,Rotation:[180f,0f]}",
                "summon minecraft:sheep -4 -60 5 {NoAI:1b,Invulnerable:1b,PersistenceRequired:1b,Color:14b}",
                "summon minecraft:armor_stand 0 -60 5 {NoGravity:1b,ShowArms:1b}",
                "fill -16 -60 16 -16 -54 30 minecraft:stone_bricks",
                "fill 16 -60 16 16 -54 30 minecraft:stone_bricks",
                "fill -16 -54 16 16 -54 30 minecraft:stone_bricks",
                "fill -16 -60 30 16 -54 30 minecraft:stone_bricks",
                "setblock -10 -60 24 minecraft:sea_lantern",
                "setblock -5 -60 24 minecraft:torch",
                "setblock 0 -60 24 minecraft:glowstone",
                "setblock 5 -60 24 minecraft:soul_lantern",
                "setblock 10 -60 24 minecraft:campfire[lit=true]");
        var level = server.overworld();
        for (int i = 0; i < 3; i++) {
            ItemFrame frame = new ItemFrame(level, new BlockPos(-3 + i, -58, 7), Direction.NORTH);
            if (i == 1) frame.setItem(new ItemStack(Items.DIAMOND));
            if (i == 2) frame.setItem(MapItem.create(level, 0, 0, (byte) 0, false, false));
            level.addFreshEntity(frame);
        }
    }

    static void commands(MinecraftServer server, String... commands) {
        var source = server.createCommandSourceStack().withSuppressedOutput();
        for (String command : commands) {
            try {
                server.getCommands().getDispatcher().execute(command, source);
            } catch (CommandSyntaxException e) {
                throw new AssertionError("Scene fixture command failed: " + command, e);
            }
        }
    }
}
