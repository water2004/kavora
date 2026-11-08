/* SPDX-License-Identifier: LGPL-3.0-only */
package com.gbwater.rtxremix.horizons;

import com.gbwater.rtxremix.horizons.voxel.*;
import com.nvidia.rtxremix.*;
import com.nvidia.rtxremix.minecraft.asset.*;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.terrain.lod.FarTerrainExtension;
import com.nvidia.rtxremix.minecraft.terrain.lod.NearSection;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;

import it.unimi.dsi.fastutil.longs.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;

import java.util.*;
import java.util.concurrent.*;

/** Render-thread ownership of a voxel octree in the shared Remix scene. */
public final class RemixHorizons implements FarTerrainExtension {
    private static final long NATIVE_WORK_NS = 1_500_000;
    private static final int MAX_STAGING_BATCHES = 8,
            UPLOAD_BYTES = 4 << 20,
            RETIRE_BYTES = 4 << 20;

    private static final boolean CULL_COVERED_PAGES =
            !Boolean.getBoolean("rtxremix.horizons.disableCoveredPageCulling");
    private static final boolean FORCE_COVERAGE_CLIPPING =
            Boolean.getBoolean("rtxremix.horizons.forceCoverageClipping");

    private static final class Page {
        final RemixInstance instance;
        final TerrainMeshConverter.Bounds bounds;
        final long bytes;
        boolean visible, clipped = true;

        Page(RemixInstance instance, TerrainMeshConverter.Bounds bounds, long bytes) {
            this.instance = instance;
            this.bounds = bounds;
            this.bytes = bytes;
        }
    }

    private static final class Handle {
        final long key;
        final long revision;
        final long[] quads;
        final ArrayList<Page> pages = new ArrayList<>();
        long bytes;
        boolean visible, dirty;
        long coverageRevision = -1;

        Handle(long key, long revision, long[] quads) {
            this.key = key;
            this.revision = revision;
            this.quads = quads;
        }
    }

    private static final class Job {
        final long key, stamp;
        final CompletableFuture<VoxelMesher.Mesh> mesh;
        final CompletableFuture<TerrainMeshConverter.Cursor> preparation;
        TerrainMeshConverter.Cursor cursor;
        Handle handle;
        Handle reused;
        TerrainMeshStream stream;
        TerrainMeshStream.Batch batch;
        int page;
        boolean done;

        Job(long key, long stamp, CompletableFuture<VoxelMesher.Mesh> mesh,
                float waterTileSize, Group group) {
            this.key = key;
            this.stamp = stamp;
            this.mesh = mesh;
            preparation = mesh.thenApplyAsync(
                    result -> new TerrainMeshConverter.Cursor(result, waterTileSize, () -> group.cancelled),
                    VoxelSession.workers());
        }
    }

    private static final class Group {
        final long[] keys, replacedKeys;
        final ArrayList<Job> jobs = new ArrayList<>();
        final CompletableFuture<Boolean> future = new CompletableFuture<>();
        TerrainResidencyBudget.Reservation reservation;
        long estimate, replaced, uploaded;
        volatile boolean cancelled;

        Group(long[] keys, long[] replacedKeys) {
            this.keys = keys;
            this.replacedKeys = replacedKeys;
        }
    }

    private final Long2ObjectOpenHashMap<Handle> handles = new Long2ObjectOpenHashMap<>();
    private final Long2LongOpenHashMap changes = new Long2LongOpenHashMap();
    private final ArrayList<Group> groups = new ArrayList<>();
    private final ArrayDeque<Handle> retiring = new ArrayDeque<>();
    private final LongArrayList selected = new LongArrayList();
    private final LongOpenHashSet selection = new LongOpenHashSet();
    private final Long2ObjectOpenHashMap<Set<NearSection>> near = new Long2ObjectOpenHashMap<>();
    private final NearCoverage coverage = new NearCoverage();
    private final ProjectedError view = new ProjectedError();
    private final TerrainMeshStream.Budget staging = new TerrainMeshStream.Budget(MAX_STAGING_BATCHES);
    private final Matrix4f projection = new Matrix4f();
    private final float[] matrix = new float[16];
    private VoxelSession session;
    private ResidentOctree tree;
    private TerrainResidencyBudget budget;
    private VoxelMeshCache meshCache;
    private TerrainMaterialPalette palette;
    private MaterialCatalog.View materials;
    private RemixAPI api;
    private BlockTextureManager textures;
    private long generation, mutations, capacityRevision, changeSequence;
    private long uploadedTotal,
            retiredTotal,
            completedGroups,
            rejectedGroups,
            completedEstimate,
            completedBytes;
    private int failures, geometryMiB;
    private boolean memoryBlocked;

