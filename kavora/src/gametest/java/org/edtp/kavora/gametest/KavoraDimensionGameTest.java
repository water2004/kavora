/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.gametest;

import org.edtp.kavora.render.RenderBackend;
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
public final class KavoraDimensionGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        if (Boolean.getBoolean("kavora.gametest.nightSkyOnly")) return;
        if (Boolean.getBoolean("kavora.gametest.cloudsOnly")) return;
        if (Boolean.getBoolean("kavora.gametest.beaconOnly")
                || Boolean.getBoolean("kavora.gametest.endPortalsOnly")
                || Boolean.getBoolean("kavora.gametest.stainedGlassOnly")
                || Boolean.getBoolean("kavora.gametest.particleOnly")
                || Boolean.getBoolean("kavora.gametest.settingsOnly")
                || Boolean.getBoolean("kavora.gametest.qualityVisualOnly")
                || Boolean.getBoolean("kavora.gametest.terrainReloadOnly")
                || Boolean.getBoolean("kavora.gametest.lightingOnly")
                || Boolean.getBoolean("kavora.gametest.streamingOnly")
                || Boolean.getBoolean("kavora.gametest.retainedOnly")) return;
        boolean hidden = context.computeOnClient(client -> client.gui.hud.isHidden());
        int width = Integer.getInteger("kavora.gametest.width", 0);
        int height = Integer.getInteger("kavora.gametest.height", 2160);
        if (width > 0) {
            context.getInput().resizeWindow(width, height);
            context.waitFor(client -> client.getWindow().getWidth() == width && client.getWindow().getHeight() == height, 200);
        }
        boolean colourOnly = Boolean.getBoolean("kavora.gametest.dimensionColourOnly");
        var builder = context.worldBuilder();
        if (colourOnly) builder.adjustSettings(settings -> settings.setSeed("1"));
        try (TestSingleplayerContext world = builder.create()) {
            world.getServer().runOnServer(server -> KavoraSceneFixtures.commands(server, "gamemode creative @a"));
            context.runOnClient(client -> {
                if (!client.gui.hud.isHidden()) client.gui.hud.toggle();
            });
            visit(context, world, Level.NETHER, DimensionType.Skybox.NONE, "nether");
            if (colourOnly) KavoraColourChecks.run(context);
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
        KavoraWorldGameTest.assertWorldRenders(context, world, "dimension-" + name + "-" + backend);
        if (Boolean.getBoolean("kavora.gametest.dimensionColourOnly")) KavoraColourChecks.recordDimension(context, name);
    }

    static void buildAndEnter(MinecraftServer server, ResourceKey<Level> dimension) {
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
