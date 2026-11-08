/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.gametest;

import org.edtp.kavora.KavoraRenderer;
import org.edtp.kavora.KavoraMod;
import org.edtp.kavora.render.RenderBackend;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** Exercises real resource loading, native rendering, and world-session replacement. */
public final class KavoraWorldGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(KavoraWorldGameTest.class);
    private static final int READY_TIMEOUT_TICKS = 20 * 120;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (Boolean.getBoolean("kavora.gametest.dimensionColourOnly")) return;
        if (Boolean.getBoolean("kavora.gametest.cloudsOnly")) return;
        if (Boolean.getBoolean("kavora.gametest.beaconOnly") || Boolean.getBoolean("kavora.gametest.particleOnly")
                || Boolean.getBoolean("kavora.gametest.lightingOnly")
                || Boolean.getBoolean("kavora.gametest.retainedOnly")) return;
        context.runOnClient(client -> {
            String expected = System.getProperty("kavora.gametest.expectedBackend");
            require(expected == null || RenderBackend.current().name().equalsIgnoreCase(expected),
                    "Minecraft could not start the requested " + expected + " backend");
        });
        int width = Integer.getInteger("kavora.gametest.width", 0);
        int height = Integer.getInteger("kavora.gametest.height", 2160);
        if (width > 0) {
            context.getInput().resizeWindow(width, height);
            context.waitFor(client -> client.getWindow().getWidth() == width && client.getWindow().getHeight() == height, 200);
        }
        String waterFixture = System.getProperty("kavora.gametest.waterFixture");
        String lodFixture = System.getProperty("kavora.gametest.lodFixture");
        if (lodFixture != null) {
            KavoraLodFixtureChecks.run(context, lodFixture);
            return;
        }
        if (waterFixture != null) {
            captureWaterFixture(context, waterFixture);
            return;
        }
        TestWorldSave save;
        long farGeneration;
        KavoraStreamingChecks.FarProbe farProbe = null;
        var worldBuilder = context.worldBuilder();
        if (Boolean.getBoolean("kavora.gametest.nightSkyNormalDisplay")) {
            worldBuilder.setUseConsistentSettings(false);
        }
        boolean naturalTerrain = Boolean.getBoolean("kavora.gametest.streamingNatural");
        boolean waterTerrain = Boolean.getBoolean("kavora.gametest.streamingWater")
                || Boolean.getBoolean("kavora.gametest.nightSkyOnly");
        if (naturalTerrain) {
            // Keep the terrain within Sodium's section distance while flying above hills.
            context.runOnClient(client -> client.options.renderDistance().set(
                    Integer.getInteger("kavora.gametest.renderDistance", 8)));
            worldBuilder.setUseConsistentSettings(false);
        }
        worldBuilder.adjustSettings(settings -> {
            settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
            if (naturalTerrain) {
                settings.setSeed("1");
                settings.setGenerateStructures(false);
            }
            if (waterTerrain) {
                settings.setWorldType(settings.getNormalPresetList().stream()
                        .filter(entry -> entry.preset().is(net.minecraft.world.level.levelgen.presets.WorldPresets.FLAT))
                        .findFirst().orElseThrow());
                settings.updateDimensions((registries, dimensions) -> {
                    var generator = (net.minecraft.world.level.levelgen.FlatLevelSource) dimensions.overworld();
                    var flat = generator.settings().withBiomeAndLayers(java.util.List.of(
                            new net.minecraft.world.level.levelgen.flat.FlatLayerInfo(1, net.minecraft.world.level.block.Blocks.BEDROCK),
                            new net.minecraft.world.level.levelgen.flat.FlatLayerInfo(1, net.minecraft.world.level.block.Blocks.SAND),
                            new net.minecraft.world.level.levelgen.flat.FlatLayerInfo(4, net.minecraft.world.level.block.Blocks.WATER)),
                            generator.settings().structureOverrides(), generator.settings().getBiome());
                    return dimensions.replaceOverworldGenerator(registries,
                            new net.minecraft.world.level.levelgen.FlatLevelSource(flat));
                });
            }
        });
        try (TestSingleplayerContext world = worldBuilder.create()) {
            save = world.getWorldSave();
            assertWorldRenders(context, world, "created");
            if (Boolean.getBoolean("kavora.gametest.nightSkyOnly")) {
                KavoraNightSkyChecks.run(context, world);
                return;
            }
            if (Boolean.getBoolean("kavora.gametest.qualityVisualOnly")) {
                KavoraQualitySceneChecks.run(context, world);
                return;
            }
            if (Boolean.getBoolean("kavora.gametest.cloudMirrorOnly")) {
                KavoraCloudMirrorChecks.run(context, world);
                return;
            }
            if (Boolean.getBoolean("kavora.gametest.joinOnly")) return;
            if (Boolean.getBoolean("kavora.gametest.reconstructionInputs")) {
                assertReconstructionInputs(context);
                assertWorldResize(context);
            } else if (Boolean.getBoolean("kavora.gametest.terrainReloadOnly")) {
                KavoraSodiumTerrainChecks.run(context, world);
                assertTerrainReload(context, world);
            } else if (Boolean.getBoolean("kavora.gametest.streamingOnly")) {
                farProbe = KavoraStreamingChecks.run(context, world);
            } else {
                KavoraSettingsChecks.run(context);
                if (Boolean.getBoolean("kavora.gametest.settingsOnly")) return;
                assertWorldResize(context);
                assertTerrainReload(context, world);
                if (Boolean.getBoolean("kavora.gametest.includeStreaming")) farProbe = KavoraStreamingChecks.run(context, world);
            }
            farGeneration = context.computeOnClient(client -> {
                var stats = KavoraMod.renderer.getChunkManager().getLodDiagnostics();
                return stats == null ? -1L : stats.generation();
            });
        }
        assertDisconnected(context);
        KavoraStreamingChecks.FarProbe reopenProbe = farProbe;
        try (TestSingleplayerContext world = save.open()) {
            assertWorldRenders(context, world, "rejoined");
            if (Boolean.getBoolean("kavora.gametest.terrainReloadOnly")) {
                KavoraSodiumTerrainChecks.assertCaptured(context, "rejoined");
            }
            if (farGeneration >= 0) {
                try {
                    context.waitFor(client -> {
                        var stats = KavoraMod.renderer.getChunkManager().getLodDiagnostics();
                        require(stats != null && stats.failedCells() == 0, "Far terrain failed after world reopen");
                        return stats.generation() > farGeneration && stats.visibleCells() > 0;
                    }, 200);
                    // A cold scene must produce its first geometry promptly. Rebuilding the
                    // entire visited radius then uses the same bounded readiness budget as a
                    // terrain reload; uploads deliberately span frames instead of blocking one.
                    if (reopenProbe != null) {
                        context.waitFor(client -> reopenProbe.ready()
                                && KavoraMod.renderer.getChunkManager().isFarViewRefined(), READY_TIMEOUT_TICKS);
                        requireVisibleWorld(captureCompletedFrame(context, "rejoined-far-refined"));
                    }
                } catch (AssertionError failure) {
                    context.runOnClient(client -> LOGGER.error("Far terrain reopen failed: {}",
                            KavoraMod.renderer.getChunkManager().getLodHandoffDiagnostics()));
                    throw failure;
                }
                context.runOnClient(client -> LOGGER.info("Far terrain reopened: {}",
                        KavoraMod.renderer.getChunkManager().getLodHandoffDiagnostics()));
            }
        }
        assertDisconnected(context);
    }

    /** Uses an isolated copy of an observed world, independently of streaming-readiness assertions. */
    private static void captureWaterFixture(ClientGameTestContext context, String fixture) {
        prepareWaterFixture(context.computeOnClient(client -> client.getLevelSource().getLevelPath(fixture)));
        context.runOnClient(client -> {
            client.options.renderDistance().set(Integer.getInteger("kavora.gametest.renderDistance", 16));
            client.options.cloudStatus().set(net.minecraft.client.CloudStatus.FANCY);
        });
        context.runOnClient(client -> client.createWorldOpenFlows().openWorld(fixture,
                () -> { throw new AssertionError("Water fixture load was cancelled"); }));
        context.waitFor(client -> client.level != null && client.player != null
                && client.getSingleplayerServer() != null && client.gui.screen() == null, READY_TIMEOUT_TICKS);
        var setup = context.computeOnClient(client -> {
            var server = client.getSingleplayerServer();
            return CompletableFuture.runAsync(() -> {
                server.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME, false, server);
                KavoraSceneFixtures.commands(server, "weather clear");
                server.clockManager().setTotalTicks(server.registryAccess()
                        .get(net.minecraft.world.clock.WorldClocks.OVERWORLD).orElseThrow(), 6000);
                var player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
                boolean shore = Boolean.getBoolean("kavora.gametest.frameGenerationOnly")
                        || Boolean.getBoolean("kavora.gametest.waterTemporalOnly");
                player.getAbilities().flying = !shore;
                player.onUpdateAbilities();
                require(player.teleportTo(server.overworld(), shore ? 496.5 : 480.5, shore ? 63 : 160,
                        shore ? 87.5 : .5, java.util.Set.of(), shore ? 0 : 90, 18, true),
                        "Water fixture camera setup failed");
            }, server);
        });
        context.waitFor(client -> setup.isDone(), 200);
        setup.join();
        context.waitFor(client -> KavoraMod.renderer.isSceneReady()
                && KavoraMod.renderer.getChunkManager().getTerrainDiagnostics().columnsMissingGeometry() == 0
                && KavoraMod.renderer.getCloudRenderer().getLastDrawCount() > 0,
                READY_TIMEOUT_TICKS);
        boolean freezeScene = Boolean.getBoolean("kavora.gametest.waterShoreCompositeOnly");
        if (freezeScene) setWaterFixtureFrozen(context, true);
        try {
            context.runOnClient(client -> KavoraMod.renderer.getRemixApi()
                    .setConfigVariable("rtx.debugView.debugViewIdx", "0"));
            if (Boolean.getBoolean("kavora.gametest.waterTemporalOnly")) KavoraWaterTemporalChecks.run(context);
            else if (Boolean.getBoolean("kavora.gametest.frameGenerationOnly")) KavoraFrameGenerationChecks.run(context);
            else KavoraStreamingChecks.captureWaterDiagnostics(context);
        } finally {
            if (freezeScene) setWaterFixtureFrozen(context, false);
            var closingServer = context.computeOnClient(client -> client.getSingleplayerServer());
            context.runOnClient(client -> {
                var message = net.minecraft.network.chat.Component.translatable("menu.savingLevel");
                client.level.disconnect(message);
                client.disconnect(new net.minecraft.client.gui.screens.GenericMessageScreen(message), false);
            });
            context.waitFor(client -> !closingServer.getRunningThread().isAlive(), READY_TIMEOUT_TICKS);
            context.setScreen(net.minecraft.client.gui.screens.TitleScreen::new);
        }
        assertDisconnected(context);
    }

    private static void setWaterFixtureFrozen(ClientGameTestContext context, boolean frozen) {
        var completion = context.computeOnClient(client -> {
            var server = client.getSingleplayerServer();
            return CompletableFuture.runAsync(() -> KavoraSceneFixtures.commands(server,
                    frozen ? "tick freeze" : "tick unfreeze"), server);
        });
        context.waitFor(client -> completion.isDone(), 200);
        completion.join();
    }

    /** Persist safe player state before the server can tick during terrain loading. */
    private static void prepareWaterFixture(Path world) {
        try {
            Path levelPath = world.resolve(LevelResource.LEVEL_DATA_FILE.id());
            var level = NbtIo.readCompressed(levelPath, NbtAccounter.unlimitedHeap());
            level.getCompound("Data").orElseThrow().putInt("GameType", 1);
            NbtIo.writeCompressed(level, levelPath);
            try (var players = Files.list(world.resolve(LevelResource.PLAYER_DATA_DIR.id()))) {
                for (Path path : players.filter(p -> p.getFileName().toString().endsWith(".dat")).toList()) {
                    var player = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
                    player.putInt("playerGameType", 1);
                    player.putFloat("Health", 20);
                    player.putShort("DeathTime", (short) 0);
                    player.putDouble("fall_distance", 0);
                    var abilities = player.getCompound("abilities").orElseThrow();
                    abilities.putBoolean("mayfly", true);
                    abilities.putBoolean("flying", true);
                    abilities.putBoolean("invulnerable", true);
                    abilities.putBoolean("instabuild", true);
                    NbtIo.writeCompressed(player, path);
                }
            }
        } catch (IOException e) {
            throw new AssertionError("Could not prepare the isolated water fixture", e);
        }
    }

    private static void assertTerrainReload(ClientGameTestContext context, TestSingleplayerContext world) {
        KavoraSodiumTerrainChecks.assertTextureReload(context);
        assertWorldRenders(context, world, "material-reloaded");
    }

    private static void assertReconstructionInputs(ClientGameTestContext context) {
        String[] keys = {"rtx.graphicsPreset", "rtx.upscalerType", "rtx.enableRayReconstruction",
                "rtx.sparseRendering.enableSparseRendering", "rtx.rayreconstruction.enableDisocclusionMaskBlur",
                "rtx.rayreconstruction.useSpecularHitDistance", "rtx.postfx.enable", "rtx.postfx.enableMotionBlur"};
        var original = context.computeOnClient(client -> {
            var values = new java.util.LinkedHashMap<String, String>();
            for (String key : keys) values.put(key, KavoraMod.renderer.getRemixApi().getConfigVariableResolved(key));
            return values;
        });
        try {
            context.runOnClient(client -> {
                var api = KavoraMod.renderer.getRemixApi();
                api.setConfigVariable(keys[0], "4");
                api.setConfigVariable(keys[1], "1");
                api.setConfigVariable(keys[2], "true");
                api.setConfigVariable(keys[6], "true");
                api.setConfigVariable(keys[7], "true");
            });
            for (boolean sparse : new boolean[] {false, true}) {
                for (boolean blur : new boolean[] {false, true}) {
                    for (boolean hitDistance : new boolean[] {false, true}) {
                        context.runOnClient(client -> {
                            var api = KavoraMod.renderer.getRemixApi();
                            api.setConfigVariable(keys[3], Boolean.toString(sparse));
                            api.setConfigVariable(keys[4], Boolean.toString(blur));
                            api.setConfigVariable(keys[5], Boolean.toString(hitDistance));
                        });
                        context.waitFor(client -> {
                            var api = KavoraMod.renderer.getRemixApi();
                            return Boolean.parseBoolean(api.getConfigVariableResolved(keys[2]))
                                    && Boolean.parseBoolean(api.getConfigVariableResolved(keys[3])) == sparse
                                    && Boolean.parseBoolean(api.getConfigVariableResolved(keys[4])) == blur
                                    && Boolean.parseBoolean(api.getConfigVariableResolved(keys[5])) == hitDistance
                                    && Boolean.parseBoolean(api.getConfigVariableResolved(keys[6]))
                                    && Boolean.parseBoolean(api.getConfigVariableResolved(keys[7]));
                        }, 200);
                        long frame = context.computeOnClient(client -> KavoraFrameProbe.presents);
                        context.waitFor(client -> KavoraFrameProbe.presents >= frame + 40
                                && KavoraMod.renderer.isSceneReady(), READY_TIMEOUT_TICKS);
                        String phase = "rr-sparse-" + sparse + "-blur-" + blur + "-hit-" + hitDistance;
                        requireVisibleWorld(captureCompletedFrame(context, phase));
                        context.runOnClient(client -> require(KavoraFrameProbe.failures == 0,
                                "Native frame failed while switching RR inputs: " + phase));
                    }
                }
            }
            assertPipelineFeatureTransitions(context);
        } finally {
            context.runOnClient(client -> original.forEach((key, value) ->
                    KavoraMod.renderer.getRemixApi().setConfigVariable(key, value)));
        }
    }

    private static void assertPipelineFeatureTransitions(ClientGameTestContext context) {
        String[] keys = {"rtx.enableRayReconstruction", "rtx.enablePSRR", "rtx.enablePSTR",
                "rtx.raytracedRenderTarget.enable", "rtx.enableStochasticAlphaBlend",
                "rtx.enableShaderExecutionReorderingInPathtracerGbuffer"};
        var original = context.computeOnClient(client -> {
            var values = new java.util.LinkedHashMap<String, String>();
            for (String key : keys) values.put(key, KavoraMod.renderer.getRemixApi().getConfigVariableResolved(key));
            return values;
        });
        boolean[][] cases = {
                {false, false, false, false, false, true},
                {false, true, true, false, false, true},
                {true, false, false, false, false, true},
                {true, true, true, false, false, true},
                {true, true, true, true, false, true},
                {true, true, true, false, true, true},
                {true, true, true, false, false, true},
                {true, true, true, false, false, false},
                {true, true, true, false, false, true}
        };
        try {
            for (int index = 0; index < cases.length; index++) {
                boolean[] values = cases[index];
                context.runOnClient(client -> {
                    var api = KavoraMod.renderer.getRemixApi();
                    for (int key = 0; key < keys.length; key++) {
                        api.setConfigVariable(keys[key], Boolean.toString(values[key]));
                    }
                });
                context.waitFor(client -> {
                    var api = KavoraMod.renderer.getRemixApi();
                    for (int key = 0; key < keys.length; key++) {
                        if (Boolean.parseBoolean(api.getConfigVariableResolved(keys[key])) != values[key]) return false;
                    }
                    return true;
                }, 200);
                long frame = context.computeOnClient(client -> KavoraFrameProbe.presents);
                context.waitFor(client -> KavoraFrameProbe.presents >= frame + 40
                        && KavoraMod.renderer.isSceneReady(), READY_TIMEOUT_TICKS);
                String phase = "pipeline-features-" + index;
                requireVisibleWorld(captureCompletedFrame(context, phase));
                context.runOnClient(client -> require(KavoraFrameProbe.failures == 0,
                        "Native frame failed while switching pipeline features: " + phase));
            }
        } finally {
            context.runOnClient(client -> original.forEach((key, value) ->
                    KavoraMod.renderer.getRemixApi().setConfigVariable(key, value)));
        }
    }

    private static void assertWorldResize(ClientGameTestContext context) {
        int[] original = context.computeOnClient(client -> new int[] {
                client.getWindow().getWidth(), client.getWindow().getHeight()});
        int[][] sizes = {{1280, 720}, {1600, 900}, original};
        for (int[] size : sizes) {
            context.getInput().resizeWindow(size[0], size[1]);
            context.waitFor(client -> client.getWindow().getWidth() == size[0]
                    && client.getWindow().getHeight() == size[1], 200);
            long frame = context.computeOnClient(client -> KavoraFrameProbe.presents);
            context.waitFor(client -> KavoraFrameProbe.presents >= frame + 5
                    && KavoraMod.renderer.isSceneReady(), 200);
            requireVisibleWorld(captureCompletedFrame(context, "resized-" + size[0] + "x" + size[1]));
            context.runOnClient(client -> require(KavoraFrameProbe.failures == 0,
                    "Native frame failed while replacing shared Vulkan images"));
        }
    }

    static Path assertWorldRenders(ClientGameTestContext context,
                                          TestSingleplayerContext world, String phase) {
        world.getServer().runOnServer(server -> {
            var key = ResourceKey.create(Registries.RECIPE,
                    Identifier.fromNamespaceAndPath("kavora", "copper_beacon"));
            require(server.getRecipeManager().byKey(key).isPresent(),
                    "Copper beacon recipe was rejected during resource loading");
        });
        context.runOnClient(client -> require(KavoraMod.renderer != null
                        && KavoraMod.renderer.isInitialized(),
                "Remix initialization failed; inspect the preceding native/interop error"));
        context.waitFor(client -> client.level != null && client.player != null
                && client.getSingleplayerServer() != null && client.gui.screen() == null,
                READY_TIMEOUT_TICKS);
        context.waitFor(client -> {
            KavoraRenderer renderer = KavoraMod.renderer;
            return renderer != null && renderer.isInitialized()
                    && renderer.isSceneReady()
                    && renderer.getChunkManager().getTerrainDiagnostics().columnsWithGeometry() > 0
                    && renderer.getChunkManager().getTerrainDiagnostics().retainedPages() > 0;
        }, READY_TIMEOUT_TICKS);
        context.runOnClient(client -> {
            assertGrassAtlasPlacement();
            assertNativeMaterialPolicies();
            org.edtp.kavora.entity.KavoraRenderTypeChecks.assertBeaconLayers();
            KavoraRenderer renderer = KavoraMod.renderer;
            RenderBackend actual = RenderBackend.current();
            require(renderer.getBackend() == actual, "Remix and Minecraft backends differ");
            PreferredGraphicsApi selected = client.options.preferredGraphicsBackend().get();
            if (selected != PreferredGraphicsApi.DEFAULT) {
                require(actual.name().equals(selected.name()),
                        "Minecraft fell back from selected backend " + selected + " to " + actual);
            }
            String expected = System.getProperty("kavora.gametest.expectedBackend");
            if (expected != null) {
                require(actual.name().equalsIgnoreCase(expected),
                        "Expected " + expected + " but Minecraft selected " + actual);
            }
            LOGGER.info("GameTest {}: native Remix initialized, backend={}, selected={}, terrain={}",
                    phase, actual, selected, renderer.getChunkManager().getTerrainDiagnostics());
        });
        long[] frames = context.computeOnClient(client ->
                new long[] {KavoraFrameProbe.submits, KavoraFrameProbe.presents});
        context.waitFor(client -> KavoraFrameProbe.submits >= frames[0] + 40
                && KavoraFrameProbe.presents >= frames[1] + 40, READY_TIMEOUT_TICKS);
        Path screenshot = captureCompletedFrame(context, phase);
        require(Files.isRegularFile(screenshot), "Screenshot was not written: " + screenshot);
        requireVisibleWorld(screenshot);
        context.runOnClient(client -> require(KavoraFrameProbe.failures == 0,
                "Scene capture or native submission failed during the test, including screenshot rendering"));
        LOGGER.info("GameTest {} screenshot: {}", phase, screenshot.toAbsolutePath());
        return screenshot;
    }

    static Path captureCompletedFrame(ClientGameTestContext context, String phase) {
        CompletableFuture<Path> result = context.computeOnClient(client -> {
            Path destination = client.gameDirectory.toPath().resolve("screenshots")
                    .resolve("remix-world-" + phase + ".png");
            // Fabric's screenshot helper renders GameRenderer directly, bypassing the
            // Minecraft.renderFrame hooks that pair Remix's frame start and present.
            // Read the previous completed frame while ordinary frames keep advancing.
            return captureTarget(client.gameRenderer.mainRenderTarget(), destination);
        });
        context.waitFor(client -> result.isDone(), READY_TIMEOUT_TICKS);
        return result.join();
    }

    static Path captureNativeMenuFrame(ClientGameTestContext context, String phase) {
        CompletableFuture<Path> result = new CompletableFuture<>();
        context.runOnClient(client -> {
            Path destination = client.gameDirectory.toPath().resolve("screenshots")
                    .resolve("remix-world-" + phase + ".png");
            // Native menus are composed after GameRenderer and ordinary test tasks.
            KavoraFrameProbe.afterNativeMenus = () -> captureTarget(client.gameRenderer.mainRenderTarget(), destination)
                    .whenComplete((path, error) -> {
                        if (error == null) result.complete(path);
                        else result.completeExceptionally(error);
                    });
        });
        try {
            context.waitFor(client -> result.isDone(), READY_TIMEOUT_TICKS);
            return result.join();
        } finally {
            context.runOnClient(client -> KavoraFrameProbe.afterNativeMenus = null);
        }
    }

    private static CompletableFuture<Path> captureTarget(RenderTarget target, Path destination) {
        CompletableFuture<Path> captured = new CompletableFuture<>();
        Screenshot.takeScreenshot(target, image -> {
            try (image) {
                Files.createDirectories(destination.getParent());
                image.writeToFile(destination);
            } catch (IOException | RuntimeException e) {
                captured.completeExceptionally(e);
                return;
            }
            captured.complete(destination);
        });
        return captured;
    }

    private static void assertDisconnected(ClientGameTestContext context) {
        context.waitFor(client -> client.level == null && client.getSingleplayerServer() == null,
                READY_TIMEOUT_TICKS);
    }

    private static void assertNativeMaterialPolicies() {
        var api = KavoraMod.renderer.getRemixApi();
        for (String key : new String[] { "rtx.materials.enableUvBoundsLooping",
                "rtx.ignoreSkyIfCameraInMedium", "rtx.gpuResidentScene",
                "rtx.neuralRadianceCache.wrapPositionsToSceneBounds",
                "rtx.spatiallyHashedRadianceCache.queryForThinOpaquePrimaryView",
                "rtx.subsurface.disableThinOpaqueInSecondaryRays" }) {
            require(Boolean.parseBoolean(api.getConfigVariableResolved(key)),
                    "Shipped native policy is missing or disabled: " + key);
        }
        require(!Boolean.parseBoolean(api.getConfigVariableResolved("rtx.subsurface.enableThinOpaqueInRefractions")),
                "Thin opaque SSS must use the shipped refraction policy");
    }

    private static void assertGrassAtlasPlacement() {
        var atlas = org.edtp.kavora.texture.TextureSourceRegistry.registeredAtlas(
                org.edtp.kavora.texture.BlockTextureManager.BLOCK_ATLAS_ID);
        require(atlas != null, "Block atlas was not registered");
        var layout = (org.edtp.kavora.mixin.SpriteAtlasAccessor) atlas;
        var sprite = layout.getSprites().get(Identifier.withDefaultNamespace("block/grass_block_top"));
        require(sprite != null, "Grass sprite missing from block atlas");
        var image = ((org.edtp.kavora.mixin.SpriteContentsAccessor) sprite.contents()).getImage();
        byte[] stitched = org.edtp.kavora.texture.BlockTextureManager.stitchAtlasRGBA(atlas);
        int originX = Math.round(sprite.getU0() * layout.getWidth());
        int originY = Math.round(sprite.getV0() * layout.getHeight());
        for (int y = 0; y < sprite.contents().height(); y++) {
            for (int x = 0; x < sprite.contents().width(); x++) {
                int offset = ((originY + y) * layout.getWidth() + originX + x) * 4;
                int actual = (stitched[offset] & 255) | ((stitched[offset + 1] & 255) << 8)
                        | ((stitched[offset + 2] & 255) << 16) | ((stitched[offset + 3] & 255) << 24);
                int expected = org.edtp.kavora.texture.NativeImagePixels.abgr(image, x, y);
                require(actual == expected, "Grass atlas texel disagrees with sprite UV placement at " + x + "," + y);
            }
        }
    }

    /** The daylight flat fixture must contain scene pixels outside the HUD and crosshair. */
    static void requireVisibleWorld(Path screenshot) {
        try {
            var image = javax.imageio.ImageIO.read(screenshot.toFile());
            require(image != null, "Screenshot could not be decoded: " + screenshot);
            int visible = 0;
            int height = image.getHeight() * 3 / 4;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int rgb = image.getRGB(x, y);
                    if (((rgb >>> 16) & 255) > 8 || ((rgb >>> 8) & 255) > 8 || (rgb & 255) > 8) visible++;
                }
            }
            require(visible > image.getWidth() * height / 100,
                    "World image is black: " + screenshot);
        } catch (IOException e) {
            throw new AssertionError("Cannot validate world screenshot: " + screenshot, e);
        }
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