    static FarTerrainExtension scene() {
        return new RemixHorizons();
    }

    @Override
    public void tick(RemixAPI api, BlockTextureManager textures, ClientLevel world, Vec3 camera) {
        mutations = 0;
        if (!HorizonsSettings.INSTANCE.enabled) {
            if (session != null) clearGraphics();
            return;
        }
        VoxelSession store = HorizonsClient.session(world);
        if (session != store || geometryMiB != HorizonsSettings.INSTANCE.geometryMiB) {
            clearGraphics();
            this.api = api;
            this.textures = textures;
            session = store;
            generation++;
            geometryMiB = HorizonsSettings.INSTANCE.geometryMiB;
            meshCache = new VoxelMeshCache((long) Math.min(32, geometryMiB / 8) << 20);
            budget =
                    new TerrainResidencyBudget(
                            (long) geometryMiB << 20, Math.min(64, geometryMiB / 4) * 1048576L);
            palette = new TerrainMaterialPalette(textures);
            materials = session.materials.view(palette);
            tree =
                    new ResidentOctree(
                            new ResidentOctree.Backend() {
                                public Collection<Long> roots() {
                                    return session.roots;
                                }

                                public VoxelSection.Info info(long key) {
                                    return session.metadata.get(key);
                                }

                                public boolean childrenReady(long key) {
                                    return session.childrenReady(key);
                                }

                                public boolean within(long key) {
                                    return view.within(key, renderRadiusBlocks());
                                }

                                public double area(long key) {
                                    return view.area(key);
                                }

                                public double splitArea() {
                                    return (double) HorizonsSettings.INSTANCE.nodePixels
                                            * HorizonsSettings.INSTANCE.nodePixels;
                                }

                                public boolean resident(long key) {
                                    return handles.containsKey(key);
                                }

                                public boolean dirty(long key) {
                                    Handle h = handles.get(key);
                                    return h != null && h.dirty;
                                }

                                public CompletableFuture<Boolean> request(
                                        long[] keys, long[] replaced) {
                                    return requestGroup(keys, replaced);
                                }

                                public void cancel(long[] keys) {
                                    for (Group group : groups)
                                        if (group.keys == keys) cancelGroup(group);
                                }

                                public void retire(long key) {
                                    Handle handle = handles.remove(key);
                                    if (handle != null) {
                                        if (!handle.dirty)
                                            meshCache.put(new VoxelMesher.Mesh(handle.key, handle.revision, handle.quads));
                                        setVisible(handle, false);
                                        retiring.add(handle);
                                    }
                                }

                                public long capacityRevision() {
                                    return capacityRevision;
                                }

                                public boolean memoryPressure() {
                                    return memoryBlocked && retiring.isEmpty();
                                }
                            });
        }
        this.api = api;
        this.textures = textures;
        coverage.update(camera, world);
        if (!store.ready()) return;
        var client = Minecraft.getInstance();
        client.gameRenderer.mainCamera().getViewRotationProjectionMatrix(projection).get(matrix);
        view.set(
                camera.x,
                camera.y,
                camera.z,
                matrix,
                client.getWindow().getWidth(),
                client.getWindow().getHeight());
        for (VoxelSession.UpdateKey update; (update = store.changed.poll()) != null; ) {
            if (update.meshChanges() == 0) continue;
            long key = update.key();
            changeSequence++;
            if ((update.meshChanges() & 1) != 0) invalidate(key);
            for (int axis = 0; axis < 3; axis++)
                for (int sign = -1; sign <= 1; sign += 2) {
                    int bit = 1 + axis * 2 + (sign > 0 ? 1 : 0);
                    if ((update.meshChanges() & (1 << bit)) == 0) continue;
                    long adjacent = VoxelKey.neighbor(key, axis, sign);
                    invalidate(adjacent);
                }
        }
        palette.drain(16);
        long deadline = System.nanoTime() + NATIVE_WORK_NS;
        try (var ignored = ProfileScope.begin("Lod.Retire")) {
            retirePages(false, deadline);
        }
        prepareGroups();
        try (var ignored = ProfileScope.begin("Lod.Upload")) {
            upload(deadline);
        }
        tree.tick(selected);
        selection.clear();
        selection.addAll(selected);
    }

