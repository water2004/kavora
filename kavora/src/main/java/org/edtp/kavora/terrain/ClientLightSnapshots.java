/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.terrain;

import org.edtp.kavora.terrain.LightStorageSnapshotAccess;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.edtp.kavora.mixin.ChunkLightProviderAccessor;

/** Render-thread control over the client light engine's published snapshots. */
public final class ClientLightSnapshots {
    private ClientLightSnapshots() {
    }

    /**
     * Replaces an aliased snapshot of the block and sky light storages with a real copy.
     * Must run on the render thread before RTX capture switches off, because the section
     * rebuilds that follow read light on worker threads. A storage whose snapshot is already
     * a real copy is left alone.
     */
    public static void publishRealSnapshots(ClientLevel world) {
        LevelLightEngine provider = world.getLightEngine();
        for (LightLayer type : LightLayer.values()) {
            if (provider.getLayerListener(type) instanceof ChunkLightProviderAccessor access
                    && access.kavora$getLightStorage() instanceof LightStorageSnapshotAccess storage) {
                storage.kavora$publishRealSnapshot();
            }
        }
    }
}
