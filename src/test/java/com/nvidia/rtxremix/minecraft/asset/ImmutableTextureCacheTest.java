/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.asset;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ImmutableTextureCacheTest {
    @Test
    void identicalPixelsShareUntilTheLastOwnerAndDoNotBecomeAnIdleGpuCache() {
        var cache = new ImmutableTextureCache();
        var ids = new AtomicLong(10);
        var destroyed = new ArrayList<Long>();
        ByteBuffer source = ByteBuffer.wrap(new byte[]{0, 1, 2, 3, 4, 5}).position(1).limit(5);
        var key = ImmutableTextureCache.Key.of("items/albedo", 1, 1, 0, 0, 0, source);
        var same = ImmutableTextureCache.Key.of("items/albedo", 1, 1, 0, 0, 0, ByteBuffer.wrap(new byte[]{1, 2, 3, 4}));
        assertEquals(1, source.position());
        assertEquals(5, source.limit());
        assertEquals(key, same);
        assertEquals(11, cache.acquire(key, ids::incrementAndGet));
        assertEquals(11, cache.acquire(same, ids::incrementAndGet));
        assertEquals(4, cache.savedImageBytes());
        assertTrue(cache.release(11, destroyed::add));
        assertEquals(0, cache.savedImageBytes());
        assertTrue(destroyed.isEmpty());
        assertTrue(cache.release(11, destroyed::add));
        assertEquals(java.util.List.of(11L), destroyed);
        assertFalse(cache.release(11, destroyed::add));
        assertEquals(12, cache.acquire(key, ids::incrementAndGet));
    }

    @Test
    void formatPurposeDimensionsAndPixelsRemainPartOfTheIdentity() {
        var base = ImmutableTextureCache.Key.of("items/albedo", 1, 1, 0, 0, 0, ByteBuffer.wrap(new byte[]{1, 2, 3, 4}));
        assertNotEquals(base, ImmutableTextureCache.Key.of("items/albedo", 1, 1, 1, 0, 0, ByteBuffer.wrap(new byte[]{1, 2, 3, 4})));
        assertNotEquals(base, ImmutableTextureCache.Key.of("items/albedo", 1, 1, 0, 1, 0, ByteBuffer.wrap(new byte[]{1, 2, 3, 4})));
        assertNotEquals(base, ImmutableTextureCache.Key.of("items/albedo", 2, 1, 0, 0, 0, ByteBuffer.wrap(new byte[]{1, 2, 3, 4})));
        assertNotEquals(base, ImmutableTextureCache.Key.of("items/albedo", 1, 1, 0, 0, 0, ByteBuffer.wrap(new byte[]{1, 2, 3, 5})));
        assertNotEquals(base, ImmutableTextureCache.Key.of("other/albedo", 1, 1, 0, 0, 0, ByteBuffer.wrap(new byte[]{1, 2, 3, 4})));
    }

    @Test
    void failedNativeDestroyKeepsTheLastAllocationOwnedForRetry() {
        var cache = new ImmutableTextureCache();
        var key = new ImmutableTextureCache.Key("items/albedo", 4, 4, 0, 0, 0, "pixels");
        cache.acquire(key, () -> 42);
        assertThrows(IllegalStateException.class, () -> cache.release(42, handle -> { throw new IllegalStateException(); }));
        assertEquals(42, cache.acquire(key, () -> { throw new AssertionError("Allocation must remain live"); }));
        assertEquals(84, cache.savedImageBytes());
        assertTrue(cache.release(42, handle -> fail("Another owner is alive")));
        assertTrue(cache.release(42, handle -> assertEquals(42, handle)));
    }

    @Test
    void imageAccountingUsesNativeNormalFormatAndEveryMip() {
        assertEquals(42, new ImmutableTextureCache.Key("items/albedo", 4, 4, 0, 2, 0, "normal").imageBytes());
        assertEquals(21, new ImmutableTextureCache.Key("items/albedo", 4, 4, 4, 1, 0, "r8").imageBytes());
        assertEquals(84, new ImmutableTextureCache.Key("items/albedo", 4, 4, 0, 0, 0, "rgba").imageBytes());
    }
}
