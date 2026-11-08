/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.terrain.lod;

import org.edtp.kavora.sdk.RemixAPI;
import org.edtp.kavora.texture.BlockTextureManager;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

/** Optional extension boundary; the base renderer owns no far-terrain storage or mesher. */
public final class LodTerrainScene implements AutoCloseable {
    private FarTerrainExtension extension;
    private boolean resolved;

    public void tick(RemixAPI api, BlockTextureManager textures, ClientLevel world, Vec3 camera) {
        if (!resolved) {
            var factories = FabricLoader.getInstance().getEntrypoints(
                    FarTerrainExtension.ENTRYPOINT, FarTerrainExtension.Factory.class);
            if (factories.size() > 1) throw new IllegalStateException("Only one Kavora far-terrain extension may be active");
            if (!factories.isEmpty()) extension = java.util.Objects.requireNonNull(factories.getFirst().create());
            resolved = true;
        }
        if (extension != null) extension.tick(api, textures, world, camera);
    }
    public void recordNear(long column, Set<NearSection> sections) { if (extension != null) extension.recordNear(column, sections); }
    public boolean isActive() { return extension != null && extension.isActive(); }
    public boolean isViewRefined() { return extension != null && extension.isViewRefined(); }
    public int renderRadiusBlocks() { return extension == null ? 0 : extension.renderRadiusBlocks(); }
    public FarTerrainExtension.Stats diagnostics() { return extension == null ? null : extension.diagnostics(); }
    public boolean hasGeometryForSection(int x, int y, int z) { return extension != null && extension.hasGeometryForSection(x, y, z); }
    public boolean hasVisibleGeometryForSection(int x, int y, int z) { return extension != null && extension.hasVisibleGeometryForSection(x, y, z); }
    public String handoffDiagnostics() { return extension == null ? "no far-terrain extension" : extension.handoffDiagnostics(); }
    public String sectionDiagnostics(int x, int y, int z) { return extension == null ? "no far-terrain extension" : extension.sectionDiagnostics(x, y, z); }
    public long visibilityMutations() { return extension == null ? 0 : extension.visibilityMutations(); }
    public void flushCoverage() { if (extension != null) extension.flushCoverage(); }
    public void suspend() { if (extension != null) extension.suspend(); }
    public void clear() { if (extension != null) extension.clear(); }
    @Override public void close() { if (extension != null) { extension.close(); extension = null; } }
}
