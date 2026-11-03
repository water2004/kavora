/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gametest;

import com.nvidia.rtxremix.minecraft.render.RenderBackend;
import java.util.Locale;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.DimensionType;

/** Replaces the client world without disconnecting, including skyless and fixed-sky dimensions. */
public final class RemixDimensionGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        if (Boolean.getBoolean("rtxremix.gametest.cloudsOnly")) return;
        if (Boolean.getBoolean("rtxremix.gametest.beaconOnly")
                || Boolean.getBoolean("rtxremix.gametest.particleOnly")
                || Boolean.getBoolean("rtxremix.gametest.settingsOnly")
                || Boolean.getBoolean("rtxremix.gametest.lightingOnly")
                || Boolean.getBoolean("rtxremix.gametest.streamingOnly")
                || Boolean.getBoolean("rtxremix.gametest.retainedOnly")) return;
        boolean hidden = context.computeOnClient(client -> client.gui.hud.isHidden());
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getServer().runOnServer(server -> RemixSceneFixtures.commands(server, "gamemode creative @a"));
            context.runOnClient(client -> {
                if (!client.gui.hud.isHidden()) client.gui.hud.toggle();
            });
            visit(context, world, Level.NETHER, DimensionType.Skybox.NONE, "nether");
            visit(context, world, Level.END, DimensionType.Skybox.END, "end");
            visit(context, world, Level.OVERWORLD, DimensionType.Skybox.OVERWORLD, "overworld-return");
        } finally {
            context.runOnClient(client -> {
                if (client.gui.hud.isHidden() != hidden) client.gui.hud.toggle();
            });
        }
    }

    private static void visit(ClientGameTestContext context, TestSingleplayerContext world,
                              ResourceKey<Level> dimension, DimensionType.Skybox sky, String name) {
        world.getServer().runOnServer(server -> buildAndEnter(server, dimension));
        context.waitFor(client -> client.level != null && client.level.dimension().equals(dimension)
                && client.gui.screen() == null, 2400);
        context.runOnClient(client -> {
            if (client.level.dimensionType().skybox() != sky) throw new AssertionError("Unexpected dimension sky");
            client.gui.toastManager().clear();
        });
        context.waitTicks(60);
        String backend = context.computeOnClient(client -> RenderBackend.current().name().toLowerCase(Locale.ROOT));
        RemixWorldGameTest.assertWorldRenders(context, world, "dimension-" + name + "-" + backend);
    }

    private static void buildAndEnter(MinecraftServer server, ResourceKey<Level> dimension) {
        var level = server.getLevel(dimension);
        if (level == null) throw new AssertionError("Fixture dimension missing: " + dimension);
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        for (int z = -8; z <= 8; z++) for (int x = -8; x <= 8; x++) {
            level.setBlockAndUpdate(position.set(x, 79, z), Blocks.STONE_BRICKS.defaultBlockState());
            for (int y = 80; y <= 87; y++) {
                level.setBlockAndUpdate(position.set(x, y, z), Blocks.AIR.defaultBlockState());
            }
        }
        level.setBlockAndUpdate(position.set(-3, 80, 2), Blocks.GLOWSTONE.defaultBlockState());
        level.setBlockAndUpdate(position.set(3, 80, 2), Blocks.SEA_LANTERN.defaultBlockState());
        level.setBlockAndUpdate(position.set(0, 80, 4), Blocks.GOLD_BLOCK.defaultBlockState());
        var player = server.getPlayerList().getPlayers().getFirst();
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        if (!player.teleportTo(level, 0.5, 80, -5.5, Set.of(), 0, 12, true)) {
            throw new AssertionError("Cannot enter fixture dimension: " + dimension);
        }
    }
}
