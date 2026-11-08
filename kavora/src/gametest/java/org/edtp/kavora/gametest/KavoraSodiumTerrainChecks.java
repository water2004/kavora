/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.gametest;

import org.edtp.kavora.KavoraMod;
import org.edtp.kavora.compat.sodium.SodiumCapture;
import org.edtp.kavora.compat.sodium.SodiumRasterSection;
import org.edtp.kavora.compat.sodium.mixin.SodiumSectionManagerAccessor;
import org.edtp.kavora.compat.sodium.mixin.SodiumWorldRendererAccessor;
import org.edtp.kavora.gametest.mixin.SodiumTerrainTestAccess;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.data.PresentTranslucentData;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Checks real terrain ownership, GPU arenas and recapture across raster/Remix transitions. */
final class KavoraSodiumTerrainChecks {
    private static final Logger LOGGER = LoggerFactory.getLogger(KavoraSodiumTerrainChecks.class);
    private static final int TIMEOUT = 20 * 120;

    static void run(ClientGameTestContext context, TestSingleplayerContext world) {
        var fixtureOrigin = context.computeOnClient(client -> client.player.blockPosition().immutable());
        var waterBlock = fixtureOrigin.offset(0, 0, 8);
        long fixtureSection = SectionPos.of(waterBlock).asLong();
        long fixtureColumn = ChunkPos.pack(SectionPos.x(fixtureSection), SectionPos.z(fixtureSection));
        world.getServer().runOnServer(server -> {
            int x = fixtureOrigin.getX(), y = fixtureOrigin.getY(), z = fixtureOrigin.getZ();
            KavoraSceneFixtures.commands(server,
                    "fill " + (x - 6) + " " + (y - 2) + " " + (z + 3) + " "
                            + (x + 6) + " " + y + " " + (z + 14) + " minecraft:stone",
                    "fill " + (x - 5) + " " + (y - 1) + " " + (z + 4) + " "
                            + (x + 5) + " " + y + " " + (z + 13) + " minecraft:water",
                    "fill " + (x - 3) + " " + (y + 1) + " " + (z + 10) + " "
                            + (x + 3) + " " + (y + 2) + " " + (z + 10) + " minecraft:glass");
        });
        context.runOnClient(client -> {
            client.player.setYRot(0);
            client.player.setXRot(25);
        });
        context.waitFor(client -> client.level.getBlockState(waterBlock).is(Blocks.WATER)
                && fixtureBuilt(fixtureSection) && settled(), TIMEOUT);
        assertCaptured(context, "initial");

        try {
            var old = context.computeOnClient(client -> manager());
            context.runOnClient(client -> KavoraMod.setWorldEnabled(false));
            awaitRaster(context, old, fixtureSection, "world-off");
            context.runOnClient(client -> require(KavoraMod.isRtxEnabled(),
                    "World-only toggle disabled worker capture"));

            var built = context.computeOnClient(client -> snapshot());
            built.columns().add(fixtureColumn);
            old = context.computeOnClient(client -> manager());
            context.runOnClient(client -> {
                client.player.setXRot(-89);
                KavoraMod.setWorldEnabled(true);
            });
            awaitCaptured(context, old, built, "world-on-looking-away");

            old = context.computeOnClient(client -> manager());
            context.runOnClient(client -> {
                client.player.setXRot(25);
                KavoraMod.setRtxEnabled(false);
            });
            awaitRaster(context, old, fixtureSection, "master-off");
            built = context.computeOnClient(client -> snapshot());
            built.columns().add(fixtureColumn);
            old = context.computeOnClient(client -> manager());
            context.runOnClient(client -> {
                client.player.setXRot(-89);
                KavoraMod.setRtxEnabled(true);
            });
            awaitCaptured(context, old, built, "master-on-looking-away");

            old = context.computeOnClient(client -> manager());
            built = context.computeOnClient(client -> snapshot());
            context.runOnClient(client -> {
                KavoraMod.rebuildLoadedTerrain();
                KavoraMod.setWorldEnabled(false);
                KavoraMod.setRtxEnabled(false);
                KavoraMod.setWorldEnabled(true);
                KavoraMod.setRtxEnabled(true);
                KavoraMod.rebuildLoadedTerrain();
            });
            awaitCaptured(context, old, built, "coalesced-reloads");
        } finally {
            context.runOnClient(client -> {
                KavoraMod.setWorldEnabled(true);
                KavoraMod.setRtxEnabled(true);
                client.player.setXRot(25);
            });
        }
        KavoraWorldGameTest.requireVisibleWorld(KavoraWorldGameTest.captureCompletedFrame(context, "sodium-restored"));
    }

    static void assertCaptured(ClientGameTestContext context, String phase) {
        long first = context.computeOnClient(client -> KavoraFrameProbe.presents);
        context.waitFor(client -> {
            require(KavoraMod.isRemixWorldRendering(), "Remix terrain does not own " + phase);
            requireEmptyArenas(regions(manager()), phase);
            return KavoraFrameProbe.presents >= first + 8;
        }, TIMEOUT);
        context.runOnClient(client -> LOGGER.info("Sodium terrain {}: zero raster geometry/index arenas; {} captured sections",
                phase, SodiumCapture.builtSections().size()));
    }

