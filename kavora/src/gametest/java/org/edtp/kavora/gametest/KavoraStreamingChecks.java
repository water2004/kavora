/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.gametest;

import org.edtp.kavora.KavoraMod;
import org.edtp.kavora.compat.sodium.SodiumCapture;
import org.edtp.kavora.compat.sodium.mixin.SodiumWorldRendererAccessor;
import java.io.IOException;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.Set;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Continuous movement crosses the resident ring in both directions without terrain reloads. */
final class KavoraStreamingChecks {
    private static final Logger LOGGER = LoggerFactory.getLogger(KavoraStreamingChecks.class);
    private KavoraStreamingChecks() { }

    record FarProbe(int x, int y, int z) {
        boolean ready() { return KavoraMod.renderer.getChunkManager().hasFarTerrainGeometry(x, y, z); }
    }

    static FarProbe run(ClientGameTestContext context, TestSingleplayerContext world) {
        boolean water = Boolean.getBoolean("kavora.gametest.streamingWater");
        boolean natural = Boolean.getBoolean("kavora.gametest.streamingNatural");
        int legChunks = Integer.getInteger("kavora.gametest.streamingLegChunks", 20);
        if (legChunks < 20 || legChunks % 5 != 0) throw new IllegalArgumentException("Flight length must be at least 20 chunks and divisible by 5");
        String prefix = natural ? "natural" : water ? "water" : "streamed";
        try (Recording recording = startRecording()) {
            try {
                world.getServer().runOnServer(server -> {
                    var player = server.getPlayerList().getPlayers().getFirst();
                    player.setGameMode(GameType.CREATIVE);
                    player.getAbilities().flying = true;
                    player.onUpdateAbilities();
                    double cameraY = natural ? 160 : water ? -50 : -58;
                    if (!player.teleportTo(server.overworld(), 0.5, cameraY, 0.5, Set.of(), -90, 0, true)) {
                        throw new AssertionError("Streaming camera setup failed");
                    }
                });
                context.waitTicks(10);
                context.waitFor(client -> client.level.hasChunk(0, 0), 200);
                int originY = surfaceSection(context, 0);
                context.runOnClient(client -> LOGGER.info("Streaming start: position={}, flying={}, renderDistance={}, surfaceSection={}",
                        client.player.position(), client.player.getAbilities().flying, client.options.getEffectiveRenderDistance(), originY));
                context.runOnClient(client -> client.player.getAbilities().setFlyingSpeed(0.15f));
                flyLeg(context, true, legChunks, new FarProbe(0, originY, 0));
                awaitAllNear(context);
                int destinationY = surfaceSection(context, legChunks);
                verifyFar(context, 0, originY, 90, prefix + "-far-origin");
                if (Boolean.getBoolean("kavora.gametest.waterDiagnosticsOutbound")) captureWaterDiagnostics(context);
                context.runOnClient(client -> {
                    client.player.setYRot(90);
                    client.player.setXRot(0);
                });
                flyLeg(context, false, legChunks, new FarProbe(legChunks, destinationY, 0));
                awaitAllNear(context);
                verifyFar(context, legChunks, destinationY, -90, prefix + "-far-return");
                context.runOnClient(client -> {
                    if (KavoraFrameProbe.failures != 0) throw new AssertionError("Native frame failed during streaming");
                    var far = KavoraMod.renderer.getChunkManager().getLodDiagnostics();
                    if (far != null && far.failedCells() != 0) throw new AssertionError("Far terrain build failed: " + far);
                });
                KavoraWorldGameTest.captureCompletedFrame(context, prefix + "-" + (legChunks * 32) + "-blocks");
                if (KavoraWaterGeometryProbe.ENABLED) {
                    context.runOnClient(client -> {
                        LOGGER.info("Water geometry camera: eye={}, yaw={}, pitch={}, fov={}", client.player.getEyePosition(),
                                client.player.getYRot(), client.player.getXRot(), client.options.fov().get());
                        KavoraWaterGeometryProbe.dump(client.gameDirectory.toPath().resolve("water-geometry.bin"));
                        KavoraWaterGeometryProbe.hide(KavoraMod.renderer.getRemixApi(), true);
                    });
                    try {
                        captureSettledWaterFrame(context, "underwater-geometry");
                        captureWaterOption(context, "rtx.debugView.debugViewIdx", "22", "underwater-material");
                        captureWaterOption(context, "rtx.debugView.debugViewIdx", "23", "underwater-albedo");
                        captureWaterOption(context, "rtx.debugView.debugViewIdx", "32", "underwater-raw-albedo");
                        captureWaterOption(context, "rtx.enableDirectLighting", "False", "underwater-no-direct");
                    }
                    finally { context.runOnClient(client -> KavoraWaterGeometryProbe.hide(KavoraMod.renderer.getRemixApi(), false)); }
                    captureSettledWaterFrame(context, "water-restored");
                }
                if (Boolean.getBoolean("kavora.gametest.waterDiagnostics")) captureWaterDiagnostics(context);
                return new FarProbe(legChunks, destinationY, 0);
            } finally {
                context.getInput().releaseKey(options -> options.keyUp);
                if (recording != null) recording.stop();
            }
        }
    }

