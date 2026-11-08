/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.asset;

import com.nvidia.rtxremix.MeshQuadSurface;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import java.lang.ref.Reference;
import java.nio.ByteBuffer;

/** Static flat quads keep one shared attribute table and use the runtime's common quad indices. */
public final class RemixQuadMesh extends RemixMesh {
    private final RemixMaterial material;
    private final int quads, materialCount;
    private final long bytes;
    private ByteBuffer vertices, attributes;

    public RemixQuadMesh(RemixMaterial material, int quads, int materialCount,
                         ByteBuffer vertices, ByteBuffer attributes) {
        super(new RemixMeshSurface[0]);
        this.material = material;
        this.quads = quads;
        this.materialCount = materialCount;
        this.vertices = vertices;
        this.attributes = attributes;
        bytes = (long) quads * MeshQuadSurface.RESIDENT_QUAD_BYTES
                + (long) materialCount * MeshQuadSurface.MATERIAL_STRIDE;
    }

    @Override
    protected long doCreate(RemixAPI api, long hash) {
        var surface = new MeshQuadSurface(material.getHandle(), vertices, attributes, quads, materialCount);
        try { return api.createQuadMesh(hash, surface); }
        finally { Reference.reachabilityFence(this); }
    }

    @Override
    protected void doDisposeData() {
        if (vertices != null) DirectBufferPool.release(vertices);
        if (attributes != null) DirectBufferPool.release(attributes);
        vertices = attributes = null;
        super.doDisposeData();
    }

    @Override
    public long estimatedNativeBytes() {
        return bytes;
    }
}
