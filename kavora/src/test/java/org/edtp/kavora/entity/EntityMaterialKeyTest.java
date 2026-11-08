/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.resources.Identifier;

/**
 * The material cache stores records and is probed with a mutable key. HashMap calls
 * probe.equals(stored) and requires equal hash codes, so a drift between the two
 * misses every lookup and silently drops every entity layer.
 */
class EntityMaterialKeyTest {

    private static final Identifier TEX = Identifier.fromNamespaceAndPath("minecraft", "textures/entity/cow/cow.png");

    @Test
    void lookupProbeAgreesWithTheStoredRecordOnHashAndEquality() {
        KavoraEntitySubmitter.EntityMaterialKey stored =
                new KavoraEntitySubmitter.EntityMaterialKey(TEX, RenderLayerUtils.SurfaceMode.CUTOUT);
        KavoraEntitySubmitter.EntityMaterialLookupKey probe =
                new KavoraEntitySubmitter.EntityMaterialLookupKey().set(TEX, RenderLayerUtils.SurfaceMode.CUTOUT);

        assertEquals(stored.hashCode(), probe.hashCode());
        assertTrue(probe.equals(stored));
    }

    @Test
    void surfaceModeSeparatesMaterialsOnOneTexture() {
        KavoraEntitySubmitter.EntityMaterialLookupKey probe =
                new KavoraEntitySubmitter.EntityMaterialLookupKey().set(TEX, RenderLayerUtils.SurfaceMode.CUTOUT);

        assertFalse(probe.equals(
                new KavoraEntitySubmitter.EntityMaterialKey(TEX, RenderLayerUtils.SurfaceMode.TRANSLUCENT)));
    }
}
