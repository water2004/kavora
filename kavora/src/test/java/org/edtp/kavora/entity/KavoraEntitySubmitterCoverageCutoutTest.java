/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.entity;

import org.edtp.kavora.texture.DynamicTextureCache;
import org.edtp.kavora.texture.GlyphPageMirror;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.material.MapColor;
import org.edtp.kavora.sdk.InstanceCategoryBit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the material and instance classification for captured textures.
 */
class KavoraEntitySubmitterCoverageCutoutTest {

    private static final Identifier MAP_TEXTURE = Identifier.fromNamespaceAndPath("minecraft", "dynamic/map/42_1");

    @Test
    void dynamicMapsCutAtHalfCoverage() {
        assertEquals(128, KavoraEntitySubmitter.cutoutAlphaReferenceValue(MAP_TEXTURE));
    }

    @Test
    void mapDecorationsCutAtHalfCoverage() {
        assertEquals(128, KavoraEntitySubmitter.cutoutAlphaReferenceValue(
                Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/map_decorations.png")));
    }

    @Test
    void mapDecorationsAreAlreadyOffsetDecals() {
        assertEquals(InstanceCategoryBit.DECAL_NO_OFFSET,
                KavoraEntitySubmitter.additionalCategoryFlagsForTexture(
                        Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/map_decorations.png")));
        assertEquals(0, KavoraEntitySubmitter.additionalCategoryFlagsForTexture(MAP_TEXTURE));
    }

    @Test
    void particleAtlasPartsAreParticles() {
        assertEquals(InstanceCategoryBit.PARTICLE,
                KavoraEntitySubmitter.additionalCategoryFlagsForTexture(
                        Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/particles.png")));
    }

    @Test
    void allayBodyUsesLocalAppearanceLighting() {
        assertEquals(InstanceCategoryBit.SELF_LIT,
                KavoraEntitySubmitter.additionalCategoryFlagsForTexture(
                        Identifier.withDefaultNamespace("textures/entity/allay/allay.png")));
        assertEquals(0, KavoraEntitySubmitter.additionalCategoryFlagsForTexture(
                Identifier.withDefaultNamespace("textures/entity/vex/vex.png")));
    }

    @Test
    void glyphPagesCutAtHalfCoverage() {
        Identifier page = Identifier.fromNamespaceAndPath("minecraft", "default/7");
        DynamicTextureCache.onRegisterGlyphPage(page, new GlyphPageMirror(false));

        assertEquals(128, KavoraEntitySubmitter.cutoutAlphaReferenceValue(page));
    }

    @Test
    void vanillaMapPaletteHasBinaryAlpha() {
        for (int color = 0; color <= 255; color++) {
            int alpha = MapColor.getColorFromPackedId(color) >>> 24;
            assertTrue(alpha == 0 || alpha == 255, "map color " + color + " has alpha " + alpha);
        }
    }

    @Test
    void otherTexturesShowAnyTexelWithAlpha() {
        assertEquals(1, KavoraEntitySubmitter.cutoutAlphaReferenceValue(
                Identifier.fromNamespaceAndPath("minecraft", "textures/entity/slime/slime.png")));
        assertEquals(1, KavoraEntitySubmitter.cutoutAlphaReferenceValue(
                Identifier.fromNamespaceAndPath("example", "dynamic/map/42_1")));
    }
}
