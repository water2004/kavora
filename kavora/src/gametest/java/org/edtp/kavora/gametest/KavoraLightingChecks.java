/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.gametest;

import org.edtp.kavora.KavoraMod;
import org.edtp.kavora.entity.HeldItemFlame;
import org.edtp.kavora.light.BlockLightRuleManager;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.gamerules.GameRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Controlled celestial lighting and new light-emitter fixtures. */
final class KavoraLightingChecks {
    private static final Logger LOGGER = LoggerFactory.getLogger(KavoraLightingChecks.class);
    private KavoraLightingChecks() { }

    static void run(ClientGameTestContext context, TestSingleplayerContext world) {
        if (Boolean.getBoolean("kavora.gametest.moonLightingOnly")) {
            KavoraMoonChecks.run(context, world);
            return;
        }
        if (Boolean.getBoolean("kavora.gametest.nightIlluminationOnly")) {
            KavoraNightIlluminationChecks.run(context, world);
            return;
        }
        world.getServer().runOnServer(server -> {
            server.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
            server.getGameRules().set(GameRules.ADVANCE_WEATHER, false, server);
            server.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
            server.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 0, server);
            KavoraSceneFixtures.commands(server, "weather clear", "time set 21000",
                    "fill -8 -60 1 -4 -57 2 minecraft:oak_leaves[persistent=true]",
                    "fill -2 -60 1 2 -57 2 minecraft:birch_leaves[persistent=true]",
                    "fill 4 -60 1 8 -57 2 minecraft:cherry_leaves[persistent=true]",
                    "fill -8 -60 -5 8 -60 -2 minecraft:short_grass");
            var player = server.getPlayerList().getPlayers().getFirst();
            player.setGameMode(GameType.CREATIVE);
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            if (!player.teleportTo(server.overworld(), .5, -56, -13, Set.of(), 0, 15, true)) {
                throw new AssertionError("Lighting camera setup failed");
            }
        });
        KavoraWorldGameTest.assertWorldRenders(context, world, "lighting-night");
        if (Boolean.getBoolean("kavora.gametest.lightingCacheChecks")) {
            checkNightCache(context, world);
            return;
        }
        logCelestialLights(context, "night");
        captureOption(context, "rtx.enableDirectLighting", "False", "night-no-direct");
        captureOption(context, "rtx.subsurface.enableThinOpaque", "False", "night-no-transmission");
        captureOption(context, "rtx.spatiallyHashedRadianceCache.queryForThinOpaquePrimaryView", "False", "night-no-primary-cache");
        world.getServer().runOnServer(server -> KavoraSceneFixtures.commands(server, "time set 12300"));
        capture(context, "sunset");
        logCelestialLights(context, "sunset");
        captureOption(context, "rtx.subsurface.enableThinOpaque", "False", "sunset-no-transmission");
        if (Boolean.getBoolean("kavora.gametest.celestialSweep")) {
            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                player.teleportTo(server.overworld(), 18, -55, -7, Set.of(), 70, 15, true);
            });
            for (int time : new int[]{6000, 18000, 21000, 23000, 12000, 13000, 14000}) {
                world.getServer().runOnServer(server -> KavoraSceneFixtures.commands(server, "time set " + time));
                capture(context, "backlit-" + time);
                logCelestialLights(context, "backlit-" + time);
                captureOption(context, "rtx.spatiallyHashedRadianceCache.queryForThinOpaquePrimaryView", "False", "backlit-" + time + "-no-cache");
                captureOption(context, "rtx.subsurface.enableThinOpaque", "False", "backlit-" + time + "-no-transmission");
            }
        }
        checkCopperTorch(context, world);
    }

    private static void checkNightCache(ClientGameTestContext context, TestSingleplayerContext world) {
        setOption(context, "rtx.graphicsPreset", "4");
        String feedback = System.getProperty("kavora.gametest.lightingFilterFeedback");
        if (feedback != null) setOption(context, "rtx.spatiallyHashedRadianceCache.cellFilterFeedback", feedback);
        context.runOnClient(client -> {
            var api = KavoraMod.renderer.getRemixApi();
            for (String option : new String[]{"maxNumFramesToAccumulate", "staleFrameNumber", "cellFilterFeedback"}) {
                LOGGER.info("Lighting cache {}={}", option,
                        api.getConfigVariableResolved("rtx.spatiallyHashedRadianceCache." + option));
            }
        });
        world.getServer().runOnServer(server -> {
            KavoraSceneFixtures.commands(server, "time set 18000");
            server.getPlayerList().getPlayers().getFirst().teleportTo(server.overworld(),
                    18, -55, -7, Set.of(), 70, 15, true);
        });
        Path coldNight = capture(context, "cold-night");
        captureOption(context, "rtx.integrateIndirectMode", "0", "night-path-traced");
        captureOption(context, "rtx.enableDirectLighting", "False", "night-backlit-no-direct");
        captureOption(context, "rtx.spatiallyHashedRadianceCache.cellFilterMode", "0", "night-backlit-no-filter");
        world.getServer().runOnServer(server -> KavoraSceneFixtures.commands(server, "time set 6000"));
        capture(context, "cache-day");
        world.getServer().runOnServer(server -> KavoraSceneFixtures.commands(server, "time set 18000"));
        capture(context, "cache-night-after-day");
        Path settledNight = null;
        for (int sample = 1; sample <= 6; sample++) {
            Path image = capture(context, "cache-night-settling-" + sample);
            if (sample == 2) settledNight = image;
        }
        captureOption(context, "rtx.spatiallyHashedRadianceCache.cellFilterFeedback", "0", "cache-night-no-feedback");
        captureOption(context, "rtx.enableDirectLighting", "False", "cache-night-after-day-no-direct");
        context.runOnClient(client -> KavoraMod.renderer.getRemixApi()
                .setConfigVariable("rtx.spatiallyHashedRadianceCache.resetHistory", "True"));
        capture(context, "cache-night-reset");
        captureOption(context, "rtx.integrateIndirectMode", "0", "cache-night-after-day-path-traced");
        captureOption(context, "rtx.debugView.debugViewIdx", "100", "night-direct-noisy");
        captureOption(context, "rtx.debugView.debugViewIdx", "106", "night-indirect-noisy");
        assertNightResponse(coldNight, settledNight);
    }

    /** Compare the same fixed leaf and grass regions after 180 presented frames, excluding sky and HUD. */
    private static void assertNightResponse(Path coldNight, Path settledNight) {
        try {
            var cold = ImageIO.read(coldNight.toFile());
            var settled = ImageIO.read(settledNight.toFile());
            require(cold != null && settled != null, "Cannot decode lighting response captures");
            int[][] regions = {{640, 500, 795, 680}, {1000, 590, 1050, 730}};
            for (int[] region : regions) {
                double reference = regionLuminance(cold, region);
                double after = regionLuminance(settled, region);
                LOGGER.info("Night response: cold={}, after180Frames={}", reference, after);
                require(after <= reference * 2 + .005,
                        "Foliage retains daylight after 180 frames: cold=" + reference + ", after=" + after);
            }
        } catch (IOException error) {
            throw new AssertionError("Cannot read lighting response captures", error);
        }
    }

    private static double regionLuminance(java.awt.image.BufferedImage image, int[] region) {
        int left = region[0] * image.getWidth() / 1920, right = region[2] * image.getWidth() / 1920;
        int top = region[1] * image.getHeight() / 1080, bottom = region[3] * image.getHeight() / 1080;
        double sum = 0;
        for (int y = top; y < bottom; y++) for (int x = left; x < right; x++) {
            int rgb = image.getRGB(x, y);
            sum += .2126 * linear((rgb >>> 16) & 255) + .7152 * linear((rgb >>> 8) & 255) + .0722 * linear(rgb & 255);
        }
        return sum / ((right - left) * (bottom - top));
    }

    private static double linear(int channel) {
        double value = channel / 255.0;
        return value <= .04045 ? value / 12.92 : Math.pow((value + .055) / 1.055, 2.4);
    }

    private static void logCelestialLights(ClientGameTestContext context, String phase) {
        context.runOnClient(client -> {
            var sky = KavoraMod.renderer.getLightManager().getSkyLightState();
            LOGGER.info("Lighting {}: sunPosition={}, sunRadiance={}, moonPosition={}, moonRadiance={}",
                    phase, Arrays.toString(sky.sunPosition()), Arrays.toString(sky.sunDirectRadiance()),
                    Arrays.toString(sky.moonPosition()), Arrays.toString(sky.moonDirectRadiance()));
        });
    }

    private static void checkCopperTorch(ClientGameTestContext context, TestSingleplayerContext world) {
        context.runOnClient(client -> {
            var sampler = BlockLightRuleManager.fixedCentroid(new float[]{.5f, .7f, .5f});
            var standing = BlockLightRuleManager.resolve(Blocks.COPPER_TORCH.defaultBlockState(), BlockPos.ZERO, sampler);
            var normal = BlockLightRuleManager.resolve(Blocks.TORCH.defaultBlockState(), BlockPos.ZERO, sampler);
            require(standing.size() == 1 && normal.size() == 1, "Torch light rule missing or duplicated");
            var light = standing.getFirst();
            require(light.intensity() == normal.getFirst().intensity() && light.intensity() > 0,
                    "Copper torch must retain vanilla torch light strength");
            require(light.colorG() > light.colorR() && light.colorG() > light.colorB(), "Copper flame is not green");
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                var state = Blocks.COPPER_WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, facing);
                var wall = BlockLightRuleManager.resolve(state, BlockPos.ZERO, sampler);
                require(wall.size() == 1 && wall.getFirst().intensity() == light.intensity(), "Wall torch light missing");
                require(Math.abs(wall.getFirst().x() - (.5f - .27f * facing.getStepX())) < 1e-6f
                        && Math.abs(wall.getFirst().z() - (.5f - .27f * facing.getStepZ())) < 1e-6f,
                        "Wall torch emitter is not anchored to its flame");
            }
            require(HeldItemFlame.particleFor(new ItemStack(Items.COPPER_TORCH)) == ParticleTypes.COPPER_FIRE_FLAME,
                    "Held copper torch does not use the placed flame");
        });
        world.getServer().runOnServer(server -> {
            KavoraSceneFixtures.commands(server, "time set 18000",
                    "fill 24 -61 -4 36 -61 8 minecraft:white_concrete",
                    "fill 24 -60 -4 24 -55 8 minecraft:white_concrete",
                    "fill 36 -60 -4 36 -55 8 minecraft:white_concrete",
                    "fill 24 -55 -4 36 -55 8 minecraft:white_concrete",
                    "fill 24 -60 8 36 -55 8 minecraft:white_concrete",
                    "setblock 27 -59 4 minecraft:stone",
                    "setblock 33 -59 4 minecraft:stone",
                    "setblock 27 -58 4 minecraft:torch",
                    "setblock 33 -58 4 minecraft:copper_torch",
                    "setblock 30 -58 7 minecraft:copper_wall_torch[facing=north]",
                    "give @a minecraft:copper_torch");
            var player = server.getPlayerList().getPlayers().getFirst();
            player.teleportTo(server.overworld(), 30.5, -60, -2.5, Set.of(), 0, -8, true);
        });
        KavoraWorldGameTest.assertWorldRenders(context, world, "copper-torches");
    }

    private static void captureOption(ClientGameTestContext context, String key, String value, String phase) {
        String previous = context.computeOnClient(client -> KavoraMod.renderer.getRemixApi().getConfigVariableResolved(key));
        try {
            setOption(context, key, value);
            capture(context, phase);
        } finally {
            setOption(context, key, previous);
        }
    }

    private static void setOption(ClientGameTestContext context, String key, String value) {
        context.runOnClient(client -> KavoraMod.renderer.getRemixApi().setConfigVariable(key, value));
        try {
            context.waitFor(client -> value.equalsIgnoreCase(KavoraMod.renderer.getRemixApi().getConfigVariableResolved(key)), 100);
        } catch (AssertionError error) {
            String actual = context.computeOnClient(client -> KavoraMod.renderer.getRemixApi().getConfigVariableResolved(key));
            throw new AssertionError(key + " did not resolve to " + value + " (actual=" + actual + ")", error);
        }
    }

    private static Path capture(ClientGameTestContext context, String phase) {
        long frame = context.computeOnClient(client -> KavoraFrameProbe.presents);
        context.waitFor(client -> KavoraFrameProbe.presents >= frame + 60, 1200);
        return KavoraWorldGameTest.captureCompletedFrame(context, "lighting-" + phase);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
