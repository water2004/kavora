/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import org.edtp.kavora.entity.BakedQuadStaticGeometryCache;
import org.spongepowered.asm.mixin.Mixin;

import java.util.Arrays;
import net.minecraft.client.resources.model.geometry.BakedQuad;

/** Stores the bake-time geometry digest used by Remix entity mesh pooling. */
@Mixin(BakedQuad.class)
public class BakedQuadMixin implements BakedQuadStaticGeometryCache {

    private int[] kavora$staticGeometrySnapshot;
    private long kavora$staticGeometryHash;
    private int kavora$staticGeometryBoundsMin;
    private int kavora$staticGeometryBoundsMax;

    @Override
    public boolean kavora$matchesStaticGeometry(int[] vertexData,
                                                   int texcoordBoundsMin,
                                                   int texcoordBoundsMax) {
        return kavora$staticGeometryBoundsMin == texcoordBoundsMin
                && kavora$staticGeometryBoundsMax == texcoordBoundsMax
                && Arrays.equals(kavora$staticGeometrySnapshot, vertexData);
    }

    @Override
    public long kavora$getStaticGeometryHash() {
        return kavora$staticGeometryHash;
    }

    @Override
    public void kavora$setStaticGeometryHash(long hash,
                                                int[] vertexData,
                                                int texcoordBoundsMin,
                                                int texcoordBoundsMax) {
        if (kavora$staticGeometrySnapshot == null
                || kavora$staticGeometrySnapshot.length != vertexData.length) {
            kavora$staticGeometrySnapshot = Arrays.copyOf(vertexData, vertexData.length);
        } else {
            System.arraycopy(vertexData, 0, kavora$staticGeometrySnapshot, 0,
                    vertexData.length);
        }
        kavora$staticGeometryBoundsMin = texcoordBoundsMin;
        kavora$staticGeometryBoundsMax = texcoordBoundsMax;
        kavora$staticGeometryHash = hash;
    }
}
