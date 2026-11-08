/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.asset;

import org.edtp.kavora.sdk.TextureFormat;
import org.edtp.kavora.sdk.TextureFromMemoryInfo;
import org.edtp.kavora.sdk.TexturePurpose;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/** Render-thread ownership of identical immutable images. Keeps no pixel copies or idle images. */
final class ImmutableTextureCache {
    /** A constant scalar map samples identically at every coordinate and generated mip level. */
    static TextureFromMemoryInfo compactScalar(TextureFromMemoryInfo source) {
        if (!source.immutable || source.format != TextureFormat.R8_UNORM
                || source.purpose != TexturePurpose.DATA || source.mipLevels != 0
                || (source.width == 1 && source.height == 1)) return source;
        ByteBuffer pixels = source.pixelData;
        if ((long) source.width * source.height != pixels.remaining() || !pixels.hasRemaining()) return source;
        byte value = pixels.get(pixels.position());
        long repeated = (value & 255L) * 0x0101010101010101L;
        int offset = pixels.position();
        while (pixels.limit() - offset >= Long.BYTES) {
            if (pixels.getLong(offset) != repeated) return source;
            offset += Long.BYTES;
        }
        while (offset < pixels.limit()) {
            if (pixels.get(offset++) != value) return source;
        }
        ByteBuffer texel = pixels.duplicate();
        texel.limit(texel.position() + 1);
        return TextureFromMemoryInfo.builder().hash(source.hash).size(1, 1)
                .format(source.format).purpose(source.purpose).immutable(true)
                .pixelData(texel.slice()).build();
    }

    record Key(int width, int height, int format, int purpose, int mipLevels, String pixels) {
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

        static Key of(int width, int height, int format, int purpose, int mipLevels, ByteBuffer pixels) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                digest.update(pixels.duplicate());
                // COLOR differs from DATA only when the runtime generates sRGB mip levels.
                int mipPurpose = purpose == 0 && (mipLevels > 0 || (format != 1 && format != 3)) ? 1 : purpose;
                return new Key(width, height, format, mipPurpose, mipLevels, HexFormat.of().formatHex(digest.digest()));
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
