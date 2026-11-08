/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.lod;

import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

/** Optional far-terrain scene. Every method runs on the render thread. */
public interface FarTerrainExtension extends AutoCloseable {
    String ENTRYPOINT = "rtxremix:far_terrain";
    /** Creates an independent scene owner for one terrain manager. */
    interface Factory { FarTerrainExtension create(); }
    record Stats(long generation, int visibleCells, int stagedCells, int outstandingJobs,
                 long geometryBytes, long visibleGeometryBytes, long reservedWorkerBytes,
                 int retiringCells, int failedCells, boolean memoryBackpressured) { }
    /** Workers may retain immutable CPU snapshots, never the client world or native handles. */
    void tick(RemixAPI api, BlockTextureManager textures, ClientLevel world, Vec3 camera);
    /** Records committed near geometry. The extension must not delay or initiate near publication. */
    void recordNear(long column, Set<NearSection> sections);
    boolean isActive();
    int renderRadiusBlocks();
    Stats diagnostics();
    /** True when intersecting geometry has reached the selected distance-dependent detail, not a temporary ancestor. */
    boolean hasGeometryForSection(int x, int y, int z);
    /** Includes temporary parent or child coverage while the selected detail is being built. */
    default boolean hasVisibleGeometryForSection(int x, int y, int z) { return hasGeometryForSection(x, y, z); }
    /** Every observed node in the current view has reached its selected screen-space detail. */
    default boolean isViewRefined() { return false; }
    String handoffDiagnostics();
    String sectionDiagnostics(int x, int y, int z);
    long visibilityMutations();
    void flushCoverage();
    void suspend();
    void clear();
    @Override void close();
}
