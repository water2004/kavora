/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BakedQuadMixinTest {

    @Test
    void zeroIsAValidCachedHash() {
        BakedQuadMixin cache = new BakedQuadMixin();
        int[] vertexData = {1, 2, 3, 4};

        assertFalse(cache.kavora$matchesStaticGeometry(vertexData, 11, 12));
        cache.kavora$setStaticGeometryHash(0L, vertexData, 11, 12);

        assertTrue(cache.kavora$matchesStaticGeometry(vertexData, 11, 12));
        assertEquals(0L, cache.kavora$getStaticGeometryHash());
    }

    @Test
    void mutableVertexDataInvalidatesAndReusesTheSnapshot() {
        BakedQuadMixin cache = new BakedQuadMixin();
        int[] vertexData = {1, 2, 3, 4};
        cache.kavora$setStaticGeometryHash(7L, vertexData, 11, 12);

        vertexData[2] = 99;
        assertFalse(cache.kavora$matchesStaticGeometry(vertexData, 11, 12));

        cache.kavora$setStaticGeometryHash(8L, vertexData, 11, 12);
        assertTrue(cache.kavora$matchesStaticGeometry(vertexData, 11, 12));
        assertEquals(8L, cache.kavora$getStaticGeometryHash());
    }

    @Test
    void changedSpriteBoundsInvalidateUnchangedVertexData() {
        BakedQuadMixin cache = new BakedQuadMixin();
        int[] vertexData = {1, 2, 3, 4};
        cache.kavora$setStaticGeometryHash(7L, vertexData, 11, 12);

        assertFalse(cache.kavora$matchesStaticGeometry(vertexData, 21, 12));
        assertFalse(cache.kavora$matchesStaticGeometry(vertexData, 11, 22));
    }
}
