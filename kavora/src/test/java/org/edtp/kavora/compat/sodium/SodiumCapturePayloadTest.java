/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.compat.sodium;

import org.edtp.kavora.terrain.capture.BlockDataQueue;
import org.edtp.kavora.terrain.capture.SectionBlockData;
import org.edtp.kavora.terrain.capture.SectionMeshData;
import net.minecraft.core.SectionPos;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SodiumCapturePayloadTest {
    private static BlockDataQueue.QueuedCapture capture(SectionMeshData mesh) {
        return new BlockDataQueue.QueuedCapture(new SectionBlockData(SectionPos.of(1, 2, 3), mesh,
                List.of(), 0, 0, 0, 0, 0, 0, new long[64]), new BlockDataQueue.CaptureToken(7, 9));
    }
    @Test
    void rejectedOutputReleasesTheSnapshotExactlyOnce() {
        SectionMeshData mesh = new SectionMeshData(List.of());
        SodiumCapturePayload payload = new SodiumCapturePayload();
        payload.set(capture(mesh));
        payload.discard();
        payload.discard();
        assertThrows(IllegalStateException.class, mesh::surfaces);
        assertNull(payload.take());
    }
    @Test
    void acceptedOutputTransfersOwnershipWithoutReleasing() {
        SectionMeshData mesh = new SectionMeshData(List.of());
        var capture = capture(mesh);
        SodiumCapturePayload payload = new SodiumCapturePayload();
        payload.set(capture);
        assertSame(capture, payload.take());
        payload.discard();
        assertTrue(mesh.surfaces().isEmpty());
        mesh.release();
    }
    @Test
    void aSecondSnapshotCannotOverwriteAnOwnedReference() {
        SectionMeshData mesh = new SectionMeshData(List.of());
        SodiumCapturePayload payload = new SodiumCapturePayload();
        payload.set(capture(mesh));
        assertThrows(IllegalStateException.class, () -> payload.set(capture(mesh)));
        payload.discard();
    }
}
