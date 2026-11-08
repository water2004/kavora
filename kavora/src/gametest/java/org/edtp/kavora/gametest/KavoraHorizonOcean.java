/* SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.gametest;

import org.edtp.kavora.sdk.HardcodedVertex;
import org.edtp.kavora.sdk.MeshInfo;
import org.edtp.kavora.sdk.MeshInfoSurfaceTriangles;
import org.edtp.kavora.sdk.RemixAPI;
import org.edtp.kavora.sdk.Transform;
import org.edtp.kavora.KavoraMod;
import org.edtp.kavora.texture.BlockTextureManager;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Optical reference geometry, independent of observed terrain or LOD. Render-thread only. */
final class KavoraHorizonOcean implements AutoCloseable {
    private final RemixAPI api = KavoraMod.renderer.getRemixApi();
    private final long mesh;
    private final long instance;

    KavoraHorizonOcean(float height) {
        var surface = new MeshInfoSurfaceTriangles();
        surface.material = KavoraMod.renderer.getTextureManager().getWaterSurfaceMaterial().getHandle();
        surface.vertexCount = 4;
        surface.vertexBuffer = ByteBuffer.allocateDirect(4 * HardcodedVertex.STRIDE).order(ByteOrder.nativeOrder());
        float tile = BlockTextureManager.getWaterTileSize();
        for (float[] corner : new float[][] {{-8192, -8192}, {-8192, 8192}, {8192, 8192}, {8192, -8192}}) {
            HardcodedVertex.create(corner[0], 0, corner[1], 0, 1, 0,
                    corner[0] / tile, corner[1] / tile, -1).writeTo(surface.vertexBuffer);
        }
        surface.vertexBuffer.flip();
        surface.indexCount = 6;
        surface.indexBuffer = ByteBuffer.allocateDirect(6 * Integer.BYTES).order(ByteOrder.nativeOrder());
        for (int index : new int[] {0, 1, 2, 0, 2, 3}) surface.indexBuffer.putInt(index);
        surface.indexBuffer.flip();
        mesh = api.createMesh(MeshInfo.builder().hash(0x7d050)
                .surfaces(new MeshInfoSurfaceTriangles[] {surface}).build());
        Transform transform = Transform.identity();
        transform.setTranslation(0, height, 0);
        try {
            instance = api.sceneInstanceAdd(mesh, transform.matrix, 0, 0, 0);
        } catch (RuntimeException | Error failure) {
            api.destroyMesh(mesh);
            throw failure;
        }
    }

    @Override public void close() {
        api.sceneInstanceRemove(instance);
        api.destroyMesh(mesh);
    }
}
