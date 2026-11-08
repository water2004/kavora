/* SPDX-License-Identifier: LGPL-3.0-only */
package com.gbwater.rtxremix.horizons.voxel;

import static org.junit.jupiter.api.Assertions.*;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CompletableFuture;

class ResidentOctreeTest {
    static final class World implements ResidentOctree.Backend {
        final long root;
        final Set<Long> resident = new HashSet<>(), dirty = new HashSet<>();
        final Map<Long, VoxelSection.Info> info = new HashMap<>();
        final ArrayList<long[]> requests = new ArrayList<>();
        final ArrayList<long[]> replaced = new ArrayList<>();
        final ArrayList<CompletableFuture<Boolean>> completions = new ArrayList<>();
        double area = 10000;
        long capacity;
        boolean pressure;

        public boolean memoryPressure() {
            return pressure;
        }

        World() {
            this(1);
        }

        World(int level) {
            root = VoxelKey.of(level, 0, 0, 0);
            info.put(root, new VoxelSection.Info(root, 1, 255, 255));
            for (int i = 0; i < 8; i++) {
                long key = VoxelKey.child(root, i);
                info.put(key, new VoxelSection.Info(key, 1, 255, 255));
            }
        }

        public Collection<Long> roots() {
            return List.of(root);
        }

        public VoxelSection.Info info(long key) {
            return info.get(key);
        }

        public boolean childrenReady(long key) {
            return true;
        }

        public boolean within(long key) {
            return true;
        }

        public double area(long key) {
            return area;
        }

        public double splitArea() {
            return 4096;
        }

        public boolean resident(long key) {
            return resident.contains(key);
        }

        public boolean dirty(long key) {
            return dirty.contains(key);
        }

        public CompletableFuture<Boolean> request(long[] keys, long[] replacedKeys) {
            var result = new CompletableFuture<Boolean>();
            requests.add(keys);
            replaced.add(replacedKeys);
            completions.add(result);
            return result;
        }

        public void cancel(long[] keys) {}

        public void retire(long key) {
            resident.remove(key);
        }

        public long capacityRevision() {
            return capacity;
        }

        void complete(int index, boolean ok) {
            if (ok)
                for (long key : requests.get(index)) {
                    resident.add(key);
                    dirty.remove(key);
                }
            completions.get(index).complete(ok);
        }
    }

    @Test
    void observedAirChildrenDoNotConsumeGeometryRequests() {
        World world = new World();
        world.info.put(world.root, new VoxelSection.Info(world.root, 1, 255, 1));
        ResidentOctree tree = new ResidentOctree(world);
        LongList selected = new LongArrayList();
        tree.tick(selected);
        world.complete(0, true);
        tree.tick(selected);
        assertArrayEquals(new long[] {VoxelKey.child(world.root, 0)}, world.requests.get(1));
        world.complete(1, true);
        tree.tick(selected);
        tree.releaseRetired();
        assertEquals(List.of(VoxelKey.child(world.root, 0)), selected);
    }

    @Test
    void memoryPressureMergesOneLevelInsteadOfDiscardingAnEntireDeepSubtree() {
        World world = new World(2);
        for (int i = 0; i < 8; i++) {
            long child = VoxelKey.child(world.root, i);
            for (int j = 0; j < 8; j++) {
                long leaf = VoxelKey.child(child, j);
                world.info.put(leaf, new VoxelSection.Info(leaf, 1, 255, 255));
            }
        }
        ResidentOctree tree = new ResidentOctree(world);
        LongList selected = new LongArrayList();
        tree.tick(selected);
        for (int step = 0; step < 10 && !tree.viewRefined(); step++) {
            for (int i = 0; i < world.completions.size(); i++)
                if (!world.completions.get(i).isDone()) world.complete(i, true);
            tree.tick(selected);
            tree.releaseRetired();
        }
        assertEquals(64, selected.size());
        int before = world.requests.size();
        world.area = -1;
        world.pressure = true;
        tree.tick(selected);
        assertTrue(world.requests.size() > before);
        for (int i = before; i < world.requests.size(); i++) {
            assertEquals(1, world.requests.get(i).length);
            assertEquals(1, VoxelKey.level(world.requests.get(i)[0]));
        }
    }

