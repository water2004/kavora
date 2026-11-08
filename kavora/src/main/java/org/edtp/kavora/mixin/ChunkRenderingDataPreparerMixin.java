/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import org.edtp.kavora.terrain.visibility.VisibilityGraphBuffers;
import org.edtp.kavora.terrain.visibility.TimeSlicedWorkQueue;
import org.edtp.kavora.terrain.visibility.RenderableChunksBuffers;
import org.edtp.kavora.terrain.visibility.HierarchicalFrustumCuller;
import org.edtp.kavora.terrain.visibility.FarChunkProbe;
import org.edtp.kavora.terrain.visibility.ChunkSlotMoveLog;
import org.edtp.kavora.terrain.visibility.ChunkInfoPool;
import org.edtp.kavora.terrain.visibility.BuiltChunkFrustumIndexBuild;
import org.edtp.kavora.terrain.visibility.BuiltChunkFrustumIndex;
import org.edtp.kavora.profiling.ProfileScope;
import org.edtp.kavora.KavoraMod;
import com.google.common.collect.Queues;
import org.joml.FrustumIntersection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.SectionPos;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.client.renderer.Octree;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.SectionOcclusionGraph.GraphStorage;
import net.minecraft.client.renderer.SectionOcclusionGraph.Node;
import net.minecraft.client.renderer.SectionOcclusionGraph.SectionToNodeMap;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/**
 * Takes over vanilla's chunk visibility preparation on three fronts: it runs
 * the full visibility walk on a dedicated low-priority executor so long view
 * distances cannot starve terrain capture or the render thread, it builds and
 * publishes the coarse {@code BuiltChunkFrustumIndex} that bulk-accepts or
 * rejects section groups before any exact per-chunk frustum test, and it
 * time-slices vanilla's incremental propagation on the render thread through a
 * per-frame budget so a chunk-load burst cannot stall a frame.
 *
 * <p>It also labels each phase for the profiler.
 */
@Mixin(SectionOcclusionGraph.class)
public abstract class ChunkRenderingDataPreparerMixin {
    @Shadow
    @Final
    private AtomicReference<?> currentGraph;

    /** Vanilla's neighbour order; the walk must visit directions in this order. */
    @Shadow
    @Final
    private static Direction[] DIRECTIONS;

    /** Ray-march step of vanilla's far-chunk probe, {@code ceil(sqrt(3) * 16)} blocks. */
    @Shadow
    @Final
    private static double CEILED_SECTION_DIAGONAL;

    @Shadow
    private ViewArea viewArea;

    /** Vanilla's neighbour lookup: null beyond the view distance or the world's height. */
    @Shadow
    protected abstract SectionRenderDispatcher.RenderSection getRelativeFrom(
            long cameraSectionOrigin, SectionRenderDispatcher.RenderSection chunk, Direction direction);

    /** Vanilla's horizontal view-distance test between two section origins. */
    @Shadow
    protected abstract boolean isInViewDistance(long cameraSectionOrigin, long origin);

