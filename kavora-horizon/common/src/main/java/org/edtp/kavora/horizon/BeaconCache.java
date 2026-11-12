/* SPDX-License-Identifier: LGPL-3.0-only */
package org.edtp.kavora.horizon;

import org.edtp.kavora.horizon.voxel.VoxelDatabase;
import org.edtp.kavora.terrain.lod.FarBeaconBeam;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.BeaconBeamOwner;
import net.minecraft.world.level.chunk.LevelChunk;
import org.rocksdb.RocksDBException;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/** Client observations replace immutable columns. Only the session's IO worker writes them. */
final class BeaconCache {
    private final ConcurrentHashMap<Long, List<FarBeaconBeam>> stored = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, List<FarBeaconBeam>> pending = new ConcurrentHashMap<>();
    private volatile long revision;

    void restore(VoxelDatabase database) throws RocksDBException {
        for (var column : database.beaconColumns().entrySet()) {
            stored.put(column.getKey(), decode(column.getValue()));
        }
        if (!stored.isEmpty()) revision++;
    }

    boolean observe(BeaconBlockEntity beacon) {
        BlockPos pos = beacon.getBlockPos();
        long key = column(pos);
        List<BeaconBeamOwner.Section> sections = beacon.getBeamSections();
        List<FarBeaconBeam> previous = latest(key);
        for (FarBeaconBeam beam : previous) {
            if (beam.pos().equals(pos) && matches(beam.sections(), sections)) return false;
        }
        if (sections.isEmpty()) return forget(pos);

        var replacement = new ArrayList<>(previous);
        replacement.removeIf(beam -> beam.pos().equals(pos));
        replacement.add(new FarBeaconBeam(pos, sections.stream()
                .map(section -> new FarBeaconBeam.Section(section.getColor(), section.getHeight())).toList()));
        pending.put(key, List.copyOf(replacement));
        return true;
    }

    boolean forget(BlockPos pos) {
        if (stored.isEmpty() && pending.isEmpty()) return false;
        long key = column(pos);
        var previous = latest(key);
        for (int i = 0; i < previous.size(); i++) {
            if (!previous.get(i).pos().equals(pos)) continue;
            var next = new ArrayList<>(previous);
            next.remove(i);
            pending.put(key, List.copyOf(next));
            return true;
        }
        return false;
    }

    boolean reconcile(LevelChunk chunk) {
        long key = chunk.getPos().pack();
        var previous = latest(key);
        if (previous.isEmpty()) return false;
        var next = previous.stream().filter(beam -> chunk.getBlockState(beam.pos()).is(Blocks.BEACON)).toList();
        if (next.size() == previous.size()) return false;
        pending.put(key, next);
        return true;
    }

    boolean hasWrites() { return !pending.isEmpty(); }

    long revision() { return revision; }

    void forEachColumn(BiConsumer<Long, List<FarBeaconBeam>> visitor) { stored.forEach(visitor); }

    void flush(VoxelDatabase database) throws RocksDBException {
        for (var column : pending.entrySet()) {
            long key = column.getKey();
            var beams = column.getValue();
            database.writeBeaconColumn(key, encode(beams));
            if (beams.isEmpty()) stored.remove(key);
            else stored.put(key, beams);
            revision++;
            pending.remove(key, beams);
        }
    }

    private List<FarBeaconBeam> latest(long key) {
        var queued = pending.get(key);
        return queued != null ? queued : stored.getOrDefault(key, List.of());
    }

    private static long column(BlockPos pos) { return ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4); }

    private static boolean matches(List<FarBeaconBeam.Section> saved, List<BeaconBeamOwner.Section> live) {
        if (saved.size() != live.size()) return false;
        for (int i = 0; i < saved.size(); i++) {
            var section = live.get(i);
            if (section.getColor() != saved.get(i).color() || section.getHeight() != saved.get(i).height())
                return false;
        }
        return true;
    }

    private static byte[] encode(List<FarBeaconBeam> beams) {
        int bytes = 0;
        for (var beam : beams) bytes += Long.BYTES + Integer.BYTES + 2 * Integer.BYTES * beam.sections().size();
        var out = ByteBuffer.allocate(bytes);
        for (var beam : beams) {
            out.putLong(beam.pos().asLong()).putInt(beam.sections().size());
            for (var section : beam.sections()) out.putInt(section.color()).putInt(section.height());
        }
        return out.array();
    }

    private static List<FarBeaconBeam> decode(byte[] bytes) {
        var in = ByteBuffer.wrap(bytes);
        var beams = new ArrayList<FarBeaconBeam>();
        while (in.hasRemaining()) {
            BlockPos pos = BlockPos.of(in.getLong());
            int count = in.getInt();
            if (count <= 0 || count > in.remaining() / (2 * Integer.BYTES))
                throw new IllegalArgumentException("Invalid cached beacon section count");
            var sections = new ArrayList<FarBeaconBeam.Section>(count);
            for (int i = 0; i < count; i++) sections.add(new FarBeaconBeam.Section(in.getInt(), in.getInt()));
            beams.add(new FarBeaconBeam(pos, sections));
        }
        return List.copyOf(beams);
    }
}
