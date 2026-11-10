/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.gametest;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.IntPredicate;
import javax.imageio.ImageIO;

/** Deterministic world fixtures for material and lighting checks. */
final class KavoraSceneFixtures {
    private KavoraSceneFixtures() {}

    static void captureEntityMaterials(ClientGameTestContext context, TestSingleplayerContext world) {
        captureEntityMaterials(context, world, 0);
        captureEntityMaterials(context, world, 8192);
    }

    private static void captureEntityMaterials(ClientGameTestContext context, TestSingleplayerContext world, int x) {
        world.getServer().runOnServer(server -> {
            var level = server.overworld();
            level.getChunk((x - 6) >> 4, -1);
            level.getChunk((x + 6) >> 4, -1);
            level.getChunk((x - 6) >> 4, 0);
            level.getChunk((x + 6) >> 4, 0);
            String at = "execute positioned " + (x + 0.0) + " 0 0 run ";
            if (x == 0) commands(server, "time set 6000", "weather clear");
            commands(server,
                    at + "fill ~-6 -61 -6 ~6 -61 5 minecraft:stone_bricks",
                    at + "fill ~-6 -60 3 ~6 -56 3 minecraft:quartz_block",
                    at + "setblock ~1 -58 2 minecraft:oak_wall_sign[facing=north]",
                    at + "summon minecraft:villager ~-0.55 -60 1.5 {NoAI:1b,PersistenceRequired:1b,Rotation:[180f,0f],VillagerData:{type:\"minecraft:plains\",profession:\"minecraft:none\",level:1}}",
                    at + "summon minecraft:villager ~-1.45 -60 1.5 {NoAI:1b,PersistenceRequired:1b,Rotation:[180f,0f],VillagerData:{type:\"minecraft:plains\",profession:\"minecraft:farmer\",level:5}}",
                    at + "fill ~2 -56 0 ~6 -56 3 minecraft:stone_bricks",
                    at + "summon minecraft:zombie_villager ~2.65 -60 1.5 {NoAI:1b,PersistenceRequired:1b,Rotation:[180f,0f],VillagerData:{type:\"minecraft:plains\",profession:\"minecraft:none\",level:1}}",
                    at + "summon minecraft:drowned ~3.7 -60 1.5 {NoAI:1b,PersistenceRequired:1b,Rotation:[180f,0f]}",
                    at + "summon minecraft:sheep ~4.9 -60 1.5 {NoAI:1b,PersistenceRequired:1b,Rotation:[180f,0f],Color:14b}");
            var sign = (SignBlockEntity) level.getBlockEntity(new BlockPos(x + 1, -58, 2));
            var text = sign.getText(SignTextSlot.FRONT).asMutable()
                    .setLine(0, Component.literal("ABC 123"))
                    .setLine(1, Component.literal("A\u4e2dB\u6587C\u661fD"))
                    .setLine(2, Component.literal("\u661f\u6587\u4e2d"))
                    .setLine(3, Component.literal("xyz!?"));
            sign.setText(text.asImmutable(), SignTextSlot.FRONT);
            var player = server.getPlayerList().getPlayers().getFirst();
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
        });
        moveEntityFixtureCamera(context, world, x, -0.8, 0);
        context.waitTicks(40);
        KavoraWorldGameTest.captureCompletedFrame(context, "entity-materials-" + x);
        try (var debug = RuntimeOptionScope.acquire(context, "rtx.debugView.debugViewIdx", "32")) {
            context.waitTicks(10);
            Path albedo = KavoraWorldGameTest.captureCompletedFrame(context, "entity-materials-albedo-" + x);
            assertVillagerMaterials(readImage(albedo), albedo);
            moveEntityFixtureCamera(context, world, x + 3.75, -0.8, 0);
            context.waitTicks(12);
            Path mobs = KavoraWorldGameTest.captureCompletedFrame(context, "layered-mobs-albedo-" + x);
            assertSheepFace(readImage(mobs), mobs);
            moveEntityFixtureCamera(context, world, x + 1.5, 1.5, -23);
            context.waitTicks(12);
            Path sign = KavoraWorldGameTest.captureCompletedFrame(context, "sign-albedo-" + x);
            assertSignMaterials(readImage(sign), sign);
        }
    }

    private static void moveEntityFixtureCamera(ClientGameTestContext context, TestSingleplayerContext world,
            double x, double z, float pitch) {
        world.getServer().runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            KavoraWorldGameTest.require(player.teleportTo(server.overworld(), x, -59.65, z, Set.of(), 0, pitch, true),
                    "Entity materials fixture teleport failed");
        });
        context.waitFor(client -> Math.abs(client.player.getZ() - z) < 0.01 && Math.abs(client.player.getX() - x) < 0.01, 200);
    }

    private static BufferedImage readImage(Path albedo) {
        try {
            return ImageIO.read(albedo.toFile());
        } catch (IOException e) {
            throw new AssertionError("Could not read entity material fixture " + albedo, e);
        }
    }

    private static void assertVillagerMaterials(BufferedImage image, Path albedo) {
        IntPredicate eyes = rgb -> {
            int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
            return g > 100 && 2 * g > 3 * r && 2 * g > 3 * b;
        };
        KavoraWorldGameTest.require(countPixels(image, 464, 275, 542, 308, eyes) > 30,
                "Unemployed villager face is missing its eye texture: " + albedo);
        KavoraWorldGameTest.require(countPixels(image, 570, 275, 674, 308, eyes) > 30,
                "Farmer face is missing its eye texture: " + albedo);
        KavoraWorldGameTest.require(countPixels(image, 577, 236, 669, 260, rgb -> {
            int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
            return r > 145 && g > 115 && 2 * b < r;
        }) > 100, "Farmer hat lost its opaque texture: " + albedo);

    }

    private static void assertSignMaterials(BufferedImage image, Path albedo) {
        // Count separate horizontal bands of ink within the sign face. The sign has four lines,
        // including bitmap-only Latin text and a row of Unicode fallback glyphs.
        int bands = 0;
        boolean previousInk = false;
        for (int y = 164; y < 331; y++) {
            boolean ink = countPixels(image, 365, y, 490, y + 1,
                    rgb -> (rgb >> 16 & 255) > 230 && (rgb >> 8 & 255) > 230 && (rgb & 255) > 230) > 2;
            if (ink && !previousInk) bands++;
            previousInk = ink;
        }
        KavoraWorldGameTest.require(bands >= 4, "Sign is missing one or more lines: " + albedo);
    }

    private static void assertSheepFace(BufferedImage image, Path albedo) {
        double skin = countPixels(image, 145, 372, 235, 446, rgb -> {
            int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
            return r > 80 && r < 215 && g > 65 && g < 200 && r - g > 15 && g - b > 6;
        });
        KavoraWorldGameTest.require(skin > 1300, "Sheep undercoat is obscuring the face: " + skin + " pixels in " + albedo);
    }

    private static double countPixels(BufferedImage image, int x0, int y0, int x1, int y1, IntPredicate matches) {
        // Camera FOV follows height. Preserve the crop about the optical centre at other resolutions.
        double scale = image.getHeight() / 480.0;
        double originX = image.getWidth() * 0.5 - 427 * scale;
        int count = 0;
        for (int y = (int) Math.round(y0 * scale); y < Math.round(y1 * scale); y++) {
            for (int x = Math.max(0, (int) Math.round(originX + x0 * scale));
                    x < Math.min(image.getWidth(), Math.round(originX + x1 * scale)); x++) {
                if (matches.test(image.getRGB(x, y))) count++;
            }
        }
        return count / (scale * scale);
    }

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
