/* SPDX-License-Identifier: Apache-2.0 */
package com.nvidia.rtxremix.minecraft.gametest;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.UnfocusedFramerateLimit;
import com.nvidia.rtxremix.minecraft.light.LightManager;
import com.nvidia.rtxremix.minecraft.sky.SkyRenderer;
import java.util.Arrays;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Separates apparent lunar size from ground illumination under the normal rendering settings. */
final class RemixMoonChecks {
    private static final Logger LOGGER = LoggerFactory.getLogger(RemixMoonChecks.class);

    static void run(ClientGameTestContext context, TestSingleplayerContext world) {
        int limit = context.computeOnClient(client -> UnfocusedFramerateLimit.get());
        float intensity = LightManager.getMoonIntensity(), size = SkyRenderer.getMoonSize();
        try {
            context.runOnClient(client -> UnfocusedFramerateLimit.set(UnfocusedFramerateLimit.UNLIMITED));
            world.getServer().runOnServer(server -> {
                server.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
                server.getGameRules().set(GameRules.ADVANCE_WEATHER, false, server);
                server.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
                RemixSceneFixtures.commands(server, "weather clear", "time set 18000",
                        "fill -16 -61 -16 16 -61 16 minecraft:white_concrete",
                        "fill -1 -60 3 1 -55 5 minecraft:stone_bricks");
                var player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.CREATIVE);
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                player.teleportTo(server.overworld(), .5, -58, -10, Set.of(), 0, 18, true);
            });
            context.waitFor(client -> RtxRemixMod.renderer.isSceneReady()
                    && RtxRemixMod.renderer.getChunkManager().getTerrainDiagnostics().columnsMissingGeometry() == 0, 2400);
            setIntensity(context, 1);
            capture(context, "moon-ground-default");
            context.runOnClient(client -> {
                var state = RtxRemixMod.renderer.getLightManager().getSkyLightState();
                LOGGER.info("Moon transport: position={}, source={}, direct={}, phase={}",
                        Arrays.toString(state.moonPosition()), Arrays.toString(state.moonSourceRadiance()),
                        Arrays.toString(state.moonDirectRadiance()), client.gameRenderer.mainCamera().attributeProbe()
                                .getValue(net.minecraft.world.attribute.EnvironmentAttributes.MOON_PHASE, 0f).index());
                client.player.setYRot((float) Math.toDegrees(Math.atan2(-state.moonPosition()[0], state.moonPosition()[2])));
                client.player.setXRot((float) -Math.toDegrees(Math.asin(state.moonPosition()[1])));
                SkyRenderer.setMoonSize(1);
            });
            capture(context, "moon-size-before");
            context.runOnClient(client -> SkyRenderer.setMoonSize(SkyRenderer.DEFAULT_MOON_SIZE));
            capture(context, "moon-size-default");
            int fov = context.computeOnClient(client -> client.options.fov().get());
            try {
                context.runOnClient(client -> client.options.fov().set(30));
                capture(context, "moon-surface-detail");
            } finally {
                context.runOnClient(client -> client.options.fov().set(fov));
            }
            context.runOnClient(client -> {
                client.player.setYRot(0);
                client.player.setXRot(18);
            });
            setIntensity(context, 0);
            capture(context, "moon-ground-zero");
            setIntensity(context, 3);
            capture(context, "moon-ground-three");
            setIntensity(context, intensity);
            world.getServer().runOnServer(server -> RemixSceneFixtures.commands(server, "time set 90000"));
            capture(context, "moon-ground-crescent");
            context.runOnClient(client -> LOGGER.info("Crescent transport: source={}, direct={}",
                    Arrays.toString(RtxRemixMod.renderer.getLightManager().getSkyLightState().moonSourceRadiance()),
                    Arrays.toString(RtxRemixMod.renderer.getLightManager().getSkyLightState().moonDirectRadiance())));
            context.runOnClient(client -> {
                var state = RtxRemixMod.renderer.getLightManager().getSkyLightState();
                client.player.setYRot((float) Math.toDegrees(Math.atan2(-state.moonPosition()[0], state.moonPosition()[2])));
                client.player.setXRot((float) -Math.toDegrees(Math.asin(state.moonPosition()[1])));
            });
            capture(context, "moon-crescent-surface");
            context.runOnClient(client -> {
                client.player.setYRot(0);
                client.player.setXRot(18);
            });
            if (Boolean.getBoolean("rtxremix.gametest.memoryLifecycle")) memoryLifecycle(context);
        } finally {
            context.runOnClient(client -> {
                LightManager.setMoonIntensity(intensity);
                SkyRenderer.setMoonSize(size);
                RtxRemixMod.renderer.getSkyRenderer().forceRefresh();
                UnfocusedFramerateLimit.set(limit);
            });
        }
    }

    private static void setIntensity(ClientGameTestContext context, float value) {
        context.runOnClient(client -> {
            LightManager.setMoonIntensity(value);
            RtxRemixMod.renderer.getSkyRenderer().forceRefresh();
        });
    }

    private static void memoryLifecycle(ClientGameTestContext context) {
        for (String mode : new String[] {"0", "1", "2"}) {
            try (var ignored = RuntimeOptionScope.acquire(context, "rtx.spatiallyHashedRadianceCache.cellFilterMode", mode)) {
                capture(context, "memory-filter-" + mode);
            }
        }
        try (var preserve = RuntimeOptionScope.acquire(context, "rtx.rayreconstruction.preserveSettingsInNativeMode", "True")) {
            for (int pass = 0; pass < 2; pass++) {
                try (var ignored = RuntimeOptionScope.acquire(context, "rtx.enableRayReconstruction", "False")) {
                    capture(context, "memory-sr-" + pass);
                }
                capture(context, "memory-rr-" + pass);
            }
        }
        long beforeReload = context.computeOnClient(client -> RemixFrameProbe.presents);
        context.runOnClient(client -> RtxRemixMod.renderer.requestTextureReload());
        context.waitFor(client -> {
            var terrain = RtxRemixMod.renderer.getChunkManager().getTerrainDiagnostics();
            return RemixFrameProbe.presents > beforeReload && RtxRemixMod.renderer.isSceneReady() && terrain.columnsWithGeometry() > 0
                    && terrain.columnsMissingGeometry() == 0 && terrain.pendingBuilds() == 0
                    && terrain.readyMeshes() == 0 && terrain.captureQueue() == 0;
        }, 2400);
        capture(context, "memory-textures-reloaded");
    }

    private static void capture(ClientGameTestContext context, String name) {
        long frame = context.computeOnClient(client -> RemixFrameProbe.presents);
        context.waitFor(client -> RemixFrameProbe.presents >= frame + 64, 1600);
        context.runOnClient(client -> {
            if (RemixFrameProbe.failures != 0) throw new AssertionError("Native frame failed in " + name);
            LOGGER.info("Memory lifecycle stage: name={}, frame={}, sharedImageBytes={}", name, RemixFrameProbe.presents,
                    com.nvidia.rtxremix.minecraft.asset.RemixTextureStore.get().getSharedImageSavingsBytes());
        });
        RemixWorldGameTest.captureCompletedFrame(context, name);
    }
}
