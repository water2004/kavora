/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class KavoraEntitySubmitterIndexTest {

    @Test
    void quadsFanEachGroupOfFourFromItsFirstVertex() {
        int[] indices = KavoraEntitySubmitter.buildIndices(8, RenderLayerUtils.Topology.QUADS);

        assertArrayEquals(new int[]{
                0, 2, 1, 0, 3, 2,
                4, 6, 5, 4, 7, 6,
        }, indices);
    }

    @Test
    void quadsIgnoreATrailingPartialQuad() {
        int[] indices = KavoraEntitySubmitter.buildIndices(7, RenderLayerUtils.Topology.QUADS);

        assertArrayEquals(new int[]{0, 2, 1, 0, 3, 2}, indices);
    }

    @Test
    void stripAdvancesOneVertexPerTriangleAndAlternatesWinding() {
        int[] indices = KavoraEntitySubmitter.buildIndices(
                5, RenderLayerUtils.Topology.TRIANGLE_STRIP);

        assertArrayEquals(new int[]{
                0, 1, 2,
                2, 1, 3,
                2, 3, 4,
        }, indices);
    }

    @Test
    void shortestStripIsASingleTriangle() {
        int[] indices = KavoraEntitySubmitter.buildIndices(
                3, RenderLayerUtils.Topology.TRIANGLE_STRIP);

        assertArrayEquals(new int[]{0, 1, 2}, indices);
    }
}
