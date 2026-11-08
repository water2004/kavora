/* SPDX-License-Identifier: LGPL-3.0-only */
package org.edtp.kavora.horizon.voxel;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Changes only complete resident covers. A pending child group never replaces its parent. */
public final class ResidentOctree implements AutoCloseable {
    public interface Backend {
        Collection<Long> roots();

        VoxelSection.Info info(long key);

        boolean childrenReady(long key);

        boolean within(long key);

        double area(long key);

        double splitArea();

        boolean resident(long key);

        boolean dirty(long key);

        /** The current cover replaced atomically by these targets; excludes unrelated nodes. */
        CompletableFuture<Boolean> request(long[] keys, long[] replaced);

        void cancel(long[] keys);

        void retire(long key);

        long capacityRevision();

        default boolean memoryPressure() {
            return false;
        }
    }

    // Metadata, meshing and upload overlap. The backend bounds active snapshots and upload bytes
    // separately; a transaction waiting on one stage must not idle the other stages.
    private static final int LOAD = 0, MERGE = 1, REFRESH = 2, SPLIT = 3, MAX_TRANSITIONS = 16;

    private static final class Node {
        final long key;
        final Node parent;
        final ArrayList<Node> children = new ArrayList<>(8);
        boolean expanded, active;
        int kind;
        long blockedAt = -1, blockedSource = -1;
        int blockedKind = -1;

        Node(long key, Node parent) {
            this.key = key;
            this.parent = parent;
        }
    }

    private record Transition(
            Node owner, int kind, long[] targets, CompletableFuture<Boolean> future) {}

    private record Candidate(Node node, int kind, double priority) {}

    private final Backend backend;
    private int unrefined, waitingDetail, waitingRefresh;

    public int unrefinedNodes() {
        return unrefined;
    }

    public String pendingDetail() {
        return "detail=" + waitingDetail + ", refresh=" + waitingRefresh;
    }

    public boolean viewRefined() {
        return unrefined == 0;
    }

    private final Long2ObjectOpenHashMap<Node> roots = new Long2ObjectOpenHashMap<>();
    private final ArrayList<Transition> active = new ArrayList<>(MAX_TRANSITIONS);
    private final PriorityQueue<Candidate> candidates =
            new PriorityQueue<>(Comparator.comparingDouble(Candidate::priority).reversed());
    private final LongArrayList retired = new LongArrayList();

    public ResidentOctree(Backend backend) {
        this.backend = backend;
    }

    public int transitions() {
        return active.size();
    }

    public void tick(LongList selected) {
        selected.clear();
        candidates.clear();
        unrefined = 0;
        waitingDetail = 0;
        waitingRefresh = 0;
        for (var it = roots.values().iterator(); it.hasNext(); ) {
            Node node = it.next();
            if (!backend.within(node.key)) {
                cancelSubtree(node);
                retire(node);
                it.remove();
            }
        }
        for (long key : backend.roots())
            if (backend.within(key)) roots.computeIfAbsent(key, pos -> new Node(pos, null));
        for (var it = active.listIterator(); it.hasNext(); ) {
            Transition tx = it.next();
            if (!tx.future().isDone()) continue;
            boolean success = tx.future().join();
            Node owner = tx.owner();
            if (success && tx.kind() == SPLIT) {
                ensureChildren(owner);
                long[] missing =
                        owner.children.stream()
                                .filter(n -> !backend.resident(n.key))
                                .mapToLong(n -> n.key)
                                .toArray();
                if (missing.length != 0) {
                    it.set(
                            new Transition(
                                    owner,
                                    SPLIT,
                                    missing,
                                    backend.request(missing, new long[] {owner.key})));
                    continue;
                }
            }
            owner.active = false;
            it.remove();
            if (!success) {
                owner.blockedAt = backend.capacityRevision();
                owner.blockedKind = tx.kind();
                var source = backend.info(owner.key);
                owner.blockedSource = source == null ? -1 : source.revision();
                if (tx.kind() == SPLIT) retireChildren(owner);
                continue;
            }
            owner.blockedAt = -1;
            if (tx.kind() == SPLIT) {
                owner.expanded = true;
                retired.add(owner.key);
            }
            if (tx.kind() == MERGE) {
                owner.expanded = false;
                retireChildren(owner);
            }
        }
        for (Node root : roots.values()) visit(root, selected, false);
        while (active.size() < MAX_TRANSITIONS && !candidates.isEmpty()) {
            Candidate candidate = candidates.poll();
            Node node = candidate.node();
            if (node.active || hasActiveAncestor(node) || hasActiveDescendant(node)) continue;
            if (candidate.kind() == SPLIT) ensureChildren(node);
            long[] keys =
                    candidate.kind() == SPLIT
                            ? node.children.stream().mapToLong(n -> n.key).toArray()
                            : new long[] {node.key};
            if (keys.length == 0) continue;
            node.active = true;
            node.kind = candidate.kind();
            long[] replaced =
                    switch (candidate.kind()) {
                        case LOAD -> new long[0];
                        case MERGE -> node.children.stream().mapToLong(n -> n.key).toArray();
                        default -> new long[] {node.key};
                    };
            active.add(
                    new Transition(node, candidate.kind(), keys, backend.request(keys, replaced)));
        }
    }

