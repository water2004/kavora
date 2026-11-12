/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.entity;

import org.edtp.kavora.texture.BlockTextureManager;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KavoraEntitySubmitterAtlasSurfaceModeTest {

    private static final Identifier ENTITY_TEXTURE =
            Identifier.fromNamespaceAndPath("minecraft", "textures/entity/creeper/creeper.png");

    @Test
    void aHeldTranslucentBlockKeepsItsTranslucentSurface() {
        assertEquals(RenderLayerUtils.SurfaceMode.TRANSLUCENT,
                KavoraEntitySubmitter.atlasSurfaceMode(BlockTextureManager.BLOCK_ATLAS_ID,
                        RenderLayerUtils.SurfaceMode.TRANSLUCENT, BlockTextureManager.L_TRANS));
    }

    @Test
    void anOrdinaryItemOnTheTranslucentItemLayerFallsBackToCutout() {
        // Minecraft draws opaque items through its translucent item layer, so a
        // captured TRANSLUCENT on the atlas is not evidence of a translucent block.
        assertEquals(RenderLayerUtils.SurfaceMode.CUTOUT,
                KavoraEntitySubmitter.atlasSurfaceMode(BlockTextureManager.BLOCK_ATLAS_ID,
                        RenderLayerUtils.SurfaceMode.TRANSLUCENT, EntityRenderContext.NO_BLOCK_ITEM));
        // Nor is an opaque block item drawn through it.
        assertEquals(RenderLayerUtils.SurfaceMode.CUTOUT,
                KavoraEntitySubmitter.atlasSurfaceMode(BlockTextureManager.BLOCK_ATLAS_ID,
                        RenderLayerUtils.SurfaceMode.TRANSLUCENT, BlockTextureManager.L_SOLID));
    }

    @Test
    void anEnchantedItemKeepsItsGlintWhenNarrowedToCutout() {
        assertEquals(RenderLayerUtils.SurfaceMode.ITEM_GLINT_CUTOUT,
                KavoraEntitySubmitter.atlasSurfaceMode(BlockTextureManager.BLOCK_ATLAS_ID,
                        RenderLayerUtils.SurfaceMode.ITEM_GLINT_TRANSLUCENT, EntityRenderContext.NO_BLOCK_ITEM));
    }

    @Test
    void aHeldClearGlassBlockTakesTheGlassSurfaceWhateverItWasCapturedAs() {
        // Vanilla draws clear glass through its cutout item layer, while terrain
        // gives the placed block a refracting layer of its own.
        for (RenderLayerUtils.SurfaceMode captured : new RenderLayerUtils.SurfaceMode[] {
                RenderLayerUtils.SurfaceMode.CUTOUT, RenderLayerUtils.SurfaceMode.TRANSLUCENT}) {
            assertEquals(RenderLayerUtils.SurfaceMode.GLASS,
                    KavoraEntitySubmitter.atlasSurfaceMode(BlockTextureManager.BLOCK_ATLAS_ID,
                            captured, BlockTextureManager.L_GLASS), captured.name());
        }
    }

    @Test
    void otherAtlasSurfaceModesAreLeftAlone() {
        for (RenderLayerUtils.SurfaceMode mode : RenderLayerUtils.SurfaceMode.values()) {
            if (mode.base() == RenderLayerUtils.SurfaceMode.TRANSLUCENT) {
                continue;
            }
            assertEquals(mode, KavoraEntitySubmitter.atlasSurfaceMode(
                    BlockTextureManager.BLOCK_ATLAS_ID, mode, EntityRenderContext.NO_BLOCK_ITEM),
                    mode.name());
        }
    }

    @Test
    void texturesOtherThanTheAtlasAreNeverNarrowed() {
        // A genuinely translucent entity layer, a ghast or a slime, must keep it,
        // and an entity texture is never glass.
        assertEquals(RenderLayerUtils.SurfaceMode.TRANSLUCENT,
                KavoraEntitySubmitter.atlasSurfaceMode(ENTITY_TEXTURE,
                        RenderLayerUtils.SurfaceMode.TRANSLUCENT, EntityRenderContext.NO_BLOCK_ITEM));
        assertEquals(RenderLayerUtils.SurfaceMode.CUTOUT,
                KavoraEntitySubmitter.atlasSurfaceMode(ENTITY_TEXTURE,
                        RenderLayerUtils.SurfaceMode.CUTOUT, BlockTextureManager.L_GLASS));
    }

    @Test
    void aHeldBlockBorrowsTheAtlasMaterialItsPlacedCounterpartUses() {
        assertEquals(BlockTextureManager.L_TRANS,
                KavoraEntitySubmitter.atlasLayerType(RenderLayerUtils.SurfaceMode.TRANSLUCENT));
        assertEquals(BlockTextureManager.layerType("glass"),
                KavoraEntitySubmitter.atlasLayerType(RenderLayerUtils.SurfaceMode.GLASS));
        assertEquals(BlockTextureManager.L_CUTOUT,
                KavoraEntitySubmitter.atlasLayerType(RenderLayerUtils.SurfaceMode.CUTOUT));
        assertEquals(BlockTextureManager.L_CUTOUT,
                KavoraEntitySubmitter.atlasLayerType(RenderLayerUtils.SurfaceMode.OPAQUE));
    }
}
