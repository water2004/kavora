/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

import java.nio.ByteBuffer;

/**
 * Flat quads in cyclic winding order. Each vertex is float xyz plus a uint32 material index.
 * Each 40-byte material contains UV origin, S and T float2s, packed half2 bounds, color and axis.
 * Both buffers must be direct, native order, and alive until the foreign call returns.
 */
public record MeshQuadSurface(long material, ByteBuffer vertices, ByteBuffer materials,
                              int quadCount, int materialCount) {
    public static final int VERTEX_STRIDE = 16;
    public static final int MATERIAL_STRIDE = 40;
    /** Four float3 positions and one material index; the runtime discards the upload copy. */
    public static final int RESIDENT_QUAD_BYTES = 52;
    public static final int MAX_QUADS = 16384;

    public MeshQuadSurface {
        if (quadCount < 1 || quadCount > MAX_QUADS || materialCount < 1 || materialCount > quadCount
                || vertices == null || !vertices.isDirect()
                || materials == null || !materials.isDirect()
                || vertices.remaining() != (long) quadCount * 4 * VERTEX_STRIDE
                || materials.remaining() != (long) materialCount * MATERIAL_STRIDE) {
            throw new IllegalArgumentException("Invalid quad surface buffers");
        }
    }
}