    private void invalidate(long key) {
        changes.put(key, changeSequence);
        meshCache.take(key);
        Handle handle = handles.get(key);
        if (handle != null) handle.dirty = true;
    }

    private CompletableFuture<Boolean> requestGroup(long[] keys, long[] replaced) {
        Group group = new Group(keys, replaced);
        for (long key : keys) {
            var cached = meshCache.take(key);
            var mesh = cached == null ? session.mesh(key) : CompletableFuture.completedFuture(cached);
            group.jobs.add(new Job(key, changes.getOrDefault(key, 0L), mesh,
                    BlockTextureManager.getWaterTileSize(), group));
        }
        groups.add(group);
        return group.future;
    }

    private void prepareGroups() {
        for (Group group : groups) {
            if (group.cancelled || group.future.isDone()) continue;
            try {
                boolean ready = true;
                long estimate = 0;
                for (Job job : group.jobs) {
                    if (job.reused != null) continue;
                    if (job.cursor == null) {
                        if (!job.preparation.isDone()) {
                            ready = false;
                            continue;
                        }
                        var mesh = job.mesh.join();
                        Handle previous = handles.get(job.key);
                        if (previous != null && Arrays.equals(previous.quads, mesh.quads())) {
                            job.reused = previous;
                            job.done = true;
                            continue;
                        }
                        job.cursor = job.preparation.join();
                        job.handle = new Handle(job.key, mesh.revision(), mesh.quads());
                    }
                    if (!job.cursor.resolve(materials::resolve)) {
                        ready = false;
                        continue;
                    }
                    estimate = Math.addExact(estimate, job.cursor.geometryBytes());
                }
                if (!ready || group.reservation != null) continue;
                group.estimate = estimate;
                group.replaced = replacedBytes(group);
                group.reservation = budget.reserveTransition(estimate, group.replaced);
                if (group.reservation == null) {
                    memoryBlocked = true;
                    if (retiring.isEmpty() && budget.promisedBytes() == 0) {
                        rejectedGroups++;
                        group.future.complete(false);
                        cancelGroup(group);
                    }
                } else {
                    for (Job job : group.jobs) {
                        if (job.done) continue;
                        job.stream = new TerrainMeshStream(job.cursor, VoxelSession.workers(), staging);
                        job.stream.resume();
                    }
                }
            } catch (RuntimeException | Error error) {
                fail(group, error);
            }
        }
    }

    private long replacedBytes(Group group) {
        long bytes = 0;
        for (long key : group.replacedKeys) {
            Handle handle = handles.get(key);
            if (handle != null && handle.visible)
                for (Job job : group.jobs)
                    if (job.reused == null
                            && (VoxelKey.contains(handle.key, job.key)
                                    || VoxelKey.contains(job.key, handle.key))) {
                        bytes += handle.bytes;
                        break;
                    }
        }
        return bytes;
    }

    private void upload(long deadline) {
        long uploadedBytes = 0;
        for (Group group : groups) {
            if (group.cancelled || group.reservation == null) continue;
            try {
                boolean complete = true;
                for (Job job : group.jobs) {
                    if (job.done) continue;
                    job.stream.resume();
                    if (job.batch == null) {
                        job.batch = job.stream.poll();
                        if (job.batch == null) {
                            job.done = job.stream.isComplete();
                            complete &= job.done;
                            continue;
                        }
                        job.page = 0;
                    }
                    var batch = job.batch.result;
                    while (job.page < batch.pages.size()
                            && uploadedBytes < UPLOAD_BYTES
                            && System.nanoTime() < deadline) {
                        var page = batch.pages.get(job.page++);
                        long bytes = page.geometryBytes();
                        createPage(job.handle, page);
                        job.handle.bytes += bytes;
                        group.reservation.uploaded(bytes);
                        uploadedBytes += bytes;
                        group.uploaded += bytes;
                        uploadedTotal += bytes;
                    }
                    if (job.page == batch.pages.size()) {
                        job.batch.close();
                        job.batch = null;
                        job.done = job.stream.isComplete();
                    }
                    complete &= job.done;
                }
                if (complete) {
                    completedGroups++;
                    completedEstimate += group.estimate;
                    completedBytes += group.uploaded;
                    for (Job job : group.jobs) {
                        boolean dirty = job.stamp < changes.get(job.key);
                        if (job.reused != null) {
                            job.reused.dirty = dirty;
                            continue;
                        }
                        job.handle.dirty = dirty;
                        Handle old = handles.put(job.key, job.handle);
                        job.handle = null;
                        if (old != null) {
                            setVisible(old, false);
                            retiring.add(old);
                        }
                    }
                    group.reservation.close();
                    group.reservation = null;
                    group.future.complete(true);
                }
            } catch (RuntimeException | Error error) {
                fail(group, error);
            }
        }
        groups.removeIf(group -> group.future.isDone());
    }

