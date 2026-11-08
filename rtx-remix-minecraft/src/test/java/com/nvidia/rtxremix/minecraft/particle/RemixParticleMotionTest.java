/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.particle;

import com.nvidia.rtxremix.HardcodedVertex;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RemixParticleMotionTest {
    @Test
    void previousPositionsFollowTheRenderedVerticesAndRestartAtBirth() throws Exception {
        ByteBuffer vertices = ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder());
        write(vertices, 0, 3, false);
        assertPrevious(vertices, 0, 3);
        write(vertices, 0, 7, true);
        assertPrevious(vertices, 0, 3);
        write(vertices, 0, 11, true);
        assertPrevious(vertices, 0, 7);
        write(vertices, 0, -5, false);
        assertPrevious(vertices, 0, -5);
    }

    @Test
    void denseSlotMovesPreserveAllFourVertexCorrespondences() throws Exception {
        ByteBuffer vertices = ByteBuffer.allocateDirect(512).order(ByteOrder.nativeOrder());
        for (int corner = 0; corner < 4; corner++) {
            write(vertices, 256 + corner * 64, 10 + corner, false);
            write(vertices, 256 + corner * 64, 20 + corner, true);
        }
        Method move = RemixParticleRenderer.class.getDeclaredMethod("copyParticleQuad", ByteBuffer.class, int.class, int.class);
        move.setAccessible(true);
        move.invoke(null, vertices, 1, 0);
        for (int corner = 0; corner < 4; corner++) {
            assertPrevious(vertices, corner * 64, 10 + corner);
            write(vertices, corner * 64, 30 + corner, true);
            assertPrevious(vertices, corner * 64, 20 + corner);
        }
    }

    @Test
    void structuredVertexWritesTheNativeMotionAttributeWithoutChangingItsStride() {
        ByteBuffer data = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
        var vertex = HardcodedVertex.create(1, 2, 3);
        vertex.previousPositionX = -7;
        vertex.previousPositionY = -8;
        vertex.previousPositionZ = -9;
        vertex.writeTo(data);
        assertEquals(64, data.position());
        assertEquals(-7, data.getFloat(44));
        assertEquals(-8, data.getFloat(48));
        assertEquals(-9, data.getFloat(52));
    }

    private static void write(ByteBuffer vertices, int offset, float x, boolean history) throws Exception {
        Method put = RemixParticleRenderer.class.getDeclaredMethod("putActiveVertex", ByteBuffer.class, int.class,
                double.class, double.class, double.class, float.class, float.class, float.class,
                float.class, float.class, int.class, boolean.class);
        put.setAccessible(true);
        put.invoke(null, vertices, offset, (double)x, (double)(x + 1), (double)(x + 2), 0f, 0f, -1f, 0f, 0f, -1, history);
    }

    private static void assertPrevious(ByteBuffer vertices, int offset, float expected) {
        assertEquals(expected, vertices.getFloat(offset + 44));
        assertEquals(expected + 1, vertices.getFloat(offset + 48));
        assertEquals(expected + 2, vertices.getFloat(offset + 52));
    }
}