    private static int surfaceSection(ClientGameTestContext context, int cx) {
        return context.computeOnClient(client -> SectionPos.blockToSectionCoord(
                client.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx * 16, 0) - 1));
    }

    static void captureWaterDiagnostics(ClientGameTestContext context) {
        String indexKey = "rtx.debugView.debugViewIdx";
        String firstKey = "rtx.debugView.showFirstGBufferHit";
        String replaceKey = "rtx.debugView.replaceCompositeOutput";
        String[] previous = context.computeOnClient(client -> {
            var api = KavoraMod.renderer.getRemixApi();
            return new String[]{api.getConfigVariableResolved(indexKey), api.getConfigVariableResolved(firstKey),
                    api.getConfigVariableResolved(replaceKey)};
        });
        captureSettledWaterFrame(context, "baseline");
        if (Boolean.getBoolean("kavora.gametest.textureLifecycleOnly")) {
            long saved = context.computeOnClient(client ->
                    org.edtp.kavora.asset.KavoraTextureStore.get().getSharedImageSavingsBytes());
            KavoraWorldGameTest.require(saved > 256L * 1024L * 1024L, "Heavy-pack fixture did not share its duplicated atlases");
            long previousFrame = context.computeOnClient(client -> KavoraFrameProbe.presents);
            context.runOnClient(client -> {
                var api = KavoraMod.renderer.getRemixApi();
                LOGGER.info("Texture lifecycle: shared image savings={} bytes, DLSS={}, water normalIntensity={}",
                        saved, api.getConfigVariableResolved("rtx.qualityDLSS"),
                        api.getConfigVariableResolved("rtx.translucentMaterial.normalIntensity"));
                var capabilities = new org.edtp.kavora.sdk.FrameGenerationCapabilities();
                api.getFrameGenerationCapabilities(capabilities);
                LOGGER.info("NGX frame generation: initialized={}, supported={}, maxGeneratedFrames={}, reason={}",
                        capabilities.initializationComplete, capabilities.supported,
                        capabilities.maxGeneratedFrames, capabilities.notSupportedReason);
                KavoraMod.renderer.requestTextureReload();
            });
            context.waitFor(client -> {
                var terrain = KavoraMod.renderer.getChunkManager().getTerrainDiagnostics();
                return KavoraFrameProbe.presents > previousFrame && KavoraMod.renderer.isSceneReady() && terrain.columnsWithGeometry() > 0
                        && terrain.columnsMissingGeometry() == 0 && terrain.pendingBuilds() == 0
                        && terrain.readyMeshes() == 0 && terrain.captureQueue() == 0;
            }, 20 * 120);
            context.runOnClient(client -> KavoraWorldGameTest.require(
                    org.edtp.kavora.asset.KavoraTextureStore.get().getSharedImageSavingsBytes() > 256L * 1024L * 1024L,
                    "Texture reload lost shared atlas allocations"));
            captureSettledWaterFrame(context, "textures-reloaded");
            return;
        }
        if (Boolean.getBoolean("kavora.gametest.waterShoreCompositeOnly")) {
            captureWaterShoreComposite(context);
            return;
        }
        if (Boolean.getBoolean("kavora.gametest.waterRadianceOnly")) {
            captureWaterRadiance(context);
            return;
        }
        if (Boolean.getBoolean("kavora.gametest.waterCompositeOnly")) {
            captureWaterComposite(context);
            return;
        }
        if (Boolean.getBoolean("kavora.gametest.waterLightingOnly")) {
            captureWaterOption(context, "rtx.sparseRendering.enableSparseRendering", "False", "dense-lighting");
            captureWaterOption(context, "rtx.useRTXDI", "False", "ris-lighting");
            captureWaterOption(context, "rtx.subsurface.enableDiffusionProfile", "False", "no-diffusion");
            captureWaterOption(context, "rtx.volumetrics.enable", "False", "no-volumetrics");
            captureWaterOption(context, "rtx.viewDistance.distanceMode", "0", "no-distance-fade");
            captureWaterOption(context, "rtx.rayreconstruction.invalidateHistoryForAnimatedWater", "True", "water-history-reset");
            captureWaterOption(context, "rtx.translucentMaterial.normalIntensity", "0", "flat-normal");
            return;
        }
        // Indices belong to the matching source runtime's utility/debug_view_indices.h.
        int[] indices = {14, 16, 22, 271, 12};
        String[] names = {"triangle-normal", "shading-normal", "material", "depth", "uv"};
        try {
            context.runOnClient(client -> {
                var api = KavoraMod.renderer.getRemixApi();
                api.setConfigVariable(firstKey, "True");
                api.setConfigVariable(replaceKey, "True");
            });
            for (int i = 0; i < indices.length; i++) {
                String requested = Integer.toString(indices[i]);
                context.runOnClient(client -> KavoraMod.renderer.getRemixApi().setConfigVariable(indexKey, requested));
                context.waitFor(client -> requested.equals(KavoraMod.renderer.getRemixApi().getConfigVariableResolved(indexKey)), 100);
                captureSettledWaterFrame(context, names[i]);
            }
            context.runOnClient(client -> KavoraMod.renderer.getRemixApi().setConfigVariable(firstKey, "False"));
            int[] resolvedIndices = {22, 271, 121, 125, 149, 144, 148, 158, 159, 801, 62};
            String[] resolvedNames = {"resolved-material", "resolved-depth", "direct-specular", "indirect-specular",
                    "secondary-specular", "secondary-diffuse-raw", "secondary-diffuse", "secondary-direct",
                    "secondary-indirect", "subsurface", "bounces"};
            for (int i = 0; i < resolvedIndices.length; i++) {
                String requested = Integer.toString(resolvedIndices[i]);
                context.runOnClient(client -> KavoraMod.renderer.getRemixApi().setConfigVariable(indexKey, requested));
                context.waitFor(client -> requested.equals(KavoraMod.renderer.getRemixApi().getConfigVariableResolved(indexKey)), 100);
                captureSettledWaterFrame(context, resolvedNames[i]);
            }
            context.runOnClient(client -> KavoraMod.renderer.getRemixApi().setConfigVariable(indexKey, "0"));
            captureWaterOption(context, "rtx.translucentMaterial.normalIntensity", "0", "flat-normal");
            captureWaterOption(context, "rtx.enablePSRR", "False", "no-psr-reflection");
            captureWaterOption(context, "rtx.enablePSTR", "False", "no-psr-transmission");
            captureWaterOption(context, "rtx.enableRayReconstruction", "False", "no-ray-reconstruction");
            var clouds = context.computeOnClient(client -> client.options.cloudStatus().get());
            try {
                context.runOnClient(client -> client.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF));
                captureSettledWaterFrame(context, "no-clouds");
            } finally {
                context.runOnClient(client -> client.options.cloudStatus().set(clouds));
            }
        } finally {
            context.runOnClient(client -> {
                var api = KavoraMod.renderer.getRemixApi();
                api.setConfigVariable(indexKey, previous[0]);
                api.setConfigVariable(firstKey, previous[1]);
                api.setConfigVariable(replaceKey, previous[2]);
            });
            context.waitFor(client -> {
                var api = KavoraMod.renderer.getRemixApi();
                return previous[0].equalsIgnoreCase(api.getConfigVariableResolved(indexKey))
                        && previous[1].equalsIgnoreCase(api.getConfigVariableResolved(firstKey))
                        && previous[2].equalsIgnoreCase(api.getConfigVariableResolved(replaceKey));
            }, 100);
        }
    }

    private static void captureWaterRadiance(ClientGameTestContext context) {
        String[] keys = {"rtx.debugView.debugViewIdx", "rtx.debugView.replaceCompositeOutput",
                "rtx.debugView.maxValue", "rtx.debugView.accumulation.enable",
                "rtx.debugView.accumulation.numberOfFramesToAccumulate",
                "rtx.sparseRendering.enableSparseRendering", "rtx.translucentMaterial.normalIntensity",
                "rtx.enableFog", "rtx.volumetrics.enable", "rtx.viewDistance.distanceMode",
                "rtx.spatiallyHashedRadianceCache.enableResolve", "rtx.shadowTerminator.enableOffset"};
        String[] previous = context.computeOnClient(client -> java.util.Arrays.stream(keys)
                .map(KavoraMod.renderer.getRemixApi()::getConfigVariableResolved).toArray(String[]::new));
        try {
            context.runOnClient(client -> {
                var environment = new org.edtp.kavora.sky.EnvironmentSnapshot();
                environment.capture(client.level, client.gameRenderer.mainCamera(), 0);
                LOGGER.info("Cloud input: rgb=({},{},{}), sunAngle={}, cloudHeight={}",
                        environment.cloudRed, environment.cloudGreen, environment.cloudBlue,
                        environment.sunAngle, environment.cloudHeight);
            });
            setWaterOptions(context, java.util.Arrays.copyOf(keys, 10),
                    new String[]{"0", "False", "64", "False", "128", "False", "0", "False", "False", "0"});
            captureSettledWaterFrame(context, "radiance-no-fog");
            int[] indices = {158, 159, 160, 155, 156};
            String[] names = {"secondary-direct", "secondary-indirect", "secondary-total", "primary-direct", "primary-indirect"};
            for (int i = 0; i < indices.length; i++) {
                captureAccumulatedWaterView(context, indices[i], "radiance-" + names[i]);
            }
            if (Boolean.getBoolean("kavora.gametest.waterBoundaryOnly")) return;
            setWaterOptions(context, new String[]{keys[0], keys[3], keys[10]},
                    new String[]{"0", "False", "False"});
            captureSettledWaterFrame(context, "radiance-no-cache-query");
            captureAccumulatedWaterView(context, 159, "radiance-no-cache-query-indirect");
            captureAccumulatedWaterView(context, 160, "radiance-no-cache-query-total");
            setWaterOptions(context, new String[]{keys[0], keys[3], keys[10], keys[11]},
                    new String[]{"0", "False", previous[10], "False"});
            captureSettledWaterFrame(context, "radiance-no-terminator-offset");
            captureAccumulatedWaterView(context, 158, "radiance-no-terminator-offset-direct");
        } finally {
            setWaterOptions(context, keys, previous);
        }
    }

    private static void captureAccumulatedWaterView(ClientGameTestContext context, int index, String name) {
        setWaterOptions(context, new String[]{"rtx.debugView.debugViewIdx", "rtx.debugView.accumulation.enable"},
                new String[]{Integer.toString(index), "False"});
        setWaterOptions(context, new String[]{"rtx.debugView.accumulation.enable"}, new String[]{"True"});
        long frame = context.computeOnClient(client -> KavoraFrameProbe.presents);
        context.waitFor(client -> KavoraFrameProbe.presents >= frame + 128, 400);
        KavoraWorldGameTest.captureCompletedFrame(context, "water-" + name);
    }

    private static void captureWaterComposite(ClientGameTestContext context) {
        String[] keys = {"rtx.debugView.debugViewIdx", "rtx.debugView.replaceCompositeOutput",
                "rtx.debugView.maxValue", "rtx.debugView.accumulation.enable",
                "rtx.debugView.accumulation.numberOfFramesToAccumulate",
                "rtx.sparseRendering.enableSparseRendering", "rtx.translucentMaterial.normalIntensity"};
        String[] values = {"0", "False", "64", "True", "128", "False", "0"};
        String[] previous = context.computeOnClient(client -> java.util.Arrays.stream(keys)
                .map(KavoraMod.renderer.getRemixApi()::getConfigVariableResolved).toArray(String[]::new));
        try {
            setWaterOptions(context, keys, values);
            captureSettledWaterFrame(context, "composite-flat");
            int[] indices = {160, 203, 205, 206, 202, 200, 204};
            String[] names = {"raw-reflection", "weighted-reflection", "shared", "transmission", "sum", "upscaled", "weight"};
            for (int i = 0; i < indices.length; i++) {
                // Config writes bypass the menu's accumulation reset. Each view needs its own history.
                setWaterOptions(context, new String[]{keys[0], keys[2], keys[3]},
                        new String[]{Integer.toString(indices[i]), indices[i] == 204 ? "1" : "64", "False"});
                setWaterOptions(context, new String[]{keys[3]}, new String[]{"True"});
                long frame = context.computeOnClient(client -> KavoraFrameProbe.presents);
                context.waitFor(client -> KavoraFrameProbe.presents >= frame + 128, 400);
                KavoraWorldGameTest.captureCompletedFrame(context, "water-composite-" + names[i]);
            }
        } finally {
            setWaterOptions(context, keys, previous);
        }
    }

    private static void captureWaterShoreComposite(ClientGameTestContext context) {
        String[] keys = {"rtx.debugView.debugViewIdx", "rtx.debugView.replaceCompositeOutput",
                "rtx.debugView.maxValue", "rtx.debugView.accumulation.enable",
                "rtx.debugView.accumulation.numberOfFramesToAccumulate",
                "rtx.sparseRendering.enableSparseRendering", "rtx.translucentMaterial.normalIntensity",
                "rtx.enableFog", "rtx.volumetrics.enable"};
        String[] previous = context.computeOnClient(client -> java.util.Arrays.stream(keys)
                .map(KavoraMod.renderer.getRemixApi()::getConfigVariableResolved).toArray(String[]::new));
        var clouds = context.computeOnClient(client -> client.options.cloudStatus().get());
        try {
            boolean flatNormal = Boolean.getBoolean("kavora.gametest.waterFlatNormal");
            LOGGER.info("Shore diagnostic settings: DLSS={}, normalIntensity={} (flat diagnostic={})",
                    context.computeOnClient(client -> KavoraMod.renderer.getRemixApi().getConfigVariableResolved("rtx.qualityDLSS")),
                    flatNormal ? "0" : previous[6], flatNormal);
            setWaterOptions(context, keys, new String[]{"0", "False", "64", "False", "128", "False", flatNormal ? "0" : previous[6], "False", "False"});
            long gameTime = context.computeOnClient(client -> client.level.getGameTime());
            for (boolean enabled : new boolean[]{true, false}) {
                context.runOnClient(client -> client.options.cloudStatus().set(
                        enabled ? clouds : net.minecraft.client.CloudStatus.OFF));
                String phase = enabled ? "shore-clouds" : "shore-no-clouds";
                setWaterOptions(context, new String[]{keys[0], keys[3]}, new String[]{"0", "False"});
                captureSettledWaterFrame(context, phase);
                if (Boolean.getBoolean("kavora.gametest.waterNativeFrameDump")) captureNativeWaterFrame(context);
                if (Boolean.getBoolean("kavora.gametest.waterNativeFrameDumpOnly")) continue;
                for (int index : new int[]{160, 203, 205, 206, 202, 204}) {
                    setWaterOptions(context, new String[]{keys[2]}, new String[]{index == 204 ? "1" : "64"});
                    captureAccumulatedWaterView(context, index, phase + "-" + index);
                }
            }
            long completedGameTime = context.computeOnClient(client -> client.level.getGameTime());
            LOGGER.info("Frozen shore probe game time: {} -> {}", gameTime, completedGameTime);
            if (gameTime != completedGameTime) throw new AssertionError("Shore probe scene advanced between components");
        } finally {
            context.runOnClient(client -> client.options.cloudStatus().set(clouds));
            setWaterOptions(context, keys, previous);
        }
    }

    private static void captureNativeWaterFrame(ClientGameTestContext context) {
        String captureDebug = context.computeOnClient(client ->
                KavoraMod.renderer.getRemixApi().getConfigVariableResolved("rtx.captureDebugImage"));
        try {
            setWaterOptions(context, new String[]{"rtx.captureDebugImage"}, new String[]{"True"});
            long frame = context.computeOnClient(client -> KavoraFrameProbe.presents);
            context.runOnClient(client -> KavoraMod.renderer.getRemixApi()
                    .setConfigVariable("rtx.captureNextFrame", "True"));
            context.waitFor(client -> KavoraFrameProbe.presents >= frame + 2
                    && "False".equalsIgnoreCase(KavoraMod.renderer.getRemixApi()
                            .getConfigVariableResolved("rtx.captureNextFrame")), 200);
        } finally {
            setWaterOptions(context, new String[]{"rtx.captureDebugImage"}, new String[]{captureDebug});
        }
    }

    private static void setWaterOptions(ClientGameTestContext context, String[] keys, String[] values) {
        context.runOnClient(client -> {
            var api = KavoraMod.renderer.getRemixApi();
            for (int i = 0; i < keys.length; i++) api.setConfigVariable(keys[i], values[i]);
        });
        context.waitFor(client -> {
            var api = KavoraMod.renderer.getRemixApi();
            for (int i = 0; i < keys.length; i++) {
                if (!values[i].equalsIgnoreCase(api.getConfigVariableResolved(keys[i]))) return false;
            }
            return true;
        }, 100);
    }

    private static void captureWaterOption(ClientGameTestContext context, String key, String value, String name) {
        String previous = context.computeOnClient(client -> KavoraMod.renderer.getRemixApi().getConfigVariableResolved(key));
        try {
            context.runOnClient(client -> KavoraMod.renderer.getRemixApi().setConfigVariable(key, value));
            context.waitFor(client -> value.equalsIgnoreCase(KavoraMod.renderer.getRemixApi().getConfigVariableResolved(key)), 100);
            captureSettledWaterFrame(context, name);
        } finally {
            context.runOnClient(client -> KavoraMod.renderer.getRemixApi().setConfigVariable(key, previous));
            // Runtime option writes resolve on a later frame. Do not let the next probe
            // snapshot this probe's still-active value as its own restoration target.
            context.waitFor(client -> previous.equalsIgnoreCase(
                    KavoraMod.renderer.getRemixApi().getConfigVariableResolved(key)), 100);
        }
    }

    private static void captureSettledWaterFrame(ClientGameTestContext context, String name) {
        long frame = context.computeOnClient(client -> KavoraFrameProbe.presents);
        context.waitFor(client -> KavoraFrameProbe.presents >= frame + 60, 200);
        Path screenshot = KavoraWorldGameTest.captureCompletedFrame(context, "water-debug-" + name);
        LOGGER.info("Water diagnostic {}: {}", name, screenshot);
    }

    private static void flyLeg(ClientGameTestContext context, boolean outbound, int legChunks, FarProbe retained) {
        boolean[] hadFarCoverage = {false};
        context.getInput().holdKey(options -> options.keyUp);
        for (int step = 1; step <= legChunks / 5; step++) {
            int cx = outbound ? step * 5 : legChunks - step * 5;
            double x = cx * 16 + 0.5;
            try {
                context.waitFor(client -> {
                    var chunks = KavoraMod.renderer.getChunkManager();
                    if (chunks.getLodDiagnostics() != null && !chunks.hasTerrainGeometry(retained.x(), retained.z())) {
                        boolean visible = chunks.hasVisibleFarTerrainGeometry(retained.x(), retained.y(), retained.z());
                        if (hadFarCoverage[0] && !visible) {
                            throw new AssertionError("Visited LOD disappeared while moving away: "
                                    + chunks.getFarSectionDiagnostics(retained.x(), retained.y(), retained.z())
                                    + ", camera=" + client.player.position() + ", " + chunks.getLodHandoffDiagnostics());
                        }
                        hadFarCoverage[0] |= visible;
                    }
                    return client.player != null && (outbound ? client.player.getX() >= x : client.player.getX() <= x);
                }, 800);
            } catch (AssertionError failure) {
                context.runOnClient(client -> LOGGER.error("Streaming flight stopped: position={}, flying={}, screen={}",
                        client.player.position(), client.player.getAbilities().flying,
                        client.gui.screen() == null ? "none" : client.gui.screen().getClass().getName()));
                KavoraWorldGameTest.captureCompletedFrame(context, "flight-blocked");
                throw failure;
            }
            context.getInput().releaseKey(options -> options.keyUp);
            context.waitTicks(30);
            verifyNear(context, cx);
            if (step < legChunks / 5) context.getInput().holdKey(options -> options.keyUp);
        }
    }

    private static void verifyNear(ClientGameTestContext context, int cx) {
        context.runOnClient(client -> {
            var sodium = ((SodiumWorldRendererAccessor) SodiumWorldRenderer.instanceNullable()).kavora$sectionManager();
            int sy = SectionPos.blockToSectionCoord(client.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx * 16, 0) - 1);
            LOGGER.info("Streaming column {}: chunk={}, sodiumBuilt={}, captured={}, terrain={}", cx,
                    client.level.hasChunk(cx, 0), sodium.isSectionBuilt(cx, sy, 0),
                    SodiumCapture.builtSections().contains(SectionPos.asLong(cx, sy, 0)),
                    KavoraMod.renderer.getChunkManager().getTerrainDiagnostics());
        });
        try {
            context.waitFor(client -> {
                var chunks = KavoraMod.renderer.getChunkManager();
                for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                    if (!chunks.hasTerrainGeometry(cx + dx, dz)) return false;
                }
                return true;
            }, 100);
        } catch (AssertionError failure) {
            context.runOnClient(client -> {
                LOGGER.error("Blocked streaming handoff at {}: {}", client.player.position(),
                        KavoraMod.renderer.getChunkManager().getLodHandoffDiagnostics());
                var sodium = ((SodiumWorldRendererAccessor) SodiumWorldRenderer.instanceNullable()).kavora$sectionManager();
                for (int z = -1; z <= 1; z++) for (int x = cx - 1; x <= cx + 1; x++) {
                    int y = SectionPos.blockToSectionCoord(client.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x * 16, z * 16) - 1);
                    LOGGER.error("Near column {},{}: surfaceSection={}, chunk={}, built={}, captured={}, geometry={}", x, z, y,
                            client.level.hasChunk(x, z), sodium.isSectionBuilt(x, y, z),
                            SodiumCapture.builtSections().contains(SectionPos.asLong(x, y, z)),
                            KavoraMod.renderer.getChunkManager().hasTerrainGeometry(x, z));
                }
            });
            KavoraWorldGameTest.captureCompletedFrame(context, "streaming-failed");
            throw failure;
        }
        context.runOnClient(client -> LOGGER.info("Streaming checkpoint {} ready: {}", cx,
                KavoraMod.renderer.getChunkManager().getTerrainDiagnostics()));
    }

    private static void awaitAllNear(ClientGameTestContext context) {
        context.waitFor(client -> KavoraMod.renderer.getChunkManager()
                .getTerrainDiagnostics().columnsMissingGeometry() == 0, 200);
    }

    private static void verifyFar(ClientGameTestContext context, int cx, int sy, float yaw, String screenshot) {
        if (!context.computeOnClient(client -> KavoraMod.renderer.getChunkManager().getLodDiagnostics() != null)) return;
        context.runOnClient(client -> {
            client.player.setYRot(yaw);
            client.player.setXRot(Boolean.getBoolean("kavora.gametest.streamingNatural") ? 18 : 8);
        });
        long viewFrame = context.computeOnClient(client -> KavoraFrameProbe.presents);
        context.waitFor(client -> KavoraFrameProbe.presents > viewFrame + 1, 100);
        int[] probes = {0};
        try {
            context.waitFor(client -> {
                var terrain = KavoraMod.renderer.getChunkManager();
                boolean ready = terrain.hasFarTerrainGeometry(cx, sy, 0) && terrain.isFarViewRefined();
                if (!ready && probes[0]++ % 50 == 0)
                    LOGGER.info("Far handoff pending: {}, {}", terrain.getFarSectionDiagnostics(cx, sy, 0), terrain.getLodHandoffDiagnostics());
                return ready;
            }, 200);
        } catch (AssertionError failure) {
            context.runOnClient(client -> LOGGER.error("Missing distant section: {}, {}",
                    KavoraMod.renderer.getChunkManager().getFarSectionDiagnostics(cx, sy, 0),
                    KavoraMod.renderer.getChunkManager().getLodHandoffDiagnostics()));
            KavoraWorldGameTest.captureCompletedFrame(context, "far-handoff-failed");
            throw failure;
        }
        context.runOnClient(client -> {
            if (KavoraMod.renderer.getChunkManager().hasTerrainGeometry(cx, 0)) {
                throw new AssertionError("Distant-section check still has near geometry");
            }
            LOGGER.info("Streaming settled: terrain={}, far={}",
                    KavoraMod.renderer.getChunkManager().getTerrainDiagnostics(),
                    KavoraMod.renderer.getChunkManager().getLodHandoffDiagnostics());
            client.player.setYRot(yaw);
            client.player.setXRot(Boolean.getBoolean("kavora.gametest.streamingNatural") ? 18 : 8);
        });
        context.waitTicks(20);
        KavoraWorldGameTest.captureCompletedFrame(context, screenshot);
    }

    private static Recording startRecording() {
        String destination = System.getProperty("kavora.gametest.streamingJfr");
        if (destination == null) return null;
        try {
            Recording recording = new Recording(Configuration.getConfiguration("profile"));
            try {
                recording.setDestination(Path.of(destination));
                recording.setDumpOnExit(true);
                recording.start();
                return recording;
            } catch (IOException | RuntimeException failure) {
                recording.close();
                throw failure;
            }
        } catch (IOException | ParseException failure) {
            throw new AssertionError("Cannot start streaming profile", failure);
        }
    }
}
