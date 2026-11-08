/* SPDX-License-Identifier: LGPL-3.0-only */
package org.edtp.kavora.horizon.voxel;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

/** Render-thread cache of compact faces. Expanded vertices and native handles are never retained. */
public final class VoxelMeshCache {
    private final Long2ObjectLinkedOpenHashMap<VoxelMesher.Mesh> meshes = new Long2ObjectLinkedOpenHashMap<>();
    private final long limit;
    private long bytes;

    public VoxelMeshCache(long limit) {
        if (limit < 0) throw new IllegalArgumentException("Negative mesh cache size");
        this.limit = limit;
    }

    public long bytes() {
        return bytes;
    }

    public VoxelMesher.Mesh take(long key) {
        var mesh = meshes.remove(key);
        if (mesh != null) bytes -= mesh.bytes();
        return mesh;
    }

    public void put(VoxelMesher.Mesh mesh) {
        take(mesh.key());
        if (mesh.bytes() == 0 || mesh.bytes() > limit) return;
        while (bytes > limit - mesh.bytes()) bytes -= meshes.removeFirst().bytes();
        meshes.put(mesh.key(), mesh);
        bytes += mesh.bytes();
    }

    public void clear() {
        meshes.clear();
        bytes = 0;
    }
}
