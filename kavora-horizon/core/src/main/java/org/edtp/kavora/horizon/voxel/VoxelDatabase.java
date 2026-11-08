/* SPDX-License-Identifier: LGPL-3.0-only */
package org.edtp.kavora.horizon.voxel;

import org.rocksdb.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * World/dimension-scoped records. A snapshot and all of its mip ancestors commit in one write
 * batch.
 */
public final class VoxelDatabase implements AutoCloseable {
    private static final byte[] FORMAT = {'F'};
    private final Options options;
    private final WriteOptions writes;
    private final CompressionOptions compression;
    private final RocksDB db;

    static {
        RocksDB.loadLibrary();
    }

    public VoxelDatabase(Path path) throws IOException, RocksDBException {
        Files.createDirectories(path);
        compression = new CompressionOptions().setLevel(1);
        options =
                new Options()
                        .setCreateIfMissing(true)
                        .setCompressionType(CompressionType.ZSTD_COMPRESSION)
                        .setCompressionOptions(compression)
                        .setWriteBufferSize(16L << 20)
                        .setMaxWriteBufferNumber(2)
                        .setMaxBackgroundJobs(2)
                        .setMaxOpenFiles(128);
        writes = new WriteOptions();
        RocksDB opened = null;
        try {
            opened = RocksDB.open(options, path.toString());
            byte[] version = opened.get(FORMAT);
            if (version == null) opened.put(FORMAT, new byte[] {1});
            else if (!java.util.Arrays.equals(version, new byte[] {1}))
                throw new IOException("Unsupported Kavora Horizon cache format: " + path);
            db = opened;
        } catch (IOException | RocksDBException | RuntimeException failure) {
            if (opened != null) opened.close();
            writes.close();
            options.close();
            compression.close();
            throw failure;
        }
    }

    private static byte[] key(char kind, long key) {
        return ByteBuffer.allocate(9).put((byte) kind).putLong(key).array();
    }

    public VoxelSection read(long key) throws RocksDBException {
        byte[] value = db.get(key('V', key));
        return value == null ? null : VoxelCodec.decode(key, value);
    }

    /** Ordered section records, including unknown slots, fetched in one database call. */
    public VoxelSection[] read(long[] keys) throws RocksDBException {
        if (keys.length == 0) return new VoxelSection[0];
        var encoded = new ArrayList<byte[]>(keys.length);
        for (long key : keys) encoded.add(key('V', key));
        var values = db.multiGetAsList(encoded);
        var result = new VoxelSection[keys.length];
        for (int i = 0; i < result.length; i++) {
            byte[] value = values.get(i);
            if (value != null) result[i] = VoxelCodec.decode(keys[i], value);
        }
        return result;
    }

    /** Results keep request order, including null entries for unobserved sections. */
    public VoxelSection.Info[] infos(long[] keys) throws RocksDBException {
        if (keys.length == 0) return new VoxelSection.Info[0];
        var encoded = new ArrayList<byte[]>(keys.length);
        for (long key : keys) encoded.add(key('I', key));
        var values = db.multiGetAsList(encoded);
        var result = new VoxelSection.Info[keys.length];
        for (int i = 0; i < result.length; i++) result[i] = decodeInfo(keys[i], values.get(i));
        return result;
    }

    private static VoxelSection.Info decodeInfo(long key, byte[] bytes) {
        if (bytes == null) return null;
        ByteBuffer value = ByteBuffer.wrap(bytes);
        return new VoxelSection.Info(
                key,
                value.getLong(),
                Byte.toUnsignedInt(value.get()),
                Byte.toUnsignedInt(value.get()));
    }

    public Collection<VoxelSection.Info> roots() throws RocksDBException {
        ArrayList<VoxelSection.Info> result = new ArrayList<>();
        try (RocksIterator iterator = db.newIterator()) {
            for (iterator.seek(key('I', (long) VoxelKey.ROOT_LEVEL << 60));
                    iterator.isValid();
                    iterator.next()) {
                byte[] key = iterator.key();
                if (key.length != 9 || key[0] != 'I') break;
                long pos = ByteBuffer.wrap(key, 1, 8).getLong();
                if (VoxelKey.level(pos) != VoxelKey.ROOT_LEVEL) break;
                result.add(decodeInfo(pos, iterator.value()));
            }
            iterator.status();
        }
        return result;
    }

    public Map<Integer, String> materials() throws RocksDBException {
        Map<Integer, String> result = new LinkedHashMap<>();
        try (RocksIterator iterator = db.newIterator()) {
            for (iterator.seek(key('M', 0)); iterator.isValid(); iterator.next()) {
                byte[] key = iterator.key();
                if (key.length != 9 || key[0] != 'M') break;
                result.put(
                        Math.toIntExact(ByteBuffer.wrap(key, 1, 8).getLong()),
                        new String(iterator.value(), StandardCharsets.UTF_8));
            }
            iterator.status();
        }
        return result;
    }

    public void commit(Collection<VoxelSection> sections, Map<Integer, String> materials)
            throws RocksDBException {
        try (WriteBatch batch = new WriteBatch()) {
            for (var entry : materials.entrySet())
                batch.put(
                        key('M', entry.getKey()),
                        entry.getValue().getBytes(StandardCharsets.UTF_8));
            for (VoxelSection section : sections) {
                batch.put(key('V', section.key()), VoxelCodec.encode(section));
                batch.put(
                        key('I', section.key()),
                        ByteBuffer.allocate(10)
                                .putLong(section.revision())
                                .put((byte) section.knownChildren())
                                .put((byte) section.occupiedChildren())
                                .array());
            }
            db.write(writes, batch);
        }
    }

    @Override
    public void close() throws RocksDBException {
        try {
            db.flushWal(true);
        } finally {
            db.close();
            writes.close();
            options.close();
            compression.close();
        }
    }
}
