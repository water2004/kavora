/* SPDX-License-Identifier: LGPL-3.0-only */
package org.edtp.kavora.horizon;

import org.edtp.kavora.horizon.voxel.*;
import org.edtp.kavora.profiling.ProfileScope;

import it.unimi.dsi.fastutil.longs.Long2IntLinkedOpenHashMap;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CPU world lifetime. Observations are copied on the client thread; IO and meshing never read the
 * live level.
 */
final class VoxelSession {
    private static final ExecutorService IO =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "Kavora Horizon storage");
                        t.setDaemon(true);
                        return t;
                    });
    private static final int MESH_WORKERS =
            Math.min(3, Math.max(1, Runtime.getRuntime().availableProcessors() / 4));
    private static final ExecutorService BUILD =
            Executors.newFixedThreadPool(
                    MESH_WORKERS,
                    r -> {
                        Thread t = new Thread(r, "Kavora Horizon meshing");
                        t.setDaemon(true);
                        return t;
                    });

    private static final class MeshWorker {
        final VoxelMesher mesher = new VoxelMesher();
        MaterialCatalog catalog;
        MaterialCatalog.OcclusionView occlusion;

        MaterialCatalog.OcclusionView occlusion(MaterialCatalog next) {
            if (catalog != next) {
                catalog = next;
                occlusion = next.new OcclusionView();
            }
            return occlusion;
        }
    }

    private static final ThreadLocal<MeshWorker> MESHER = ThreadLocal.withInitial(MeshWorker::new);
    private static final int[] EMPTY = new int[4096];
    private static final int MAX_CHILD_REQUESTS = 64, CHILD_BATCH_SIZE = 16;

    static Executor workers() {
        return BUILD;
    }

    /** A key remains queued at most once; polling and publishing share the same lock. */
    static final class ChangedKeys {
        private final Long2IntLinkedOpenHashMap keys = new Long2IntLinkedOpenHashMap();

        synchronized void add(long key, int meshChanges) {
            keys.put(key, keys.get(key) | meshChanges);
        }

        synchronized UpdateKey poll() {
            if (keys.isEmpty()) return null;
            long key = keys.firstLongKey();
            return new UpdateKey(key, keys.removeFirstInt());
        }
    }

    record UpdateKey(long key, int meshChanges) {}

    // Sections include snapshots needed only as upper neighbours; updated marks what to ingest.
    record Column(int x, int z, int bottom, LevelChunkSection[] sections, BitSet updated) {
        Column merge(Column newer) {
            if (x != newer.x
                    || z != newer.z
                    || bottom != newer.bottom
                    || sections.length != newer.sections.length)
                throw new IllegalArgumentException("Incompatible column snapshots");
            LevelChunkSection[] merged = sections.clone();
            for (int i = 0; i < merged.length; i++)
                if (newer.sections[i] != null) merged[i] = newer.sections[i];
            BitSet changes = (BitSet) updated.clone();
            changes.or(newer.updated);
            return new Column(x, z, bottom, merged, changes);
        }
    }

    final ClientLevel level;
    final MaterialCatalog materials = new MaterialCatalog();
    final BeaconCache beacons = new BeaconCache();
    final ConcurrentHashMap<Long, VoxelSection.Info> metadata = new ConcurrentHashMap<>();
    final ChangedKeys changed = new ChangedKeys();
    final Set<Long> roots = ConcurrentHashMap.newKeySet();
    private final LinkedHashSet<Long> requestedChildren = new LinkedHashSet<>();
    private boolean childrenScheduled;
    private final ConcurrentHashMap<Long, Column> observations = new ConcurrentHashMap<>();
    private final AtomicBoolean pumping = new AtomicBoolean();
    private volatile Throwable failure;
    private volatile boolean ready, accepting = true;
    private VoxelDatabase database;
    private VoxelWorld world;

    private record MeshRequest(long key, CompletableFuture<VoxelMesher.Mesh> future) {}

    // IO owns this queue. Waiting requests hold keys, not seven decoded section snapshots each.
    private final ArrayDeque<MeshRequest> meshRequests = new ArrayDeque<>();
    private int activeMeshes;
    // Only the IO thread accesses initialized and the database. Close waits for initialization
    // even if it is still chained behind the previous session's close.
    private boolean initialized;
    private final CompletableFuture<Void> closed = new CompletableFuture<>();

    VoxelSession(ClientLevel level, Path directory, CompletableFuture<Void> previousClose) {
        this.level = level;
        previousClose.whenCompleteAsync(
                (ignored, previousFailure) -> {
                    try {
                        if (previousFailure != null)
                            throw new IllegalStateException(
                                    "Previous voxel session failed to close", previousFailure);
                        if (!accepting && !hasPendingObservations()) {
                            initialized = true;
                            closeDatabase();
                            return;
                        }
                        database = new VoxelDatabase(directory);
                        world = new VoxelWorld(database, 1024);
                        materials.restore(database.materials());
                        database.openBlockSourceIndex(materials::sourceCode, materials::hasBlockSources);
                        beacons.restore(database);
                        for (var info : database.roots()) {
                            metadata.put(info.key(), info);
                            roots.add(info.key());
                        }
                        initialized = true;
                        ready = true;
                        if (!accepting && !hasPendingObservations()) closeDatabase();
                        else {
                            schedulePump();
                            IO.execute(this::backfillBlockSources);
                        }
                    } catch (Throwable e) {
                        failure = e;
                        initialized = true;
                        closeDatabase();
                    }
                },
                IO);
    }

    boolean ready() {
        checkFailure();
        return ready;
    }

    private void backfillBlockSources() {
        try {
            if (accepting && database.backfillBlockSources()) IO.execute(this::backfillBlockSources);
        } catch (Throwable e) {
            failure = e;
        }
    }

    long blockSourceRevision() { return database.blockSourceRevision(); }

    void forEachBlockSourceSection(java.util.function.BiConsumer<Long, int[]> action) {
        database.forEachBlockSourceSection(action);
    }

    int pendingColumns() {
        return observations.size();
    }

    void checkFailure() {
        Throwable error = failure;
        if (error != null) throw new IllegalStateException("Voxel storage failed", error);
    }

    /**
     * LevelChunkSection.copy copies palette storage; no mutable chunk remains in a worker's
     * payload.
     */
    void observe(LevelChunk chunk) {
        if (!accepting) return;
        BitSet all = new BitSet(chunk.getSections().length);
        all.set(0, chunk.getSections().length);
        observeSections(chunk, all);
    }

    /** Copy changed sections and their upper neighbours while still on the client thread. */
    void observeSections(LevelChunk chunk, BitSet changes) {
        if (!accepting) return;
        LevelChunkSection[] source = chunk.getSections(),
                snapshot = new LevelChunkSection[source.length];
        BitSet updated = (BitSet) changes.clone();
        updated.clear(source.length, Math.max(source.length, updated.length()));
        if (updated.isEmpty()) return;
        for (int i = updated.nextSetBit(0); i >= 0; i = updated.nextSetBit(i + 1)) {
            if (snapshot[i] == null) snapshot[i] = source[i].copy();
            if (i + 1 < source.length && snapshot[i + 1] == null)
                snapshot[i + 1] = source[i + 1].copy();
        }
        var pos = chunk.getPos();
        Column column = new Column(pos.x(), pos.z(), chunk.getMinSectionY(), snapshot, updated);
        observations.merge(ChunkPos.pack(pos.x(), pos.z()), column, Column::merge);
        schedulePump();
    }

    private boolean hasPendingObservations() { return !observations.isEmpty() || beacons.hasWrites(); }

    void schedulePump() {
        if (ready && pumping.compareAndSet(false, true)) IO.execute(this::pump);
    }

    private void pump() {
        var conversions = new ArrayList<CompletableFuture<List<VoxelWorld.Observation>>>(4);
        for (var item : observations.entrySet()) {
            if (!observations.remove(item.getKey(), item.getValue())) continue;
            Column column = item.getValue();
            conversions.add(CompletableFuture.supplyAsync(() -> convert(column), BUILD));
            if (conversions.size() == 4) break;
        }
        // Keep storage available for visible mesh and metadata reads while CPU conversion runs.
        CompletableFuture.allOf(conversions.toArray(CompletableFuture[]::new))
                .whenCompleteAsync((ignored, error) -> commitObservations(conversions, error), IO);
    }

    private void commitObservations(
            List<CompletableFuture<List<VoxelWorld.Observation>>> conversions, Throwable error) {
        try (var ignored = ProfileScope.beginJfrOnly("Lod.Ingest")) {
            if (error != null) throw new CompletionException(error);
            ArrayList<VoxelWorld.Observation> batch = new ArrayList<>();
            for (var conversion : conversions) batch.addAll(conversion.join());
            if (!batch.isEmpty()) {
                for (VoxelWorld.Update update :
                        world.apply(batch, materials.drainDictionary(), materials::opacity)) {
                    VoxelSection section = update.section();
                    metadata.put(section.key(), section.info());
                    changed.add(section.key(), update.meshChanges());
                    if (VoxelKey.level(section.key()) == VoxelKey.ROOT_LEVEL)
                        roots.add(section.key());
                }
            }
            beacons.flush(database);
        } catch (Throwable e) {
            failure = e;
            observations.clear();
        } finally {
            pumping.set(false);
            if (failure == null && hasPendingObservations()) schedulePump();
            else if (!accepting) closeDatabase();
        }
    }

    private List<VoxelWorld.Observation> convert(Column column) {
        try (var ignored = ProfileScope.beginJfrOnly("Lod.Convert")) {
            ArrayList<VoxelWorld.Observation> target = new ArrayList<>(column.updated().cardinality());
            convert(column, target);
            return target;
        }
    }

    private void convert(Column column, List<VoxelWorld.Observation> target) {
        for (int i = column.updated().nextSetBit(0);
                i >= 0;
                i = column.updated().nextSetBit(i + 1)) {
            LevelChunkSection section = column.sections()[i];
            if (section.hasOnlyAir()) {
                target.add(
                        new VoxelWorld.Observation(
                                column.x(), column.bottom() + i, column.z(), EMPTY));
                continue;
            }
            String[] biomes = new String[64];
            for (int y = 0; y < 4; y++)
                for (int z = 0; z < 4; z++)
                    for (int x = 0; x < 4; x++)
                        biomes[x | z << 2 | y << 4] =
                                section.getNoiseBiome(x, y, z)
                                        .unwrapKey()
                                        .orElseThrow()
                                        .identifier()
                                        .toString();
            IdentityHashMap<BlockState, int[]> ids = new IdentityHashMap<>();
            int[] cells = new int[4096];
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (state.isAir()) continue;
                        int biome = (x >> 2) | (z >> 2) << 2 | (y >> 2) << 4;
                        int[] palette = ids.computeIfAbsent(state, ignored -> new int[64]);
                        int id = palette[biome];
                        if (id == 0) palette[biome] = id = materials.register(state, biomes[biome]);
                        int value = id;
                        var fluid = state.getFluidState();
                        if (!fluid.isEmpty()) {
                            BlockState above =
                                    y < 15
                                            ? section.getBlockState(x, y + 1, z)
                                            : i + 1 < column.sections().length
                                                    ? column.sections()[i + 1].getBlockState(
                                                            x, 0, z)
                                                    : null;
                            int top =
                                    above != null
                                                    && above.getFluidState()
                                                            .getType()
                                                            .isSame(fluid.getType())
                                            ? 9
                                            : Math.round(fluid.getOwnHeight() * 9);
                            value = VoxelSection.withFluidTop(id, top);
                        }
                        cells[x | z << 4 | y << 8] = value;
                    }
            target.add(
                    new VoxelWorld.Observation(column.x(), column.bottom() + i, column.z(), cells));
        }
    }

    boolean childrenReady(long key) {
        if (!accepting || !ready) return false;
        if (hasChildren(key)) return true;
        synchronized (requestedChildren) {
            if (!accepting || hasChildren(key)) return accepting;
            if (requestedChildren.size() >= MAX_CHILD_REQUESTS && !requestedChildren.contains(key))
                return false;
            requestedChildren.add(key);
            if (!childrenScheduled) {
                childrenScheduled = true;
                IO.execute(this::loadChildren);
            }
        }
        return false;
    }

    private boolean hasChildren(long key) {
        var info = metadata.get(key);
        if (info == null) return false;
        for (int octant = 0; octant < 8; octant++)
            if ((info.occupiedChildren() & (1 << octant)) != 0
                    && !metadata.containsKey(VoxelKey.child(key, octant))) return false;
        return true;
    }

    private void loadChildren() {
        try (var ignored = ProfileScope.beginJfrOnly("Lod.Metadata")) {
            long[] parents;
            synchronized (requestedChildren) {
                parents = new long[Math.min(CHILD_BATCH_SIZE, requestedChildren.size())];
                var iterator = requestedChildren.iterator();
                for (int i = 0; i < parents.length; i++) parents[i] = iterator.next();
            }
            if (!accepting || parents.length == 0) return;
            long[] children = new long[parents.length * 8];
            int count = 0;
            for (long parent : parents) {
                var info = metadata.get(parent);
                for (int octant = 0; octant < 8; octant++)
                    if ((info.occupiedChildren() & (1 << octant)) != 0) {
                        long child = VoxelKey.child(parent, octant);
                        if (!metadata.containsKey(child)) children[count++] = child;
                    }
            }
            var infos = database.infos(Arrays.copyOf(children, count));
            if (!accepting) return;
            for (int i = 0; i < infos.length; i++) {
                var info = infos[i];
                if (info == null)
                    throw new IllegalStateException("Missing observed child " + VoxelKey.describe(children[i]));
                metadata.putIfAbsent(info.key(), info);
            }
            synchronized (requestedChildren) {
                for (long parent : parents) {
                    requestedChildren.remove(parent);
                }
            }
        } catch (Throwable e) {
            failure = e;
        } finally {
            synchronized (requestedChildren) {
                if (!accepting || failure != null) requestedChildren.clear();
                if (requestedChildren.isEmpty()) childrenScheduled = false;
                else IO.execute(this::loadChildren);
            }
        }
    }

    CompletableFuture<VoxelMesher.Mesh> mesh(long key) {
        CompletableFuture<VoxelMesher.Mesh> result = new CompletableFuture<>();
        if (!accepting || !ready) {
            result.cancel(false);
            return result;
        }
        IO.execute(
                () -> {
                    meshRequests.addLast(new MeshRequest(key, result));
                    pumpMeshes();
                });
        return result;
    }

    private void pumpMeshes() {
        if (!accepting) {
            for (MeshRequest request; (request = meshRequests.pollFirst()) != null; )
                request.future().cancel(false);
            return;
        }
        while (activeMeshes < MESH_WORKERS && !meshRequests.isEmpty()) {
            MeshRequest request = meshRequests.removeFirst();
            CompletableFuture<VoxelMesher.Mesh> result = request.future();
            if (result.isDone()) continue;
            long key = request.key();
            try (var ignored = ProfileScope.beginJfrOnly("Lod.MeshRead")) {
                VoxelSection source = world.get(key);
                if (source == null)
                    throw new IllegalStateException(
                            "Missing observed section " + VoxelKey.describe(key));
                if (source.info().empty()) {
                    result.complete(new VoxelMesher.Mesh(key, source.revision(), new long[0]));
                    continue;
                }
                VoxelSection[] snapshot = world.neighborhood(key);
                activeMeshes++;
                BUILD.execute(
                        () -> {
                            try (var meshScope = ProfileScope.beginJfrOnly("Lod.Mesh")) {
                                if (!accepting || result.isDone()) {
                                    result.cancel(false);
                                    return;
                                }
                                MeshWorker worker = MESHER.get();
                                var mesh =
                                        worker.mesher.build(snapshot, worker.occlusion(materials));
                                if (accepting) result.complete(mesh);
                                else result.cancel(false);
                            } catch (Throwable e) {
                                result.completeExceptionally(e);
                            } finally {
                                IO.execute(
                                        () -> {
                                            activeMeshes--;
                                            pumpMeshes();
                                        });
                            }
                        });
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        }
    }

    CompletableFuture<Void> close() {
        accepting = false;
        IO.execute(
                () -> {
                    pumpMeshes();
                    if (!initialized) return;
                    if ((!hasPendingObservations() || failure != null) && !pumping.get()) closeDatabase();
                    else schedulePump();
                });
        return closed;
    }

    private void closeDatabase() {
        if (closed.isDone()) return;
        try {
            if (database != null) database.close();
            if (failure == null) closed.complete(null);
            else closed.completeExceptionally(failure);
        } catch (Throwable e) {
            failure = e;
            closed.completeExceptionally(e);
        }
    }
}