    private void createPage(Handle handle, TerrainMeshConverter.Page page) {
        var key = page.material;
        RemixMaterial material =
                key.fluid()
                        ? textures.getWaterSurfaceMaterial()
                        : key.subsurface()
                                ? textures.getAtlasMaterialSSS(key.layer())
                                : textures.getAtlasMaterial(key.layer());
        if (material == null) throw new IllegalStateException("Shared voxel material unavailable");
        RemixMesh mesh = null;
        RemixInstance instance = null;
        try {
            mesh =
                    new RemixQuadMesh(material, page.vertexCount / 4, page.materialCount,
                            page.vertices, page.attributes);
            page.transferOwnership();
            RemixMeshStore.get().create(mesh);
            Transform transform = Transform.identity();
            transform.setTranslation(
                    VoxelKey.blockX(handle.key),
                    VoxelKey.blockY(handle.key),
                    VoxelKey.blockZ(handle.key));
            boolean cutout =
                    key.layer() == BlockTextureManager.L_CUTOUT
                            || key.layer() == BlockTextureManager.L_CUTOUT_MIPPED;
            int flags = key.overlay() ? InstanceCategoryBit.DECAL_STATIC : 0;
            instance =
                    new RemixInstance(mesh)
                            .setTransform(transform)
                            .setDoubleSided(true)
                            .setVoxelCoverageLevel(2)
                            .setCategoryFlags(flags)
                            .setBlendExt(
                                    key.overlay()
                                            ? InstanceInfoBlendEXT
                                                    .textureTimesVertexColorAlphaBlend(1)
                                            : cutout
                                                    ? InstanceInfoBlendEXT
                                                            .textureTimesVertexColorAlphaTest(128)
                                                    : InstanceInfoBlendEXT
                                                            .textureTimesVertexColor());
            RemixInstanceStore.get().create(instance);
            handle.pages.add(new Page(instance, page.bounds(), page.geometryBytes()));
        } catch (RuntimeException | Error error) {
            if (instance != null) instance.destroy();
            if (mesh != null) {
                mesh.destroy();
                mesh.disposeData();
            }
            throw error;
        }
    }

    private void fail(Group group, Throwable failure) {
        failures++;
        group.future.completeExceptionally(failure);
        cancelGroup(group);
        throw new IllegalStateException("Voxel geometry publication failed", failure);
    }

    private void cancelGroup(Group group) {
        if (group.cancelled) return;
        group.cancelled = true;
        if (group.reservation != null) {
            group.reservation.close();
            group.reservation = null;
        }
        for (Job job : group.jobs) {
            job.mesh.cancel(false);
            job.preparation.cancel(false);
            if (job.stream != null) job.stream.close();
            if (job.batch != null) {
                job.batch.close();
                job.batch = null;
            }
            if (job.handle != null) {
                for (Page page : job.handle.pages) page.instance.hideFromScene(api);
                retiring.add(job.handle);
                job.handle = null;
            }
        }
        group.future.complete(false);
    }