    static void assertTextureReload(ClientGameTestContext context) {
        var previous = context.computeOnClient(client -> manager());
        var built = context.computeOnClient(client -> snapshot());
        float pitch = context.computeOnClient(client -> client.player.getXRot());
        try {
            context.runOnClient(client -> {
                client.player.setXRot(-89);
                KavoraMod.renderer.requestTextureReload();
            });
            awaitCaptured(context, previous, built, "texture-reload-looking-away");
        } finally {
            context.runOnClient(client -> client.player.setXRot(pitch));
        }
    }

    private static void awaitCaptured(ClientGameTestContext context, RenderSectionManager previous,
                                      TerrainCoverage expected, String phase) {
        context.waitFor(client -> {
            if (manager() == previous || !SodiumCapture.builtSections().containsAll(expected.sections())
                    || !settled()) return false;
            var terrain = KavoraMod.renderer.getChunkManager();
            for (long column : expected.columns()) {
                if (!terrain.hasTerrainGeometry(ChunkPos.getX(column), ChunkPos.getZ(column))) return false;
            }
            return true;
        }, TIMEOUT);
        context.runOnClient(client -> {
            require(regions(previous).getLoadedRegions().isEmpty(), "Old Sodium regions survived " + phase);
            requireEmptyArenas(regions(previous), "retired " + phase);
        });
        assertCaptured(context, phase);
    }

    private static void awaitRaster(ClientGameTestContext context, RenderSectionManager previous,
                                    long fixture, String phase) {
        context.waitFor(client -> {
            var current = manager();
            if (current == previous || !fixtureBuilt(fixture)) return false;
            var arenas = regions(current).getArenaAggregator();
            return arenas.getGeometryDeviceUsedMemory() > 0 && arenas.getIndexDeviceUsedMemory() > 0;
        }, TIMEOUT);
        context.runOnClient(client -> {
            require(!KavoraMod.isRemixWorldRendering(), "Raster terrain does not own " + phase);
            var section = ((SodiumSectionManagerAccessor) manager()).kavora$sections().getCurrent(fixture);
            require(!((SodiumRasterSection) section).kavora$isRasterUploadOmitted(),
                    "Raster fixture still omits uploads in " + phase);
            var arenas = regions(manager()).getArenaAggregator();
            LOGGER.info("Sodium terrain {}: geometry={} B, indices={} B", phase,
                    arenas.getGeometryDeviceUsedMemory(), arenas.getIndexDeviceUsedMemory());
        });
        KavoraWorldGameTest.requireVisibleWorld(KavoraWorldGameTest.captureCompletedFrame(context, "sodium-" + phase));
    }

    private static boolean fixtureBuilt(long key) {
        var section = ((SodiumSectionManagerAccessor) manager()).kavora$sections().getCurrent(key);
        return section != null && section.isBuilt()
                && section.getTranslucentData() instanceof PresentTranslucentData data
                && data.getIndexQuadCount() > 0;
    }

    private static boolean settled() {
        var terrain = KavoraMod.renderer.getChunkManager().getTerrainDiagnostics();
        return terrain.columnsWithGeometry() > 0 && terrain.columnsMissingGeometry() == 0
                && terrain.pendingBuilds() == 0 && terrain.readyMeshes() == 0 && terrain.captureQueue() == 0;
    }

    private static RenderSectionManager manager() {
        return ((SodiumWorldRendererAccessor) SodiumWorldRenderer.instanceNullable()).kavora$sectionManager();
    }

    private static RenderRegionManager regions(RenderSectionManager manager) {
        return ((SodiumTerrainTestAccess) manager).kavora$regions();
    }

    private record TerrainCoverage(LongSet sections, LongSet columns) {}

    private static TerrainCoverage snapshot() {
        LongSet sections = SodiumCapture.builtSections();
        LongSet columns = new LongOpenHashSet();
        var terrain = KavoraMod.renderer.getChunkManager();
        for (long section : sections) {
            int x = SectionPos.x(section), z = SectionPos.z(section);
            if (terrain.hasTerrainGeometry(x, z)) columns.add(ChunkPos.pack(x, z));
        }
        return new TerrainCoverage(sections, columns);
    }

    private static void requireEmptyArenas(RenderRegionManager regions, String phase) {
        var arenas = regions.getArenaAggregator();
        require(arenas.getGeometryDeviceUsedMemory() == 0 && arenas.getIndexDeviceUsedMemory() == 0
                        && arenas.getGeometryDeviceAllocatedMemory() == 0 && arenas.getIndexDeviceAllocatedMemory() == 0
                        && arenas.getMiscAllocatedMemory() == 0 && arenas.getBufferCount() == 0,
                "Unused Sodium raster GPU allocations remain in " + phase);
        for (var region : regions.getLoadedRegions()) {
            require(region.getResources() == null, "Raster region resources remain in " + phase);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private KavoraSodiumTerrainChecks() {}
}
