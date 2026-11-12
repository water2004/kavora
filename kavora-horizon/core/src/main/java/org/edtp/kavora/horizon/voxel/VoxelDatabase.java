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
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.IntUnaryOperator;

/**
 * World/dimension-scoped records. A snapshot and all of its mip ancestors commit in one write
 * batch.
 */
public final class VoxelDatabase implements AutoCloseable {
    private static final byte[] FORMAT = {'F'};
    private static final byte[] SOURCE_INDEX_CURSOR = {'S'};
    private final Options options;
    private final WriteOptions writes;
    private final CompressionOptions compression;
    private final RocksDB db;
    private final ConcurrentHashMap<Long, int[]> blockSources = new ConcurrentHashMap<>();
    private IntUnaryOperator sourceLevel;
    private BooleanSupplier hasSourceMaterials;
    private long sourceIndexCursor;
    private volatile long sourceRevision;

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

    /** Sparse block-entity snapshots keyed by column, independent of the voxel mip records. */
    public Map<Long, byte[]> beaconColumns() throws RocksDBException {
        Map<Long, byte[]> result = new LinkedHashMap<>();
        try (RocksIterator iterator = db.newIterator()) {
            for (iterator.seek(key('B', 0)); iterator.isValid(); iterator.next()) {
                byte[] key = iterator.key();
                if (key.length != 9 || key[0] != 'B') break;
                result.put(ByteBuffer.wrap(key, 1, 8).getLong(), iterator.value());
            }
            iterator.status();
        }
        return result;
    }

    public void writeBeaconColumn(long column, byte[] snapshot) throws RocksDBException {
        if (snapshot.length == 0) db.delete(writes, key('B', column));
        else db.put(writes, key('B', column), snapshot);
    }

    /** Enables the derived index without changing existing voxel records. */
    public void openBlockSourceIndex(IntUnaryOperator level, BooleanSupplier hasSources)
            throws RocksDBException {
        sourceLevel = level;
        hasSourceMaterials = hasSources;
        byte[] cursor = db.get(SOURCE_INDEX_CURSOR);
        sourceIndexCursor = cursor == null ? 0 : ByteBuffer.wrap(cursor).getLong();
        try (RocksIterator iterator = db.newIterator()) {
            for (iterator.seek(key('S', 0)); iterator.isValid(); iterator.next()) {
                byte[] key = iterator.key();
                if (key.length != 9 || key[0] != 'S') break;
                long section = ByteBuffer.wrap(key, 1, 8).getLong();
                byte[] bytes = iterator.value();
                int[] sources = new int[bytes.length / Integer.BYTES];
                ByteBuffer.wrap(bytes).asIntBuffer().get(sources);
                blockSources.put(section, sources);
            }
            iterator.status();
        }
        if (!blockSources.isEmpty()) sourceRevision++;
        if (sourceIndexCursor == 0 && !hasSources.getAsBoolean()) {
            sourceIndexCursor = Long.MAX_VALUE;
            db.put(SOURCE_INDEX_CURSOR, cursorBytes(sourceIndexCursor));
        }
    }

    /** Scans a bounded slice of legacy level-zero records on the storage thread. */
    public boolean backfillBlockSources() throws RocksDBException {
        if (sourceIndexCursor == Long.MAX_VALUE) return false;
        Map<Long, int[]> changes = new LinkedHashMap<>();
        long next = Long.MAX_VALUE;
        try (RocksIterator iterator = db.newIterator(); WriteBatch batch = new WriteBatch()) {
            iterator.seek(key('V', sourceIndexCursor));
            for (int visited = 0; visited < 32 && iterator.isValid(); visited++, iterator.next()) {
                byte[] encoded = iterator.key();
                if (encoded.length != 9 || encoded[0] != 'V') break;
                long section = ByteBuffer.wrap(encoded, 1, 8).getLong();
                if (VoxelKey.level(section) != 0) break;
                stageSources(batch, changes, section, iterator.value());
                next = section + 1;
            }
            iterator.status();
            batch.put(SOURCE_INDEX_CURSOR, cursorBytes(next));
            db.write(writes, batch);
        }
        sourceIndexCursor = next;
        publishSources(changes);
        return next != Long.MAX_VALUE;
    }

    public long blockSourceRevision() { return sourceRevision; }

    /** Packed level-zero positions grouped by sparse 32-block sections. */
    public void forEachBlockSourceSection(java.util.function.BiConsumer<Long, int[]> action) {
        blockSources.forEach(action);
    }

    private static byte[] cursorBytes(long cursor) {
        return ByteBuffer.allocate(Long.BYTES).putLong(cursor).array();
    }

    private void stageSources(WriteBatch batch, Map<Long, int[]> changes, long section, byte[] encoded)
            throws RocksDBException {
        int[] previous = blockSources.get(section);
        int[] sources = VoxelCodec.blockSources(encoded, sourceLevel);
        if (sources.length == 0) {
            if (previous == null) return;
            batch.delete(key('S', section));
        } else {
            if (Arrays.equals(previous, sources)) return;
            ByteBuffer bytes = ByteBuffer.allocate(sources.length * Integer.BYTES);
            bytes.asIntBuffer().put(sources);
            batch.put(key('S', section), bytes.array());
        }
        changes.put(section, sources);
    }

    private void publishSources(Map<Long, int[]> changes) {
        if (changes.isEmpty()) return;
        changes.forEach((section, sources) -> {
            if (sources.length == 0) blockSources.remove(section);
            else blockSources.put(section, sources);
        });
        sourceRevision++;
    }

    public void commit(Collection<VoxelSection> sections, Map<Integer, String> materials)
            throws RocksDBException {
        boolean indexSources = sourceLevel != null && hasSourceMaterials.getAsBoolean();
        Map<Long, int[]> sourceChanges = indexSources ? new LinkedHashMap<>() : Map.of();
        try (WriteBatch batch = new WriteBatch()) {
            for (var entry : materials.entrySet())
                batch.put(
                        key('M', entry.getKey()),
                        entry.getValue().getBytes(StandardCharsets.UTF_8));
            for (VoxelSection section : sections) {
                byte[] encoded = VoxelCodec.encode(section);
                batch.put(key('V', section.key()), encoded);
                if (indexSources && VoxelKey.level(section.key()) == 0)
                    stageSources(batch, sourceChanges, section.key(), encoded);
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
        publishSources(sourceChanges);
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