    private void setVisible(Handle handle, boolean visible) {
        if (handle.visible == visible && handle.coverageRevision == coverage.revision()) return;
        for (Page page : handle.pages) {
            int overlap = visible ? coverage.classify(handle.key, page.bounds) : CoveredVolume.NONE;
            boolean draw = visible && (!CULL_COVERED_PAGES || overlap != CoveredVolume.FULL);
            if (draw) {
                boolean clipped = FORCE_COVERAGE_CLIPPING || overlap != CoveredVolume.NONE;
                if (page.visible && page.clipped == clipped) continue;
                // Disjoint opaque pages can stay in hardware traversal without any-hit shaders.
                page.instance.setVoxelCoverageLevel(clipped ? 2 : 0).drawFast(api);
                page.clipped = clipped;
            } else {
                if (!page.visible) continue;
                page.instance.hideFromScene(api);
            }
            page.visible = draw;
            mutations++;
        }
        handle.visible = visible;
        handle.coverageRevision = coverage.revision();
    }

    private void retirePages(boolean drain, long deadline) {
        long retired = 0;
        while (!retiring.isEmpty()
                && (drain || retired < RETIRE_BYTES)
                && (drain || System.nanoTime() < deadline)) {
            Handle handle = retiring.peek();
            if (handle.pages.isEmpty()) {
                retiring.remove();
                if (handle.bytes != 0)
                    throw new IllegalStateException("Voxel retirement accounting");
                continue;
            }
            RemixInstance instance = handle.pages.removeLast().instance;
            long bytes = instance.getMesh().estimatedNativeBytes();
            instance.destroy();
            instance.getMesh().destroy();
            handle.bytes -= bytes;
            budget.retire(bytes);
            retired += bytes;
        }
        if (retired != 0) {
            retiredTotal += retired;
            capacityRevision++;
            memoryBlocked = false;
        }
    }

    @Override
    public void recordNear(long column, Set<NearSection> sections) {
        Set<NearSection> old = near.get(column);
        if (sections.equals(old) || sections.isEmpty() && old == null) return;
        if (sections.isEmpty()) near.remove(column);
        else near.put(column, Set.copyOf(sections));
        coverage.dirty();
    }

    @Override
    public void flushCoverage() {
        if (api == null || session == null) return;
        try (var ignored = ProfileScope.begin("Lod.Coverage")) {
            coverage.publish(api, near);
            for (Handle handle : handles.values()) setVisible(handle, selection.contains(handle.key));
            // Near coverage and far replacements reach the same scene submission.
            tree.releaseRetired();
        }
    }

    @Override
    public boolean isActive() {
        return session != null && HorizonsSettings.INSTANCE.enabled;
    }

    @Override
    public int renderRadiusBlocks() {
        return isActive() ? HorizonsSettings.INSTANCE.distanceChunks * 16 : 0;
    }

    @Override
    public Stats diagnostics() {
        int visible = 0, staged = 0;
        long bytes = 0, workerBytes = 0;
        for (Handle handle : handles.values())
            if (handle.visible) {
                boolean submitted = false;
                for (Page page : handle.pages)
                    if (page.visible) {
                        submitted = true;
                        bytes += page.bytes;
                    }
                if (submitted) visible++;
            } else staged++;
        for (Group group : groups)
            for (Job job : group.jobs)
                if (job.mesh.isDone() && !job.mesh.isCompletedExceptionally())
                    workerBytes += job.mesh.join().bytes();
        return new Stats(
                generation,
                visible,
                staged,
                groups.size(),
                budget == null ? 0 : budget.residentBytes(),
                bytes,
                workerBytes,
                retiring.size(),
                failures,
                memoryBlocked);
    }

    @Override
    public boolean hasGeometryForSection(int x, int y, int z) {
        return hasGeometry(x, y, z, true);
    }

    @Override
    public boolean isViewRefined() {
        return tree != null && session.ready() && tree.viewRefined();
    }

    @Override
    public boolean hasVisibleGeometryForSection(int x, int y, int z) {
        return hasGeometry(x, y, z, false);
    }

    private boolean hasGeometry(int x, int y, int z, boolean refined) {
        for (Handle handle : handles.values())
            if (handle.visible) {
                if (refined
                        && VoxelKey.level(handle.key) > 0
                        && view.area(handle.key)
                                > (double) HorizonsSettings.INSTANCE.nodePixels
                                        * HorizonsSettings.INSTANCE.nodePixels) continue;
                float px = x * 16 - VoxelKey.blockX(handle.key),
                        py = y * 16 - VoxelKey.blockY(handle.key),
                        pz = z * 16 - VoxelKey.blockZ(handle.key);
                for (Page page : handle.pages)
                    if (page.visible && page.bounds.intersects(px, py, pz, 16)) return true;
            }
        return false;
    }

