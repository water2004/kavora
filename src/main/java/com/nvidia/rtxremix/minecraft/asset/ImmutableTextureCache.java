/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.asset;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/** Render-thread ownership of identical immutable atlas channels. Keeps no pixel copies or idle images. */
final class ImmutableTextureCache {
    record Key(String identity, int width, int height, int format, int purpose, int mipLevels, String pixels) {
        long imageBytes() {
            int channels = purpose >= 2 && purpose <= 4 ? 2 : switch (format) {
                case 4 -> 1;
                case 5 -> 2;
                case 6 -> 8;
                case 7 -> 16;
                default -> 4;
            };
            long bytes = 0;
            for (int w = width, h = height, mip = 1;; w = Math.max(1, w / 2), h = Math.max(1, h / 2), mip++) {
                bytes += (long) w * h * channels;
                if ((w == 1 && h == 1) || (mipLevels > 0 && mip == mipLevels)) return bytes;
            }
        }

        static Key of(String identity, int width, int height, int format, int purpose, int mipLevels, ByteBuffer pixels) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                digest.update(pixels.duplicate());
                return new Key(identity, width, height, format, purpose, mipLevels, HexFormat.of().formatHex(digest.digest()));
            } catch (NoSuchAlgorithmException e) {
                throw new AssertionError("SHA-256 is required by the Java platform", e);
            }
        }
    }

    private static final class Entry {
        final Key key;
        final long handle;
        int references = 1;
        Entry(Key key, long handle) { this.key = key; this.handle = handle; }
    }

    private final Map<Key, Entry> byContent = new HashMap<>();
    private final Map<Long, Entry> byHandle = new HashMap<>();
    private long savedImageBytes;

    /** Live texture bytes avoided, excluding allocator alignment and residency. */
    long savedImageBytes() { return savedImageBytes; }

    long acquire(Key key, LongSupplier allocate) {
        Entry existing = byContent.get(key);
        if (existing != null) {
            existing.references++;
            savedImageBytes += key.imageBytes();
            return existing.handle;
        }
        long handle = allocate.getAsLong();
        Entry entry = new Entry(key, handle);
        byContent.put(key, entry);
        byHandle.put(handle, entry);
        return handle;
    }

    /** Returns false for allocations outside this cache. The final owner releases native storage. */
    boolean release(long handle, LongConsumer destroy) {
        Entry entry = byHandle.get(handle);
        if (entry == null) return false;
        if (entry.references == 1) {
            destroy.accept(handle);
            byContent.remove(entry.key);
            byHandle.remove(handle);
        } else {
            entry.references--;
            savedImageBytes -= entry.key.imageBytes();
        }
        return true;
    }
}