    /**
     * {@code -Dkavora.vanillaVisibilityWalk=true} leaves the visibility walk to vanilla with
     * RTX on. The port below must produce vanilla's visible set exactly; this is how to check
     * that on a live world, together with {@code -Dkavora.logVisibleSet=true}, which logs
     * the size and a position hash of every full walk's result whenever it changes. Run the
     * same camera with and without the switch and compare the last line each logged.
     */
    private static final boolean kavora$VANILLA_WALK = Boolean.getBoolean("kavora.vanillaVisibilityWalk");
    private static final boolean kavora$LOG_VISIBLE_SET = Boolean.getBoolean("kavora.logVisibleSet");
    private static long kavora$lastVisibleSetSignature;
    private static final ExecutorService kavora$visibilityExecutor =
            Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "Kavora chunk visibility");
                thread.setDaemon(true);
                thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
                return thread;
            });

    private static final ThreadLocal<BuiltChunkFrustumIndexBuild> kavora$frustumIndexBuild =
            new ThreadLocal<>();

    private volatile BuiltChunkFrustumIndex kavora$frustumIndex;

    /**
     * Cullers taken back from retired indices, for the next build to refill. Two suffice: one
     * behind the published index, one being filled. Offered by whichever side wins the retire
     * (see {@link BuiltChunkFrustumIndex#tryRetire}), polled by the visibility thread.
     */
    private ConcurrentLinkedQueue<HierarchicalFrustumCuller<SectionRenderDispatcher.RenderSection>> kavora$cullerPool;

    /**
     * Per-frame budget for vanilla's incremental visibility propagation. After a chunk-load
     * burst the propagation can seed a walk of thousands of sections; unbudgeted, one such
     * walk cost 25 ms on the render thread. The poll floor keeps the fringe advancing even
     * when the frame is already late.
     */
    private static final long kavora$PROPAGATION_BUDGET_NANOS = 1_500_000L;
    private static final int kavora$PROPAGATION_MINIMUM_POLLS = 64;

    /**
     * Seeds and partially walked sections carried across frames. Its entries belong to the
     * visibility state that produced them, so a publish or a storage swap empties it.
     */
    private TimeSlicedWorkQueue<Object> kavora$propagationQueue;
    /** Seed queue of the state whose entries {@link #kavora$propagationQueue} holds. */
    private BlockingQueue<?> kavora$propagationSeeds;

    private static final ThreadLocal<ArrayDeque<ProfileScope>> kavora$storageScope = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<ProfileScope>> kavora$frustumScope = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<ProfileScope>> kavora$prepareScope = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<ProfileScope>> kavora$graphScope = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<ProfileScope>> kavora$seedScope = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<ProfileScope>> kavora$walkScope = new ThreadLocal<>();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void kavora$initializeQueues(CallbackInfo ci) {
        kavora$cullerPool = new ConcurrentLinkedQueue<>();
        kavora$propagationQueue = new TimeSlicedWorkQueue<>(
                kavora$PROPAGATION_BUDGET_NANOS, kavora$PROPAGATION_MINIMUM_POLLS, System::nanoTime);
    }

    @Inject(method = "waitAndReset", at = @At("HEAD"))
    private void kavora$storageHead(ViewArea storage, CallbackInfo ci) {
        kavora$open(kavora$storageScope, "ChunkVisibility.setStorage");
        // The initial empty state vanilla builds below takes the current buffers; size them
        // for the new storage first.
        VisibilityGraphBuffers.configure(storage != null ? storage.size() : 0);
    }

    @Inject(method = "waitAndReset", at = @At("RETURN"))
    private void kavora$storageReturn(ViewArea storage, CallbackInfo ci) {
        BuiltChunkFrustumIndex retired = kavora$frustumIndex;
        kavora$frustumIndex = null;
        if (retired != null) {
            retired.tryRetire();
        }
        // Capacities were sized for the old storage; let the next builds grow fresh.
        kavora$cullerPool.clear();
        kavora$frustumIndexBuild.remove();
        kavora$propagationQueue.clear();
        kavora$propagationSeeds = null;
        kavora$close(kavora$storageScope);
    }

    @Inject(method = "addSectionsInFrustum", at = @At("HEAD"), cancellable = true)
    private void kavora$frustumHead(Frustum frustum, List<SectionRenderDispatcher.RenderSection> builtChunks, List<SectionRenderDispatcher.RenderSection> nearbyChunks, CallbackInfo ci) {
        kavora$open(kavora$frustumScope, "ChunkVisibility.applyFrustum", kavora$sizeOf(builtChunks));
        if (KavoraMod.isRtxEnabled() && kavora$applyFrustumIndex(frustum, builtChunks, nearbyChunks)) {
            kavora$captureCount(kavora$frustumScope, kavora$sizeOf(builtChunks));
            kavora$close(kavora$frustumScope);
            ci.cancel();
        }
    }

    @Inject(method = "addSectionsInFrustum", at = @At("RETURN"))
    private void kavora$frustumReturn(Frustum frustum, List<SectionRenderDispatcher.RenderSection> builtChunks, List<SectionRenderDispatcher.RenderSection> nearbyChunks, CallbackInfo ci) {
        kavora$captureCount(kavora$frustumScope, kavora$sizeOf(builtChunks));
        kavora$close(kavora$frustumScope);
    }

    @Inject(method = "update", at = @At("HEAD"))
    private void kavora$prepareHead(CallbackInfo ci) {
        kavora$open(kavora$prepareScope, "ChunkVisibility.prepare", -1);
    }

    @Inject(method = "update", at = @At("RETURN"))
    private void kavora$prepareReturn(CallbackInfo ci) {
        kavora$close(kavora$prepareScope);
    }

    @Redirect(method = "scheduleFullUpdate", at = @At(value = "INVOKE",
            target = "Ljava/util/concurrent/CompletableFuture;runAsync(Ljava/lang/Runnable;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
    private java.util.concurrent.CompletableFuture<Void> kavora$visibilityExecutor(
            Runnable work, java.util.concurrent.Executor vanillaExecutor) {
        return java.util.concurrent.CompletableFuture.runAsync(work,
                KavoraMod.isRtxEnabled() ? kavora$visibilityExecutor : vanillaExecutor);
    }

    /**
     * A full walk is about to construct its state: hand it the other buffer set. Runs for
     * vanilla's walk too, since the reused set, list and map are what its state is made of.
     */
    @Inject(method = "lambda$scheduleFullUpdate$0", at = @At("HEAD"))
    private void kavora$beginFrustumIndex(CameraRenderState camera,
            LongOpenHashSet emptySections, LongOpenHashSet loadedChunks, CallbackInfo ci) {
        VisibilityGraphBuffers.rotate();
        if (KavoraMod.isRtxEnabled()) {
            BuiltChunkFrustumIndexBuild build = new BuiltChunkFrustumIndexBuild(
                    ChunkSlotMoveLog.RENDER_THREAD.sequence(), kavora$cullerPool.poll());
            build.setCameraCenter(viewArea.getCameraSectionPos().center());
            kavora$frustumIndexBuild.set(build);
        } else {
            kavora$frustumIndexBuild.remove();
            kavora$frustumIndex = null;
        }
    }

    // Publish the index and its graph together before the render thread reads either.
    @Redirect(
            method = "lambda$scheduleFullUpdate$0",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/concurrent/atomic/AtomicReference;set(Ljava/lang/Object;)V",
                    ordinal = 0
            ))
    private void kavora$publishFrustumIndex(
            AtomicReference<Object> stateReference,
            Object state) {
        BuiltChunkFrustumIndexBuild build = kavora$frustumIndexBuild.get();
        kavora$frustumIndexBuild.remove();
        // Published even when slots were re-originned during the walk: the walk takes tens of
        // milliseconds, and discarding a build that saw a move would leave every chunk on
        // vanilla's exact per-chunk test for several frames.
        //
        // Reconciled from the move sequence the build STARTED at, never the current one:
        // anything that moved mid-walk is indexed against bounds that are already stale, so the
        // first apply must replay those moves. Starting from the current sequence would skip
        // them and silently drop the moved geometry.
        BuiltChunkFrustumIndex superseded = kavora$frustumIndex;
        kavora$frustumIndex = build != null ? build.publish(state) : null;
        stateReference.set(state);
        kavora$recycle(superseded);
    }

    /**
     * Returns a superseded index's culler to the pool unless the render thread is inside a use
     * of it, in which case that use retires it when it ends.
     */
    private void kavora$recycle(BuiltChunkFrustumIndex superseded) {
        if (superseded == null) {
            return;
        }
        HierarchicalFrustumCuller<SectionRenderDispatcher.RenderSection> culler = superseded.tryRetire();
        if (culler != null && kavora$cullerPool.size() < 2) {
            kavora$cullerPool.offer(culler);
        }
    }

    /** Ends a render-thread use; recycles the index if a publish superseded it meanwhile. */
    private void kavora$releaseIndex(BuiltChunkFrustumIndex index) {
        index.release();
        if (kavora$frustumIndex != index) {
            kavora$recycle(index);
        }
    }

    private static final ThreadLocal<GraphStorage> kavora$walkingStorage = new ThreadLocal<>();

    @Redirect(method = "runUpdates", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/Octree;add(Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;)Z"))
    private boolean kavora$indexVisibleChunk(Octree tree, SectionRenderDispatcher.RenderSection section) {
        GraphStorage storage = kavora$walkingStorage.get();
        return kavora$visit(storage, storage.sectionToNodeMap.get(section));
    }

    private boolean kavora$visit(GraphStorage storage, Node info) {
        if (!storage.sectionTree.add(info.section)) return false;
        var chunks = ((RenderableChunksBuffers) storage).kavora$reachedChunks();
        chunks.add(info);
        BuiltChunkFrustumIndexBuild build = kavora$frustumIndexBuild.get();
        if (build != null) {
            build.add(chunks, info.section);
        } else {
            BuiltChunkFrustumIndex index = kavora$frustumIndex;
            if (index != null && index.chunks() == chunks && index.tryAcquire()) {
                try { index.add(info.section); } finally { kavora$releaseIndex(index); }
            }
        }
        return true;
    }

    @Inject(method = "runPartialUpdate", at = @At("HEAD"))
    private void kavora$graphHead(CallbackInfo ci) {
        kavora$open(kavora$graphScope, "ChunkVisibility.rebuildGraph", -1);
    }

    @Inject(method = "runPartialUpdate", at = @At("RETURN"))
    private void kavora$graphReturn(CallbackInfo ci) {
        kavora$budgetHit(kavora$graphScope, kavora$propagationQueue.budgetHit());
        kavora$close(kavora$graphScope);
    }

    // Vanilla only propagates when new seeds arrived. Sections left over from a budgeted
    // slice must keep draining even on frames with no new seeds.
    @Redirect(
            method = "runPartialUpdate",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/concurrent/BlockingQueue;isEmpty()Z",
                    ordinal = 0
            ))
    private boolean kavora$noPropagationWork(BlockingQueue<?> seeds) {
        if (!KavoraMod.isRtxEnabled()) {
            return seeds.isEmpty();
        }
        kavora$dropForeignPropagationWork(seeds);
        return seeds.isEmpty() && !kavora$propagationQueue.hasPendingWork();
    }

    // Vanilla drains the seeds into a fresh deque and walks it to completion. Handing it the
    // persistent sliced queue instead bounds the walk to this frame's budget and resumes it
    // from the same entries next frame.
    @Redirect(
            method = "runPartialUpdate",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/google/common/collect/Queues;newArrayDeque()Ljava/util/ArrayDeque;"
            ))
    private ArrayDeque<Object> kavora$propagationWorkQueue() {
        if (!KavoraMod.isRtxEnabled()) {
            kavora$propagationQueue.clear();
            return Queues.newArrayDeque();
        }
        kavora$propagationQueue.beginSlice();
        return kavora$propagationQueue;
    }

    /**
     * Leftover entries index into the state that produced them; a publish invalidates them.
     * Each state owns its seed queue, so the queue vanilla is about to drain identifies the
     * state it loaded for this frame. Re-reading the published state here instead could
     * observe a newer one and leave this frame's leftovers tagged as that state's own.
     */
    private void kavora$dropForeignPropagationWork(BlockingQueue<?> seeds) {
        if (kavora$propagationSeeds != seeds) {
            kavora$propagationQueue.clear();
            kavora$propagationSeeds = seeds;
        }
    }

    @Inject(method = "initializeQueueForFullUpdate", at = @At("HEAD"))
    private void kavora$seedHead(
            BlockPos cameraPosition,
            Queue<?> queue,
            CallbackInfo ci) {
        kavora$open(kavora$seedScope, "ChunkVisibility.seedQueue", kavora$sizeOf(queue));
    }

    @Inject(method = "initializeQueueForFullUpdate", at = @At("RETURN"))
    private void kavora$seedReturn(
            BlockPos cameraPosition,
            Queue<?> queue,
            CallbackInfo ci) {
        kavora$close(kavora$seedScope);
    }

    /**
     * Vanilla's walk allocates on every step of its far-chunk probe: for each neighbour more
     * than 60 blocks from the camera it ray-marches from the neighbour's near corner back
     * toward the camera in {@link #CEILED_SECTION_DIAGONAL}-block steps, creating a Vec3d for the remaining
     * offset, a Vector3d for the advanced point and a BlockPos for the lookup each time. At render
     * distance 32 that is most of the garbage the collector cleans up while the game runs, and
     * the collector's concurrent work is what stretches frames. This port keeps vanilla's
     * traversal, arithmetic and evaluation order and holds the ray in doubles and one mutable
     * position, so the visible set is identical and the walk allocates only graph nodes.
     */
    @Inject(method = "runUpdates", at = @At("HEAD"), cancellable = true)
    private void kavora$walkVisibleGraph(
            GraphStorage renderableChunks,
            Vec3 cameraPos,
            Queue<Node> queue,
            boolean culling,
            Consumer<SectionRenderDispatcher.RenderSection> chunkConsumer,
            LongOpenHashSet emptySections, LongOpenHashSet loadedChunks,
            CallbackInfo ci) {
        kavora$walkingStorage.set(renderableChunks);
        kavora$open(kavora$walkScope, "ChunkVisibility.walkVisibleGraph", kavora$sizeOf(queue));
        if (!KavoraMod.isRtxEnabled() || kavora$VANILLA_WALK) {
            return;
        }
        try {
            kavora$walk(renderableChunks, cameraPos, queue, culling, chunkConsumer, emptySections, loadedChunks);
        } finally {
            kavora$close(kavora$walkScope);
        }
        if (kavora$LOG_VISIBLE_SET) kavora$logVisibleSet("port", renderableChunks);
        kavora$walkingStorage.remove();
        ci.cancel();
    }

    @Inject(method = "runUpdates", at = @At("RETURN"))
    private void kavora$walkReturn(
            GraphStorage renderableChunks,
            Vec3 cameraPos,
            Queue<Node> queue,
            boolean culling,
            Consumer<SectionRenderDispatcher.RenderSection> chunkConsumer,
            LongOpenHashSet emptySections, LongOpenHashSet loadedChunks,
            CallbackInfo ci) {
        kavora$close(kavora$walkScope);
        if (kavora$LOG_VISIBLE_SET) kavora$logVisibleSet("vanilla", renderableChunks);
        kavora$walkingStorage.remove();
    }

    /** Order-independent signature of the visible set: size and a hash of every section origin. */
    private static void kavora$logVisibleSet(String walk, GraphStorage renderableChunks) {
        long hash = 0;
        for (Node info : ((RenderableChunksBuffers) renderableChunks).kavora$reachedChunks()) {
            hash += (info.section.getSectionNode() * 0x9E3779B97F4A7C15L) ^ 0x632BE59BD9B4E019L;
        }
        long signature = hash ^ ((long) ((RenderableChunksBuffers) renderableChunks).kavora$reachedChunks().size() << 48);
        if (signature != kavora$lastVisibleSetSignature) {
            kavora$lastVisibleSetSignature = signature;
            KavoraMod.LOGGER.info("Visible set ({} walk): {} sections, hash {}",
                    walk, ((RenderableChunksBuffers) renderableChunks).kavora$reachedChunks().size(), Long.toHexString(hash));
        }
    }

    private void kavora$walk(GraphStorage storage, Vec3 cameraPos, Queue<Node> queue,
            boolean culling, Consumer<SectionRenderDispatcher.RenderSection> consumer,
            LongOpenHashSet emptySections, LongOpenHashSet loadedChunks) {
        SectionToNodeMap infos = storage.sectionToNodeMap;
        ChunkInfoPool pool = ((RenderableChunksBuffers) storage).kavora$infoPool();
        int cameraX = SectionPos.blockToSectionCoord(cameraPos.x);
        int cameraY = SectionPos.blockToSectionCoord(cameraPos.y);
        int cameraZ = SectionPos.blockToSectionCoord(cameraPos.z);
        long cameraSection = SectionPos.asLong(cameraX, cameraY, cameraZ);
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        FarChunkProbe.GraphLookup graph = (x, y, z) -> {
            SectionRenderDispatcher.RenderSection passed = viewArea.getRenderSectionAt(probe.set(x, y, z));
            return passed != null && infos.get(passed) != null;
        };
        while (!queue.isEmpty()) {
            Node node = queue.poll();
            SectionRenderDispatcher.RenderSection section = node.section;
            long sectionNode = section.getSectionNode();
            long chunkNode = ChunkPos.fromSectionNode(sectionNode);
            if (!loadedChunks.contains(chunkNode)) {
                LongList waiting = storage.sectionsWaitingForChunkLoads.get(chunkNode);
                if (waiting == null) {
                    waiting = new LongArrayList();
                    storage.sectionsWaitingForChunkLoads.put(chunkNode, waiting);
                }
                waiting.add(sectionNode);
                continue;
            }
            if (!emptySections.contains(sectionNode)) {
                if (kavora$visit(storage, node)) consumer.accept(section);
            } else if (section.sectionMesh.compareAndSet(CompiledSectionMesh.UNCOMPILED, CompiledSectionMesh.EMPTY)) {
                section.updateUploadTime();
            }
            boolean far = Math.abs(SectionPos.x(sectionNode) - cameraX) > 3
                    || Math.abs(SectionPos.y(sectionNode) - cameraY) > 3
                    || Math.abs(SectionPos.z(sectionNode) - cameraZ) > 3;
            for (Direction direction : DIRECTIONS) {
                SectionRenderDispatcher.RenderSection neighbour = getRelativeFrom(cameraSection, section, direction);
                if (neighbour == null || (culling && node.hasDirection(direction.getOpposite()))) continue;
                if (culling && node.hasSourceDirections()) {
                    SectionMesh mesh = section.getSectionMesh();
                    boolean visible = false;
                    for (int i = 0; i < DIRECTIONS.length; i++) {
                        if (node.hasSourceDirection(i) && mesh.facesCanSeeEachother(DIRECTIONS[i].getOpposite(), direction)) {
                            visible = true;
                            break;
                        }
                    }
                    if (!visible) continue;
                }
                if (culling && far && !FarChunkProbe.reachesCamera(
                        SectionPos.sectionToBlockCoord(SectionPos.x(sectionNode)),
                        SectionPos.sectionToBlockCoord(SectionPos.y(sectionNode)),
                        SectionPos.sectionToBlockCoord(SectionPos.z(sectionNode)), direction.getAxis(),
                        cameraX * 16 + 8, cameraY * 16 + 8, cameraZ * 16 + 8,
                        cameraPos.x, cameraPos.y, cameraPos.z, CEILED_SECTION_DIAGONAL,
                        viewArea.maxY(), viewArea.minY(), graph)) continue;
                Node existing = infos.get(neighbour);
                if (existing != null) {
                    existing.addSourceDirection(direction);
                } else {
                    Node discovered = pool.acquire(neighbour, direction, node.step + 1);
                    discovered.setDirections(node.directions, direction);
                    queue.add(discovered);
                    infos.put(neighbour, discovered);
                }
            }
        }
    }

    private static int kavora$sizeOf(List<?> values) {
        return values == null ? -1 : values.size();
    }

    private static int kavora$sizeOf(Queue<?> values) {
        return values == null ? -1 : values.size();
    }

    private static void kavora$open(ThreadLocal<ArrayDeque<ProfileScope>> holder, String phaseName) {
        kavora$open(holder, phaseName, -1);
    }

    private static void kavora$open(ThreadLocal<ArrayDeque<ProfileScope>> holder, String phaseName, int captureCount) {
        if (!ProfileScope.isDeepProfilingEnabled()) {
            return;
        }
        ArrayDeque<ProfileScope> stack = holder.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            holder.set(stack);
        }
        ProfileScope scope = ProfileScope.begin(phaseName);
        if (captureCount >= 0) {
            scope.captureCount(captureCount);
        }
        stack.push(scope);
    }

    private static void kavora$captureCount(ThreadLocal<ArrayDeque<ProfileScope>> holder, int captureCount) {
        if (!ProfileScope.isDeepProfilingEnabled() || captureCount < 0) {
            return;
        }
        ArrayDeque<ProfileScope> stack = holder.get();
        if (stack != null && !stack.isEmpty()) {
            stack.peek().captureCount(captureCount);
        }
    }

    private static void kavora$scannedChunkCount(ThreadLocal<ArrayDeque<ProfileScope>> holder, int scanned) {
        if (!ProfileScope.isDeepProfilingEnabled() || scanned < 0) {
            return;
        }
        ArrayDeque<ProfileScope> stack = holder.get();
        if (stack != null && !stack.isEmpty()) {
            stack.peek().scannedChunkCount(scanned);
        }
    }

    private static void kavora$budgetHit(ThreadLocal<ArrayDeque<ProfileScope>> holder, boolean budgetHit) {
        if (!ProfileScope.isDeepProfilingEnabled() || !budgetHit) {
            return;
        }
        ArrayDeque<ProfileScope> stack = holder.get();
        if (stack != null && !stack.isEmpty()) {
            stack.peek().budgetHit(true);
        }
    }

    private static void kavora$close(ThreadLocal<ArrayDeque<ProfileScope>> holder) {
        if (!ProfileScope.isDeepProfilingEnabled()) {
            return;
        }
        ArrayDeque<ProfileScope> stack = holder.get();
        if (stack != null && !stack.isEmpty()) {
            ProfileScope scope = stack.pop();
            if (stack.isEmpty()) {
                holder.remove();
            }
            scope.close();
        } else if (stack != null) {
            holder.remove();
        }
    }

    private boolean kavora$applyFrustumIndex(
            Frustum frustum,
            List<SectionRenderDispatcher.RenderSection> builtChunks,
            List<SectionRenderDispatcher.RenderSection> nearbyChunks) {
        BuiltChunkFrustumIndex index = kavora$frustumIndex;
        if (kavora$VANILLA_WALK || index == null || index.state() != currentGraph.get() || !index.tryAcquire()) {
            return false;
        }
        try {
            // Slots re-originned since this index was last reconciled may have left their
            // cached cells; the index re-buckets exactly those sections before it is queried.
            index.reconcile(ChunkSlotMoveLog.RENDER_THREAD);
            kavora$scannedChunkCount(kavora$frustumScope, index.size());

            Frustum offset = new Frustum(frustum).offsetToFullyIncludeCameraCube(8);
            FrustumAccessor accessor = (FrustumAccessor) offset;
            FrustumIntersection intersection = accessor.kavora$getFrustumIntersection();
            double frustumX = accessor.kavora$getX();
            double frustumY = accessor.kavora$getY();
            double frustumZ = accessor.kavora$getZ();
            index.forEachVisible(
                    (minX, minY, minZ, maxX, maxY, maxZ) -> {
                        int classification = intersection.intersectAab(
                                (float) (minX - frustumX),
                                (float) (minY - frustumY),
                                (float) (minZ - frustumZ),
                                (float) (maxX - frustumX),
                                (float) (maxY - frustumY),
                                (float) (maxZ - frustumZ));
                        if (classification == FrustumIntersection.INSIDE) {
                            return HierarchicalFrustumCuller.INSIDE;
                        }
                        if (classification == FrustumIntersection.INTERSECT) {
                            return HierarchicalFrustumCuller.INTERSECT;
                        }
                        return HierarchicalFrustumCuller.OUTSIDE;
                    },
                    chunk -> offset.isVisible(chunk.getBoundingBox()),
                    chunk -> {
                        builtChunks.add(chunk);
                        if (index.isNearby(chunk)) nearbyChunks.add(chunk);
                    });
            return true;
        } finally {
            kavora$releaseIndex(index);
        }
    }
}