    @Override
    public String sectionDiagnostics(int x, int y, int z) {
        var result = new StringBuilder("refined=").append(hasGeometryForSection(x, y, z));
        for (int level = 0; level <= VoxelKey.ROOT_LEVEL; level++) {
            long key = VoxelKey.containing(level, x * 16, y * 16, z * 16);
            Handle handle = handles.get(key);
            result.append(';')
                    .append(VoxelKey.describe(key))
                    .append("/source=")
                    .append(session == null ? null : session.metadata.get(key))
                    .append("/resident=")
                    .append(handle != null)
                    .append("/visible=")
                    .append(handle != null && handle.visible)
                    .append("/pixels=")
                    .append(Math.sqrt(Math.max(0, view.area(key))));
        }
        return result.toString();
    }

    @Override
    public String handoffDiagnostics() {
        var levels = new TreeMap<Integer, Integer>();
        int submitted = 0, covered = 0, clipped = 0;
        for (Handle h : handles.values())
            if (h.visible) {
                levels.merge(VoxelKey.level(h.key), 1, Integer::sum);
                for (Page page : h.pages) {
                    if (page.visible) {
                        submitted++;
                        if (page.clipped) clipped++;
                    }
                    else covered++;
                }
            }
        return diagnostics()
                + ", levels="
                + levels
                + ", submittedPages=" + submitted + ", coveredPages=" + covered
                + ", clippedPages=" + clipped
                + ", unrefined="
                + (tree == null ? 0 : tree.unrefinedNodes())
                + ", waiting="
                + (tree == null ? "none" : tree.pendingDetail())
                + ", columnsToStore="
                + (session == null ? 0 : session.pendingColumns())
                + ", metadata="
                + (session == null ? 0 : session.metadata.size())
                + ", promised="
                + (budget == null ? 0 : budget.promisedBytes())
                + ", cachedMeshBytes=" + (meshCache == null ? 0 : meshCache.bytes())
                + ", stagingBatches=" + staging.batches()
                + ", pipeline="
                + pipelineDiagnostics()
                + ", palette="
                + (palette == null ? "none" : palette.diagnostics());
    }

    private String pipelineDiagnostics() {
        int meshing = 0, materialsPending = 0, converting = 0, batches = 0, complete = 0;
        var reservations = new StringBuilder();
        for (Group group : groups) {
            if (group.reservation == null && group.estimate != 0)
                reservations
                        .append('[')
                        .append(group.estimate)
                        .append('/')
                        .append(group.replaced)
                        .append(']');
            for (Job job : group.jobs) {
                if (job.done) complete++;
                else if (!job.preparation.isDone()) meshing++;
                else if (job.cursor == null || !job.cursor.isResolved()) materialsPending++;
                else if (job.batch != null) batches++;
                else if (job.stream != null) converting++;
            }
        }
        return "mesh="
                + meshing
                + "/material="
                + materialsPending
                + "/convert="
                + converting
                + "/upload="
                + batches
                + "/done="
                + complete
                + "/groups="
                + completedGroups
                + "/rejected="
                + rejectedGroups
                + "/uploaded="
                + uploadedTotal
                + "/retired="
                + retiredTotal
                + "/estimated="
                + completedEstimate
                + "/actual="
                + completedBytes
                + "/admission="
                + reservations;
    }

    @Override
    public long visibilityMutations() {
        return mutations;
    }

    @Override
    public void suspend() {
        clearGraphics();
    }

    @Override
    public void clear() {
        clearGraphics();
        near.clear();
    }

    private void clearGraphics() {
        if (tree != null) {
            tree.close();
            tree = null;
        }
        for (Group group : groups) cancelGroup(group);
        groups.clear();
        for (Handle handle : handles.values()) {
            setVisible(handle, false);
            retiring.add(handle);
        }
        handles.clear();
        if (budget != null) retirePages(true, Long.MAX_VALUE);
        if (api != null) api.setVoxelCoverage(0, 0, 0, 0, 0, 0, 0, null);
        coverage.clear();
        selected.clear();
        selection.clear();
        changes.clear();
        session = null;
        budget = null;
        if (meshCache != null) meshCache.clear();
        meshCache = null;
        palette = null;
        materials = null;
        memoryBlocked = false;
        api = null;
        textures = null;
    }

    @Override
    public void close() {
        clear();
    }
}