    @Test
    void parentSurvivesUntilAllEightChildrenPublishAndMergeRetainsChildrenUntilParentReady() {
        World world = new World();
        ResidentOctree tree = new ResidentOctree(world);
        LongList selected = new LongArrayList();
        tree.tick(selected);
        assertArrayEquals(new long[0], world.replaced.get(0));
        world.complete(0, true);
        tree.tick(selected);
        assertEquals(List.of(world.root), selected);
        assertEquals(8, world.requests.get(1).length);
        assertArrayEquals(new long[] {world.root}, world.replaced.get(1));
        for (int i = 0; i < 7; i++) world.resident.add(world.requests.get(1)[i]);
        tree.tick(selected);
        assertEquals(List.of(world.root), selected);
        world.complete(1, true);
        tree.tick(selected);
        assertEquals(8, selected.size());
        tree.releaseRetired();
        assertFalse(world.resident.contains(world.root));
        world.area = 100;
        tree.tick(selected);
        assertEquals(8, selected.size());
        assertArrayEquals(world.requests.get(1), world.replaced.get(2));
        world.complete(2, true);
        tree.tick(selected);
        assertEquals(List.of(world.root), selected);
        tree.releaseRetired();
        assertEquals(Set.of(world.root), world.resident);
    }

    @Test
    void rejectedGroupDoesNotModifyDesiredQualityOrRebuildUntilCapacityChanges() {
        World world = new World();
        ResidentOctree tree = new ResidentOctree(world);
        LongList selected = new LongArrayList();
        tree.tick(selected);
        world.complete(0, true);
        tree.tick(selected);
        world.complete(1, false);
        tree.tick(selected);
        tree.releaseRetired();
        for (int i = 0; i < 20; i++) tree.tick(selected);
        assertEquals(2, world.requests.size());
        assertEquals(10000, world.area);
        assertEquals(List.of(world.root), selected);
        world.capacity++;
        tree.tick(selected);
        assertEquals(3, world.requests.size());
        assertEquals(8, world.requests.get(2).length);
    }

    @Test
    void mergeAndDescendantRefreshCannotBeAdmittedTogether() {
        World world = new World();
        ResidentOctree tree = new ResidentOctree(world);
        LongList selected = new LongArrayList();
        tree.tick(selected);
        world.complete(0, true);
        tree.tick(selected);
        world.complete(1, true);
        tree.tick(selected);
        tree.releaseRetired();
        world.dirty.addAll(world.resident);
        world.area = 100;
        tree.tick(selected);
        assertEquals(3, world.requests.size());
        assertArrayEquals(new long[] {world.root}, world.requests.get(2));
    }

    @Test
    void offscreenChildrenMergeAndReleaseCapacityWithoutChangingVisibleDetail() {
        World world = new World();
        ResidentOctree tree = new ResidentOctree(world);
        LongList selected = new LongArrayList();
        tree.tick(selected);
        world.complete(0, true);
        tree.tick(selected);
        assertFalse(tree.viewRefined());
        world.complete(1, true);
        tree.tick(selected);
        tree.releaseRetired();
        assertTrue(tree.viewRefined());
        world.area = -1;
        tree.tick(selected);
        assertEquals(2, world.requests.size());
        world.pressure = true;
        tree.tick(selected);
        assertArrayEquals(new long[] {world.root}, world.requests.get(2));
        world.complete(2, true);
        tree.tick(selected);
        tree.releaseRetired();
        assertEquals(Set.of(world.root), world.resident);
    }

    @Test
    void childObservedDuringSplitMustBeResidentBeforeParentRetires() {
        World world = new World();
        world.info.put(world.root, new VoxelSection.Info(world.root, 1, 1, 1));
        ResidentOctree tree = new ResidentOctree(world);
        LongList selected = new LongArrayList();
        tree.tick(selected);
        world.complete(0, true);
        tree.tick(selected);
        assertEquals(1, world.requests.get(1).length);
        world.info.put(world.root, new VoxelSection.Info(world.root, 2, 3, 3));
        world.complete(1, true);
        tree.tick(selected);
        tree.releaseRetired();
        assertEquals(List.of(world.root), selected);
        assertEquals(1, world.requests.get(2).length);
        assertArrayEquals(new long[] {world.root}, world.replaced.get(2));
        world.complete(2, true);
        tree.tick(selected);
        tree.releaseRetired();
        assertEquals(2, selected.size());
        assertFalse(world.resident.contains(world.root));
    }

    @Test
    void changedSourceCanRetryARejectedMeshWithoutWaitingForUnrelatedRetirement() {
        World world = new World();
        ResidentOctree tree = new ResidentOctree(world);
        LongList selected = new LongArrayList();
        tree.tick(selected);
        world.complete(0, true);
        tree.tick(selected);
        world.complete(1, false);
        tree.tick(selected);
        tree.releaseRetired();
        world.info.put(world.root, new VoxelSection.Info(world.root, 2, 255, 255));
        tree.tick(selected);
        assertEquals(3, world.requests.size());
    }
}
