/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

/** Committed GPU slices containing 64-byte HardcodedVertex records and uint32 triangle indices. */
public record MeshSharedSurface(long vertexBuffer, long vertexOffset, int vertexCount,
        long indexBuffer, long indexOffset, int indexCount, long material, int flags) {
    public MeshSharedSurface {
        if (vertexBuffer == 0 || indexBuffer == 0 || vertexOffset < 0 || indexOffset < 0
                || (vertexOffset & 3) != 0 || (indexOffset & 3) != 0 || vertexCount <= 0
                || indexCount <= 0 || indexCount % 3 != 0
                || (flags & ~MeshInfoSurfaceTriangles.TEXCOORD_BOUNDS) != 0) {
            throw new IllegalArgumentException("Invalid shared mesh surface");
        }
    }
}
