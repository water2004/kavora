/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.asset;

import org.edtp.kavora.sdk.TextureFormat;
import org.edtp.kavora.sdk.TextureFromMemoryInfo;
import org.edtp.kavora.sdk.TexturePurpose;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ImmutableTextureCacheTest {
    @Test
    void aUniformScalarUsesOneTexelWithoutChangingTheSourceBuffer() {
        ByteBuffer pixels = ByteBuffer.allocateDirect(16).position(2).limit(14);
        for (int i = pixels.position(); i < pixels.limit(); i++) pixels.put(i, (byte) 213);
        var source = TextureFromMemoryInfo.builder().hash(91).size(4, 3)
                .format(TextureFormat.R8_UNORM).purpose(TexturePurpose.DATA)
                .immutable(true).pixelData(pixels).build();
        var result = ImmutableTextureCache.compactScalar(source);
        assertEquals(1, result.width);
        assertEquals(1, result.height);
        assertEquals(91, result.hash);
        assertTrue(result.immutable);
        assertTrue(result.pixelData.isDirect());
        assertEquals(1, result.pixelData.remaining());
        assertEquals((byte) 213, result.pixelData.get(0));
        assertEquals(2, pixels.position());
        assertEquals(14, pixels.limit());
        pixels.put(13, (byte) 212);
        assertSame(source, ImmutableTextureCache.compactScalar(source));
    }

    @Test
    void mutableAuthoredAndNonScalarMapsRetainTheirFullExtent() {
        var source = TextureFromMemoryInfo.builder().size(4, 3)
                .format(TextureFormat.R8_UNORM).purpose(TexturePurpose.DATA)
                .immutable(true).pixelData(ByteBuffer.allocate(12)).build();
        source.immutable = false;
        assertSame(source, ImmutableTextureCache.compactScalar(source));
        source.immutable = true;
        source.mipLevels = 1;
        assertSame(source, ImmutableTextureCache.compactScalar(source));
        source.mipLevels = 0;
        source.purpose = TexturePurpose.HEIGHTMAP;
        assertSame(source, ImmutableTextureCache.compactScalar(source));
        source.purpose = TexturePurpose.DATA;
        source.format = TextureFormat.RGBA8_UNORM;
        assertSame(source, ImmutableTextureCache.compactScalar(source));
    }

    @Test
    void identicalPixelsShareUntilTheLastOwnerAndDoNotBecomeAnIdleGpuCache() {
        var cache = new ImmutableTextureCache();
        var ids = new AtomicLong(10);
        var destroyed = new ArrayList<Long>();
        ByteBuffer source = ByteBuffer.wrap(new byte[]{0, 1, 2, 3, 4, 5}).position(1).limit(5);
        var key = ImmutableTextureCache.Key.of(1, 1, 0, 0, 0, source);
        var same = ImmutableTextureCache.Key.of(1, 1, 0, 0, 0, ByteBuffer.wrap(new byte[]{1, 2, 3, 4}));
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
    void formatMipSemanticsDimensionsAndPixelsRemainPartOfTheIdentity() {
        var base = ImmutableTextureCache.Key.of(1, 1, 0, 0, 0, ByteBuffer.wrap(new byte[]{1, 2, 3, 4}));
        assertNotEquals(base, ImmutableTextureCache.Key.of(1, 1, 1, 0, 0, ByteBuffer.wrap(new byte[]{1, 2, 3, 4})));
        assertEquals(base, ImmutableTextureCache.Key.of(1, 1, 0, 1, 0, ByteBuffer.wrap(new byte[]{1, 2, 3, 4})));
        assertNotEquals(base, ImmutableTextureCache.Key.of(2, 1, 0, 0, 0, ByteBuffer.wrap(new byte[]{1, 2, 3, 4})));
        assertNotEquals(base, ImmutableTextureCache.Key.of(1, 1, 0, 0, 0, ByteBuffer.wrap(new byte[]{1, 2, 3, 5})));
    }

    @Test
    void srgbGeneratedMipsAndNormalAndHeightFiltersKeepSeparateAllocations() {
        ByteBuffer pixels = ByteBuffer.wrap(new byte[]{1, 2, 3, 4});
        var color = ImmutableTextureCache.Key.of(1, 1, 1, 0, 0, pixels);
        assertNotEquals(color, ImmutableTextureCache.Key.of(1, 1, 1, 1, 0, pixels));
        var data = ImmutableTextureCache.Key.of(1, 1, 0, 1, 0, pixels);
        assertNotEquals(data, ImmutableTextureCache.Key.of(1, 1, 0, 2, 0, pixels));
        assertNotEquals(data, ImmutableTextureCache.Key.of(1, 1, 0, 5, 0, pixels));
        assertEquals(ImmutableTextureCache.Key.of(1, 1, 1, 0, 1, pixels),
                ImmutableTextureCache.Key.of(1, 1, 1, 1, 1, pixels));
    }

    @Test
    void failedNativeDestroyKeepsTheLastAllocationOwnedForRetry() {
        var cache = new ImmutableTextureCache();
        var key = new ImmutableTextureCache.Key(4, 4, 0, 0, 0, "pixels");
        cache.acquire(key, () -> 42);
        assertThrows(IllegalStateException.class, () -> cache.release(42, handle -> { throw new IllegalStateException(); }));
        assertEquals(42, cache.acquire(key, () -> { throw new AssertionError("Allocation must remain live"); }));
        assertEquals(84, cache.savedImageBytes());
        assertTrue(cache.release(42, handle -> fail("Another owner is alive")));
        assertTrue(cache.release(42, handle -> assertEquals(42, handle)));
    }

    @Test
    void imageAccountingUsesNativeNormalFormatAndEveryMip() {
        assertEquals(42, new ImmutableTextureCache.Key(4, 4, 0, 2, 0, "normal").imageBytes());
        assertEquals(21, new ImmutableTextureCache.Key(4, 4, 4, 1, 0, "r8").imageBytes());
        assertEquals(84, new ImmutableTextureCache.Key(4, 4, 0, 0, 0, "rgba").imageBytes());
    }
}
