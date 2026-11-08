/* SPDX-License-Identifier: LGPL-3.0-only */
package org.edtp.kavora.horizon.voxel;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class VoxelMeshCacheTest {
    private static VoxelMesher.Mesh mesh(long key, int count) {
        return new VoxelMesher.Mesh(key, 1, new long[count]);
    }

    @Test
    void cachedFacesTransferToTheRequesterAndEvictWithinTheByteLimit() {
        var cache = new VoxelMeshCache(24);
        var a = mesh(1, 2);
        var b = mesh(2, 1);
        cache.put(a);
        cache.put(b);
        assertEquals(24, cache.bytes());
        assertSame(a, cache.take(1));
        assertNull(cache.take(1));
        assertEquals(8, cache.bytes());
        cache.put(a);
        cache.put(mesh(3, 1));
        assertNull(cache.take(2));
        assertSame(a, cache.take(1));
        assertEquals(8, cache.bytes());
        cache.clear();
        assertEquals(0, cache.bytes());
    }

    @Test
    void updatedAndOversizedMeshesCannotLeaveAStaleCachedEntry() {
        var cache = new VoxelMeshCache(16);
        cache.put(mesh(1, 1));
        cache.put(mesh(1, 3));
        assertNull(cache.take(1));
        assertEquals(0, cache.bytes());
        cache.put(mesh(1, 2));
        cache.take(1);
        assertNull(cache.take(1));
    }
}
