/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.entity;

/**
 * Cache attached to vanilla baked quads by a mixin.
 *
 * <p>The digest contains only bake-time geometry. Per-render tint and brightness
 * remain outside the cache and are folded into the mesh key on every draw.
 */
public interface BakedQuadStaticGeometryCache {

    boolean kavora$matchesStaticGeometry(int[] vertexData,
                                            int texcoordBoundsMin,
                                            int texcoordBoundsMax);

    long kavora$getStaticGeometryHash();

    void kavora$setStaticGeometryHash(long hash,
                                         int[] vertexData,
                                         int texcoordBoundsMin,
                                         int texcoordBoundsMax);
}
