/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.asset;

import com.nvidia.rtxremix.RemixAPI;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AssetCreationRollbackTest {
    @Test
    void duplicateCreationDoesNotDisposeOrDestroyTheExistingAllocation() {
        class Asset extends RemixAssetBase {
            int disposals;
            @Override protected long doCreate(RemixAPI api, long hash) { return 42; }
            @Override protected void doDestroy(RemixAPI api, long handle) { fail("The existing allocation is alive"); }
            @Override protected void doDisposeData() { disposals++; }
            @Override protected String typeName() { return "TestAsset"; }
        }
        var asset = new Asset();
        asset.create(null, 9, true);
        assertThrows(IllegalStateException.class, () -> asset.create(null, 10, true));
        assertEquals(42, asset.getHandle());
        assertEquals(1, asset.disposals);
    }

    @Test
    void disposalFailureReleasesAnAcquiredSharedAllocation() {
        var cache = new ImmutableTextureCache();
        var key = new ImmutableTextureCache.Key(1, 1, 0, 0, 0, "pixels");
        cache.acquire(key, () -> 42);
        class Asset extends RemixAssetBase {
            int disposals;
            @Override protected long doCreate(RemixAPI api, long hash) { return cache.acquire(key, () -> 43); }
            @Override protected void doDestroy(RemixAPI api, long handle) {
                assertTrue(cache.release(handle, value -> fail("Original owner is still alive")));
            }
            @Override protected void doDisposeData() {
                if (++disposals == 1) throw new IllegalStateException("Disposal failed");
            }
            @Override protected String typeName() { return "TestAsset"; }
        }
        var asset = new Asset();
        assertThrows(IllegalStateException.class, () -> asset.create(null, 9, true));
        assertEquals(0, asset.getHandle());
        assertEquals(0, cache.savedImageBytes());
        assertTrue(cache.release(42, handle -> assertEquals(42, handle)));
    }

    @Test
    void aTextureKeepsItsCreatingStoreAcrossGlobalReplacement() {
        RemixTextureStore.init(null);
        var first = RemixTextureStore.get();
        var texture = new RemixTexture(1, 1, 0, 0, java.nio.ByteBuffer.allocate(4), true);
        texture.attachStore(first);
        RemixTextureStore.init(null);
        try {
            texture.attachStore(first);
            assertThrows(IllegalStateException.class, () -> texture.attachStore(RemixTextureStore.get()));
        } finally {
            RemixTextureStore.shutdown();
        }
    }
}
