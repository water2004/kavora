/* SPDX-License-Identifier: LGPL-3.0-only */
package org.edtp.kavora.horizon.voxel;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

import org.rocksdb.RocksDBException;

import java.util.*;
import java.util.function.IntUnaryOperator;

/**
 * One IO owner coalesces observations before propagation. Mesh workers retain immutable snapshots.
 */
public final class VoxelWorld {
    public record Observation(int x, int y, int z, int[] cells) {}

    /** Bit 0: cells changed. Bits 1..6: boundary material changed in -X,+X,-Y,+Y,-Z,+Z order. */
    public record Update(VoxelSection section, int meshChanges) {}

    private final VoxelDatabase database;
    private final Long2ObjectLinkedOpenHashMap<VoxelSection> resident =
            new Long2ObjectLinkedOpenHashMap<>(128);
    private final int capacity;
    private final int[] mipSamples = new int[16 * 16 * 16];

    public VoxelWorld(VoxelDatabase database, int capacity) {
        if (capacity < 8) throw new IllegalArgumentException("Voxel cache too small");
        this.database = database;
        this.capacity = capacity;
    }

    public VoxelSection get(long key) throws RocksDBException {
        VoxelSection result = resident.getAndMoveToLast(key);
        if (result == null && !resident.containsKey(key)) {
            result = database.read(key);
            cache(key, result);
        }
        return result;
    }

    private void cache(long key, VoxelSection section) {
        resident.putAndMoveToLast(key, section);
        while (resident.size() > capacity) resident.removeFirst();
    }

    public Collection<Update> apply(
            List<Observation> observations,
            Map<Integer, String> materials,
            IntUnaryOperator opacity)
            throws RocksDBException {
        Long2ObjectLinkedOpenHashMap<VoxelSection> changed = new Long2ObjectLinkedOpenHashMap<>();
        Long2ObjectLinkedOpenHashMap<VoxelSection.Editor> edits =
                new Long2ObjectLinkedOpenHashMap<>();
        ArrayList<Update> updates = new ArrayList<>(observations.size());
        for (Observation observation : observations) {
            long key =
                    VoxelKey.of(
                            0, observation.x() >> 1, observation.y() >> 1, observation.z() >> 1);
            VoxelSection.Editor editor = edit(edits, key);
            int octant =
                    (observation.x() & 1) | (observation.z() & 1) << 1 | (observation.y() & 1) << 2;
            editor.replaceOctant(octant, observation.cells());
        }
        Long2ObjectLinkedOpenHashMap<VoxelSection> levelChanges = finish(edits, updates);
        for (int level = 0; level <= VoxelKey.ROOT_LEVEL && !levelChanges.isEmpty(); level++) {
            changed.putAll(levelChanges);
            if (level == VoxelKey.ROOT_LEVEL) break;
            edits.clear();
            for (VoxelSection child : levelChanges.values()) {
                long key = VoxelKey.parent(child.key());
                VoxelSection.Editor editor = edit(edits, key);
                int octant = VoxelKey.octant(child.key());
                VoxelMip.reduceInto(child, opacity, mipSamples);
                editor.replaceOctant(octant, mipSamples);
                // A parent must advertise an existing child even if its representative samples are
                // empty.
                editor.setOccupied(octant, child.occupiedChildren() != 0);
            }
            levelChanges = finish(edits, updates);
        }
        database.commit(changed.values(), materials);
        for (VoxelSection section : changed.values()) cache(section.key(), section);
        return updates;
    }

    private VoxelSection.Editor edit(Long2ObjectLinkedOpenHashMap<VoxelSection.Editor> edits, long key)
            throws RocksDBException {
        VoxelSection.Editor editor = edits.get(key);
        if (editor == null) {
            editor = new VoxelSection.Editor(key, get(key));
            edits.put(key, editor);
        }
        return editor;
    }

    private static Long2ObjectLinkedOpenHashMap<VoxelSection> finish(
            Long2ObjectLinkedOpenHashMap<VoxelSection.Editor> edits, List<Update> updates) {
        var result = new Long2ObjectLinkedOpenHashMap<VoxelSection>();
        for (VoxelSection.Editor editor : edits.values()) {
            VoxelSection section = editor.finish();
            if (section != editor.previous) {
                result.put(section.key(), section);
                updates.add(new Update(section, editor.meshChanges()));
            }
        }
        return result;
    }

    /** Order: -X,+X,-Y,+Y,-Z,+Z. Missing records remain null rather than becoming stored air. */
    public VoxelSection[] neighborhood(long key) throws RocksDBException {
        long[] keys = new long[7];
        keys[0] = key;
        for (int axis = 0; axis < 3; axis++)
            for (int sign = 0; sign < 2; sign++)
                keys[1 + axis * 2 + sign] = VoxelKey.neighbor(key, axis, sign == 0 ? -1 : 1);
        VoxelSection[] result = new VoxelSection[7];
        long[] missing = new long[7];
        int count = 0, missingMask = 0;
        for (int i = 0; i < keys.length; i++) {
            result[i] = resident.getAndMoveToLast(keys[i]);
            if (result[i] == null && !resident.containsKey(keys[i])) {
                missing[count++] = keys[i];
                missingMask |= 1 << i;
            }
        }
        if (count != 0) {
            var loaded = database.read(Arrays.copyOf(missing, count));
            for (int i = 0, next = 0; i < keys.length; i++) {
                if ((missingMask & 1 << i) == 0) continue;
                result[i] = loaded[next++];
                // A cached null is unobserved. apply replaces it on first observation.
                cache(keys[i], result[i]);
            }
        }
        return result;
    }
}
