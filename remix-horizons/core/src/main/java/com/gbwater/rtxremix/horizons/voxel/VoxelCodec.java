/* SPDX-License-Identifier: LGPL-3.0-only */
package com.gbwater.rtxremix.horizons.voxel;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.zip.CRC32C;

/** Versioned local palette and bit-packed indices. RocksDB compresses the resulting records. */
public final class VoxelCodec {
    private static final int MAGIC = 0x52485631;
    private static final ThreadLocal<Palette> PALETTE = ThreadLocal.withInitial(Palette::new);

    /** At most 32768 distinct cells, with a maximum table load of one half. */
    private static final class Palette {
        final int[] keys = new int[65536], slots = new int[65536];
        final int[] cells = new int[VoxelKey.VOLUME], indices = new int[VoxelKey.VOLUME];
        int size;

        void reset() {
            Arrays.fill(slots, 0);
            size = 0;
        }

        int index(int cell) {
            int hash = cell * 0x9E3779B9;
            int slot = (hash ^ (hash >>> 16)) & 65535;
            while (slots[slot] != 0) {
                if (keys[slot] == cell) return slots[slot] - 1;
                slot = (slot + 1) & 65535;
            }
            keys[slot] = cell;
            slots[slot] = size + 1;
            cells[size] = cell;
            return size++;
        }
    }

    private VoxelCodec() {}

    public static byte[] encode(VoxelSection section) {
        Palette palette = PALETTE.get();
        palette.reset();
        int[] ids = palette.indices;
        if (section.cells() == VoxelSection.AIR) palette.index(0);
        else
            for (int i = 0; i < ids.length; i++) {
                int cell = section.cells()[i];
                ids[i] = palette.index(cell);
            }
        int bits = 32 - Integer.numberOfLeadingZeros(palette.size - 1);
        int packedBytes = (VoxelKey.VOLUME * bits + 7) / 8;
        ByteBuffer out = ByteBuffer.allocate(4 + 8 + 2 + 4 + palette.size * 4 + packedBytes + 4);
        out.putInt(MAGIC)
                .putLong(section.revision())
                .put((byte) section.knownChildren())
                .put((byte) section.occupiedChildren())
                .putInt(palette.size);
        for (int i = 0; i < palette.size; i++) out.putInt(palette.cells[i]);
        long word = 0;
        int used = 0;
        if (bits != 0)
            for (int id : ids) {
                word |= (long) id << used;
                used += bits;
                while (used >= 8) {
                    out.put((byte) word);
                    word >>>= 8;
                    used -= 8;
                }
            }
        if (used > 0) out.put((byte) word);
        CRC32C crc = new CRC32C();
        crc.update(out.array(), 0, out.position());
        out.putInt((int) crc.getValue());
        return out.array();
    }

    public static VoxelSection decode(long key, byte[] bytes) {
        if (bytes.length < 26) throw new IllegalArgumentException("Truncated voxel record");
        CRC32C crc = new CRC32C();
        crc.update(bytes, 0, bytes.length - 4);
        ByteBuffer in = ByteBuffer.wrap(bytes);
        if (in.getInt(bytes.length - 4) != (int) crc.getValue() || in.getInt() != MAGIC)
            throw new IllegalArgumentException("Invalid voxel record checksum/version");
        long revision = in.getLong();
        int known = Byte.toUnsignedInt(in.get()), occupied = Byte.toUnsignedInt(in.get());
        int count = in.getInt();
        if (count < 1 || count > VoxelKey.VOLUME)
            throw new IllegalArgumentException("Invalid voxel palette length");
        int bits = 32 - Integer.numberOfLeadingZeros(count - 1);
        if (bytes.length != 4 + 8 + 2 + 4 + count * 4 + (VoxelKey.VOLUME * bits + 7) / 8 + 4)
            throw new IllegalArgumentException("Invalid voxel record length");
        int[] palette = new int[count];
        for (int i = 0; i < count; i++) palette[i] = in.getInt();
        if (count == 1) {
            int[] cells = VoxelSection.AIR;
            if (palette[0] != 0) {
                cells = new int[VoxelKey.VOLUME];
                Arrays.fill(cells, palette[0]);
            }
            return new VoxelSection(key, revision, known, occupied, cells);
        }
        int[] cells = new int[VoxelKey.VOLUME];
        int dataStart = in.position(), mask = (1 << bits) - 1;
        in.order(ByteOrder.LITTLE_ENDIAN);
        // An index spans at most 22 bits including its byte offset. The validated
        // checksum footer keeps the final word load in bounds; the mask excludes it.
        for (int i = 0, bit = 0; i < cells.length; i++, bit += bits) {
            int id = (in.getInt(dataStart + (bit >>> 3)) >>> (bit & 7)) & mask;
            if (id >= count) throw new IllegalArgumentException("Invalid voxel palette index");
            cells[i] = palette[id];
        }
        return new VoxelSection(key, revision, known, occupied, cells);
    }
}
