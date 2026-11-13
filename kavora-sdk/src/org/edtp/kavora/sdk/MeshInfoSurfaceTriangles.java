/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.sdk;

import java.nio.ByteBuffer;

/**
 * Mesh surface triangles: contains vertices, indices, material handle,
 * and optional skinning data as direct ByteBuffers.
 */
public class MeshInfoSurfaceTriangles {
    public static final int TEXCOORD_BOUNDS = 1 << 0;
    /** CPU vertices omit trailing padding and use {@link HardcodedVertex#STORAGE_STRIDE}.
     * The direct buffer must remain alive through CreateMesh; the runtime owns its copy. */
    public static final int COMPACT_VERTICES = 1 << 1;
    /** Full-stride records include previous rendered-frame positions at offset 44.
     * New vertices must set previous=current. Incompatible with COMPACT_VERTICES and skinning. */
    public static final int PREVIOUS_POSITIONS = 1 << 2;
    /** On translucent faces, a zero vertex alpha marks water on the exterior side. */
    public static final int WATER_CONTACT = 1 << 3;

    /** Direct, native-order records starting at offset zero; capacity must cover vertexCount times the stride. */
    public ByteBuffer vertexBuffer;
    public int vertexCount;
    public ByteBuffer indexBuffer;
    public int indexCount;
    public long material;
    public int flags;
    public boolean skinning_hasvalue;
    public int skinning_bonesPerVertex;
    public ByteBuffer skinning_blendWeights;
    public int skinning_blendWeightsCount;
    public ByteBuffer skinning_blendIndices;
    public int skinning_blendIndicesCount;

    public MeshInfoSurfaceTriangles() {}
}