    private boolean hasActiveDescendant(Node node) {
        for (Node child : node.children)
            if (child.active || hasActiveDescendant(child)) return true;
        return false;
    }

    private boolean childrenAreLeaves(Node node) {
        for (Node child : node.children) if (child.expanded) return false;
        return true;
    }

    private boolean hasActiveAncestor(Node node) {
        for (Node parent = node.parent; parent != null; parent = parent.parent)
            if (parent.active) return true;
        return false;
    }

    private void ensureChildren(Node node) {
        VoxelSection.Info info = backend.info(node.key);
        if (info == null) return;
        for (int i = 0; i < 8; i++)
            if ((info.occupiedChildren() & (1 << i)) != 0) {
                long key = VoxelKey.child(node.key, i);
                boolean found = false;
                for (Node child : node.children)
                    if (child.key == key) {
                        found = true;
                        break;
                    }
                if (!found && backend.info(key) != null) node.children.add(new Node(key, node));
            }
    }

    private void visit(Node node, LongList selected, boolean ancestorActive) {
        var info = backend.info(node.key);
        if (info == null) return;
        double area = backend.area(node.key), threshold = backend.splitArea();
        boolean blocked =
                node.blockedAt == backend.capacityRevision()
                        && node.blockedSource == info.revision();
        if (node.expanded) {
            ensureChildren(node);
            if (!ancestorActive
                    && !node.active
                    && (!blocked || node.blockedKind != MERGE)
                    && childrenAreLeaves(node)
                    && !hasActiveDescendant(node)
                    && area < threshold * (backend.memoryPressure() ? 1 : .5)
                    && (area >= 0 || backend.memoryPressure()))
                candidates.add(new Candidate(node, MERGE, Double.MAX_VALUE / 2));
            for (Node child : node.children) visit(child, selected, ancestorActive || node.active);
            return;
        }
        boolean resident = backend.resident(node.key);
        boolean split =
                !info.empty()
                        && VoxelKey.level(node.key) > 0
                        && area > threshold
                        && info.knownChildren() != 0;
        // Metadata discovery does not wait for parent geometry or a GPU publication slot.
        boolean childrenReady = split && backend.childrenReady(node.key);
        if (area >= 0 && !info.empty()) {
            if (!resident || split) {
                unrefined++;
                waitingDetail++;
            } else if (backend.dirty(node.key)) {
                unrefined++;
                waitingRefresh++;
            }
        }
        if (resident) selected.add(node.key);
        if (ancestorActive || node.active) return;
        if (!resident && (!blocked || node.blockedKind != LOAD))
            candidates.add(new Candidate(node, LOAD, Math.max(0, area)));
        else if (split
                && (!blocked || node.blockedKind != SPLIT)
                && childrenReady)
            candidates.add(new Candidate(node, SPLIT, area));
        else if (backend.dirty(node.key) && (!blocked || node.blockedKind != REFRESH))
            candidates.add(new Candidate(node, REFRESH, Math.max(0, area)));
    }

    /** Release after the caller has published this tick's new visibility set. */
    public void releaseRetired() {
        for (long key : retired) backend.retire(key);
        retired.clear();
    }

    private void cancelSubtree(Node node) {
        for (var it = active.iterator(); it.hasNext(); ) {
            var tx = it.next();
            if (VoxelKey.contains(node.key, tx.owner().key)) {
                backend.cancel(tx.targets());
                it.remove();
            }
        }
    }

    private void retireChildren(Node node) {
        for (Node child : node.children) retire(child);
        node.children.clear();
    }

    private void retire(Node node) {
        retireChildren(node);
        retired.add(node.key);
    }

    @Override
    public void close() {
        for (var tx : active) backend.cancel(tx.targets());
        active.clear();
        for (Node node : roots.values()) retire(node);
        roots.clear();
        releaseRetired();
    }
}
