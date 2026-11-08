/* SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package org.edtp.kavora.horizon;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BlockFaceOcclusionTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static int occlusion(Block source, Block neighbour) {
        return BlockFaceOcclusion.between(
                source.defaultBlockState(), neighbour.defaultBlockState(), Direction.UP.ordinal());
    }

    @Test
    void seabedSurvivesCutoutPlantsWithOpaqueAveragePixels() {
        for (Block plant :
                new Block[] {Blocks.KELP, Blocks.KELP_PLANT, Blocks.SEAGRASS, Blocks.TALL_SEAGRASS})
            assertEquals(
                    BlockFaceOcclusion.VISIBLE, occlusion(Blocks.SAND, plant), plant.toString());
    }

    @Test
    void terrainSurvivesLeavesAndPartialBlockModels() {
        for (Block partial : new Block[] {Blocks.OAK_LEAVES, Blocks.OAK_FENCE, Blocks.STONE_SLAB})
            assertEquals(
                    BlockFaceOcclusion.VISIBLE,
                    occlusion(Blocks.STONE, partial),
                    partial.toString());
    }

    @Test
    void waterDoesNotGainInterfacesInsideSubmergedVegetation() {
        assertEquals(BlockFaceOcclusion.INTERNAL, occlusion(Blocks.WATER, Blocks.KELP));
        assertEquals(BlockFaceOcclusion.INTERNAL, occlusion(Blocks.WATER, Blocks.WATER));
        assertEquals(BlockFaceOcclusion.VISIBLE, occlusion(Blocks.KELP, Blocks.WATER));
        assertFalse(BlockFaceOcclusion.isFluidSurface(Blocks.KELP.defaultBlockState()));
        assertTrue(BlockFaceOcclusion.isFluidSurface(Blocks.WATER.defaultBlockState()));
    }

    @Test
    void waterDoesNotCreateCoplanarInterfacesOnTheSeabed() {
        for (Direction side : Direction.values()) {
            assertEquals(
                    BlockFaceOcclusion.OCCLUDED,
                    BlockFaceOcclusion.between(
                            Blocks.WATER.defaultBlockState(),
                            Blocks.SAND.defaultBlockState(),
                            side.ordinal()));
            assertEquals(
                    BlockFaceOcclusion.VISIBLE,
                    BlockFaceOcclusion.between(
                            Blocks.SAND.defaultBlockState(),
                            Blocks.WATER.defaultBlockState(),
                            side.ordinal()));
        }
    }

    @Test
    void solidCoverageAndGlassInteriorFacesStillCull() {
        assertEquals(BlockFaceOcclusion.OCCLUDED, occlusion(Blocks.SAND, Blocks.STONE));
        assertEquals(BlockFaceOcclusion.INTERNAL, occlusion(Blocks.GLASS, Blocks.GLASS));
        assertEquals(BlockFaceOcclusion.VISIBLE, occlusion(Blocks.SAND, Blocks.GLASS));
    }

    @Test
    void recessedAndCrossedModelsKeepFacesBesideFullCubes() {
        assertEquals(BlockFaceOcclusion.VISIBLE, occlusion(Blocks.STONE_SLAB, Blocks.STONE));
        assertEquals(BlockFaceOcclusion.VISIBLE, occlusion(Blocks.SHORT_GRASS, Blocks.STONE));
        assertEquals(BlockFaceOcclusion.VISIBLE, occlusion(Blocks.OAK_FENCE, Blocks.STONE));
    }
}
