/* SPDX-License-Identifier: LGPL-3.0-only */
package org.edtp.kavora.horizon.voxel;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

class VoxelPipelineTest {
    @Test
    void derivesAndUpdatesExactSourcesFromExistingVoxelRecords(@TempDir Path directory) throws Exception {
        long key = VoxelKey.of(0, -2, 4, 3);
        int index = VoxelKey.index(27, 8, 15);
        int[] cells = new int[VoxelKey.VOLUME];
        cells[index] = 1;
        var first = new VoxelSection(key, 1, 255, 255, cells);
        try (var old = new VoxelDatabase(directory)) {
            old.commit(List.of(first), Map.of());
        }
        try (var indexed = new VoxelDatabase(directory)) {
            indexed.openBlockSourceIndex(id -> id == 1 ? 15 : id == 2 ? 7 : 0, () -> true);
            while (indexed.backfillBlockSources()) { }
            Map<Long, int[]> actual = new HashMap<>();
            indexed.forEachBlockSourceSection(actual::put);
            assertArrayEquals(new int[] { index | 15 * VoxelKey.VOLUME }, actual.get(key));
            assertTrue(Arrays.stream(VoxelMip.reduce(first, id -> -1)).allMatch(cell -> cell == 0));
            cells = cells.clone();
            cells[index] = 2;
            indexed.commit(List.of(new VoxelSection(key, 2, 255, 255, cells)), Map.of());
        }
        try (var reopened = new VoxelDatabase(directory)) {
            reopened.openBlockSourceIndex(id -> id == 2 ? 7 : 0, () -> true);
            assertFalse(reopened.backfillBlockSources());
            Map<Long, int[]> actual = new HashMap<>();
            reopened.forEachBlockSourceSection(actual::put);
            assertArrayEquals(new int[] { index | 7 * VoxelKey.VOLUME }, actual.get(key));
            reopened.commit(List.of(new VoxelSection(key, 3, 255, 0, VoxelSection.AIR)), Map.of());
            actual.clear();
            reopened.forEachBlockSourceSection(actual::put);
            assertTrue(actual.isEmpty());
        }
    }

    @Test
    void mipPriorityTiesKeepTheLastSampleAndItsFluidHeight() {
        Random random = new Random(0x50414c45545445L);
        for (int level = 0; level <= VoxelKey.ROOT_LEVEL; level++) {
            for (int variant = 0; variant < 64; variant++) {
                int[] cells = new int[VoxelKey.VOLUME];
                int[] samples = new int[8];
                for (int i = 0; i < samples.length; i++) {
                    int id = random.nextInt(8);
                    samples[i] = id == 0 ? 0
                            : VoxelSection.withFluidTop(id, random.nextInt(10));
                    cells[VoxelKey.index(8 + (i & 1), 12 + (i >>> 2), 20 + ((i >>> 1) & 1))] = samples[i];
                }
                int selected = java.util.stream.IntStream.range(0, 8).boxed()
                        .filter(i -> VoxelSection.material(samples[i]) != 0)
                        .max(Comparator.<Integer>comparingInt(i -> VoxelSection.material(samples[i]) % 3)
                                .thenComparingInt(i -> i))
                        .orElse(-1);
                int expected = selected < 0 ? 0 : samples[selected];
                if (VoxelSection.fluidTop(expected) != 0) {
                    expected = VoxelSection.withFluidTop(VoxelSection.material(expected),
                            VoxelSection.fluidTop(expected) + (selected >>> 2) * (1 << level) * 9);
                }
                var source = new VoxelSection(VoxelKey.of(level, -1, 2, -3), 1, 255, 255, cells);
                int[] result = VoxelMip.reduce(source, id -> id % 3);
                assertEquals(expected, result[4 | 10 << 4 | 6 << 8]);
                assertEquals(expected == 0 ? 0 : 1,
                        Arrays.stream(result).filter(cell -> cell != 0).count());
            }
        }
    }

    @Test
    void mipScratchOverwritesPreviousSamplesWithoutChangingTheSource() {
        int[] cells = new int[VoxelKey.VOLUME];
        Arrays.fill(cells, 7);
        int[] scratch = new int[4096];
        var source = new VoxelSection(VoxelKey.of(0, 0, 0, 0), 1, 255, 255, cells);
        VoxelMip.reduceInto(source, id -> 1, scratch);
        assertTrue(Arrays.stream(scratch).allMatch(cell -> cell == 7));
        var empty = new VoxelSection(VoxelKey.of(0, 0, 0, 0), 2, 255, 0, VoxelSection.AIR);
        VoxelMip.reduceInto(empty, id -> { throw new AssertionError("Air has no material"); }, scratch);
        assertTrue(Arrays.stream(scratch).allMatch(cell -> cell == 0));
        assertTrue(Arrays.stream(source.cells()).allMatch(cell -> cell == 7));
        VoxelMip.reduceInto(source, id -> 1, scratch);
        assertTrue(Arrays.stream(scratch).allMatch(cell -> cell == 7));
    }

    @TempDir Path directory;

    @Test
    void sectionBatchKeepsUnknownRecordsDuplicatesAndAllCellBits() throws Exception {
        long first = VoxelKey.of(0, -9, 3, 5);
        long second = VoxelKey.of(1, 4, -2, -8);
        long missing = VoxelKey.of(0, 11, 2, 4);
        int[] cells = new int[VoxelKey.VOLUME];
        for (int i = 0; i < cells.length; i++)
            cells[i] = VoxelSection.withFluidTop(i % 23 + 1, i % 19);
        var a = new VoxelSection(first, 37, 0x85, 0x81, cells);
        var b = new VoxelSection(second, 91, 0x42, 0, VoxelSection.AIR);
        try (VoxelDatabase db = new VoxelDatabase(directory)) {
            db.commit(List.of(a, b), Map.of());
            var read = db.read(new long[] {missing, second, first, missing, first});
            assertNull(read[0]);
            assertNull(read[3]);
            assertEquals(b.info(), read[1].info());
            assertSame(VoxelSection.AIR, read[1].cells());
            for (int i : new int[] {2, 4}) {
                assertEquals(a.info(), read[i].info());
                assertArrayEquals(a.cells(), read[i].cells());
            }
            assertEquals(0, db.read(new long[0]).length);
        }
    }

    @Test
    void neighborhoodMissesBecomeObservedWithoutMutatingPublishedSnapshots() throws Exception {
        long center = VoxelKey.of(0, -2, 0, 5);
        int[] solid = new int[4096];
        Arrays.fill(solid, 7);
        try (VoxelDatabase db = new VoxelDatabase(directory)) {
            VoxelWorld world = new VoxelWorld(db, 64);
            assertTrue(Arrays.stream(world.neighborhood(center)).allMatch(Objects::isNull));
            world.apply(List.of(new VoxelWorld.Observation(-4, 0, 10, solid),
                    new VoxelWorld.Observation(-2, 0, 10, new int[4096])),
                    Map.of(), id -> 3);
            var first = world.neighborhood(center);
            assertEquals(7, first[0].cell(0, 0, 0));
            assertNull(first[1]);
            assertNotNull(first[2]);
            assertTrue(first[2].info().empty());
            assertNull(first[3]);
            int[] next = solid.clone();
            next[0] = 13;
            world.apply(List.of(new VoxelWorld.Observation(-4, 0, 10, next)),
                    Map.of(), id -> 3);
            var second = world.neighborhood(center);
            assertEquals(13, second[0].cell(0, 0, 0));
            assertEquals(7, first[0].cell(0, 0, 0));
            assertTrue(second[0].revision() > first[0].revision());
        }
    }

    @Test
    void metadataBatchKeepsUnknownSlotsDuplicatesAndRequestOrder() throws Exception {
        long first = VoxelKey.of(0, -9, 3, 5);
        long second = VoxelKey.of(1, 4, -2, -8);
        long missing = VoxelKey.of(0, 11, 2, 4);
        var a = new VoxelSection(first, 37, 0x85, 0x81, VoxelSection.AIR);
        var b = new VoxelSection(second, 91, 0x42, 0x02, VoxelSection.AIR);
        try (VoxelDatabase db = new VoxelDatabase(directory)) {
            db.commit(List.of(a, b), Map.of());
            long[] request = {missing, second, first, missing, second};
            assertArrayEquals(new VoxelSection.Info[] {null, b.info(), a.info(), null, b.info()},
                    db.infos(request));
            assertEquals(0, db.infos(new long[0]).length);
            var updated = new VoxelSection(first, 38, 255, 255, VoxelSection.AIR);
            db.commit(List.of(updated), Map.of());
            assertEquals(updated.info(), db.infos(new long[] {first})[0]);
            assertArrayEquals(new long[] {missing, second, first, missing, second}, request);
        }
    }

    @Test
    void batchPublishesEachParentOnceAndPreservesReadersSnapshots() throws Exception {
        try (VoxelDatabase db = new VoxelDatabase(directory)) {
            VoxelWorld world = new VoxelWorld(db, 32);
            var observations = new ArrayList<VoxelWorld.Observation>();
            for (int i = 0; i < 8; i++) {
                int[] cells = new int[4096];
                Arrays.fill(cells, i + 1);
                observations.add(new VoxelWorld.Observation(i & 1, i >>> 2, (i >>> 1) & 1, cells));
            }
            var first = world.apply(observations, Map.of(), id -> 1);
            assertEquals(VoxelKey.ROOT_LEVEL + 1, first.size());
            VoxelSection original = world.get(VoxelKey.of(0, 0, 0, 0));
            int[] publishedCells = original.cells().clone();
            assertEquals(255, original.knownChildren());
            assertEquals(255, original.occupiedChildren());
            assertTrue(world.apply(observations, Map.of(), id -> 1).isEmpty());
            assertSame(original, world.get(original.key()));
            for (var observation : observations) Arrays.fill(observation.cells(), 11);
            var second = world.apply(observations, Map.of(), id -> 1);
            for (var update : second) {
                var before = first.stream().filter(v -> v.section().key() == update.section().key()).findFirst().orElseThrow();
                assertEquals(before.section().revision() + 1, update.section().revision());
            }
            assertArrayEquals(publishedCells, original.cells());
            assertTrue(Arrays.stream(world.get(original.key()).cells()).allMatch(id -> id == 11));
            assertTrue(Arrays.stream(VoxelSection.AIR).allMatch(id -> id == 0));
        }
    }

    @Test
    void publishedEditorCannotMutateItsSnapshot() {
        var editor = new VoxelSection.Editor(0, null);
        int[] cells = new int[4096];
        Arrays.fill(cells, 1);
        editor.replaceOctant(0, cells);
        VoxelSection published = editor.finish();
        assertThrows(IllegalStateException.class, () -> editor.replaceOctant(1, cells));
        assertThrows(IllegalStateException.class, () -> editor.setOccupied(0, false));
        assertThrows(IllegalStateException.class, editor::finish);
        assertEquals(1, published.knownChildren());
        assertEquals(1, published.occupiedChildren());
    }

    @Test
    void editedRowsProduceTheCommittedSnapshotChangeMask() {
        Random random = new Random(0x677816);
        int[] populated = new int[VoxelKey.VOLUME];
        for (int i = 0; i < populated.length; i++) populated[i] = random.nextInt(10);
        int[] snapshot = populated.clone();
        for (int iteration = 0; iteration < 256; iteration++) {
            VoxelSection previous = iteration % 4 == 0 ? null
                    : new VoxelSection(0, 7, 255, 255, populated);
            int[] original = previous == null ? VoxelSection.AIR : previous.cells();
            var editor = new VoxelSection.Editor(0, previous);
            assertThrows(IllegalStateException.class, editor::meshChanges);
            for (int write = 0, count = 1 + random.nextInt(6); write < count; write++) {
                int octant = random.nextInt(8);
                int[] samples = octantSamples(original, octant);
                for (int change = 0; change < 16; change++) {
                    int index = random.nextInt(samples.length);
                    samples[index] = VoxelSection.withFluidTop(random.nextInt(10), random.nextInt(10));
                }
                editor.replaceOctant(octant, samples);
                if (random.nextInt(4) == 0) editor.replaceOctant(octant, octantSamples(original, octant));
            }
            VoxelSection result = editor.finish();
            assertEquals(exhaustiveMeshChanges(original, result.cells()), editor.meshChanges(),
                    "Final change mask for edit " + iteration);
            if (previous != null) assertArrayEquals(snapshot, previous.cells());
        }
    }

    private static int[] octantSamples(int[] cells, int octant) {
        int[] samples = new int[4096];
        int ox = (octant & 1) * 16, oy = (octant >>> 2) * 16, oz = ((octant >>> 1) & 1) * 16;
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                System.arraycopy(cells, VoxelKey.index(ox, y + oy, z + oz), samples, z << 4 | y << 8, 16);
        return samples;
    }

    private static int exhaustiveMeshChanges(int[] before, int[] after) {
        int mask = 0;
        for (int y = 0; y < 32; y++)
            for (int z = 0; z < 32; z++)
                for (int x = 0; x < 32; x++) {
                    int index = VoxelKey.index(x, y, z);
                    if (before[index] == after[index]) continue;
                    mask |= 1;
                    if (VoxelSection.material(before[index]) == VoxelSection.material(after[index])) continue;
                    if (x == 0) mask |= 1 << 1;
                    if (x == 31) mask |= 1 << 2;
                    if (y == 0) mask |= 1 << 3;
                    if (y == 31) mask |= 1 << 4;
                    if (z == 0) mask |= 1 << 5;
                    if (z == 31) mask |= 1 << 6;
                }
        return mask;
    }

    @Test
    void signedOctantsRoundTripThroughEveryLevel() {
        for (int x : new int[] {-1000000, -2, -1, 0, 1, 1000000})
            for (int y : new int[] {-10, -1, 0, 20})
                for (int z : new int[] {-50, -1, 0, 50}) {
                    long key = VoxelKey.of(0, x, y, z);
                    assertEquals(x, VoxelKey.x(key));
                    assertEquals(y, VoxelKey.y(key));
                    assertEquals(z, VoxelKey.z(key));
                    for (int level = 0; level < VoxelKey.ROOT_LEVEL; level++) {
                        long parent = VoxelKey.parent(key);
                        assertEquals(key, VoxelKey.child(parent, VoxelKey.octant(key)));
                        assertTrue(VoxelKey.contains(parent, key));
                        key = parent;
                    }
                }
    }

    @Test
    void codecHandlesUniformAndMixedPalettesAndRejectsDamage() {
        for (int palette : new int[] {1, 2, 3, 127, 257, 32768}) {
            int[] cells = new int[VoxelKey.VOLUME];
            for (int i = 0; i < cells.length; i++) cells[i] = i % palette;
            VoxelSection source = new VoxelSection(0, 19, 255, 255, cells);
            byte[] record = VoxelCodec.encode(source);
            assertArrayEquals(cells, VoxelCodec.decode(0, record).cells());
            if (palette == 1) assertSame(VoxelSection.AIR, VoxelCodec.decode(0, record).cells());
            record[record.length - 1] ^= 1;
            assertThrows(IllegalArgumentException.class, () -> VoxelCodec.decode(0, record));
        }
    }

    @Test
    void codecPreservesEveryIndexWidthAndFinalPackedIndex() {
        Random random = new Random(0x564F58454CL);
        for (int bits = 0; bits <= 15; bits++) {
            for (int count : new int[] {1 << bits, Math.max(1, (1 << bits) - 1)}) {
                int[] cells = new int[VoxelKey.VOLUME];
                for (int i = 0; i < cells.length; i++) {
                    cells[i] = 7 + (i < count ? i : random.nextInt(count));
                }
                cells[cells.length - 1] = count + 6;
                VoxelSection source = new VoxelSection(VoxelKey.of(2, -5, 3, 7), 91, 0xa5, 0x81, cells);
                byte[] encoded = VoxelCodec.encode(source);
                byte[] original = encoded.clone();
                VoxelSection decoded = VoxelCodec.decode(source.key(), encoded);
                assertArrayEquals(cells, decoded.cells(), "palette=" + count);
                assertEquals(source.key(), decoded.key());
                assertEquals(source.revision(), decoded.revision());
                assertEquals(source.knownChildren(), decoded.knownChildren());
                assertEquals(source.occupiedChildren(), decoded.occupiedChildren());
                assertArrayEquals(original, encoded);
                assertArrayEquals(encoded, VoxelCodec.encode(decoded));
                assertNotSame(decoded.cells(), VoxelCodec.decode(source.key(), encoded).cells());
            }
        }
    }

    @Test
    void codecRejectsInvalidPackedIndexEvenWithValidChecksum() {
        for (int count : new int[] {3, 7, 31, 127, 257, 32767}) {
            int[] cells = new int[VoxelKey.VOLUME];
            for (int i = 0; i < cells.length; i++) cells[i] = i % count;
            byte[] encoded = VoxelCodec.encode(new VoxelSection(0, 1, 255, 255, cells));
            int bits = 32 - Integer.numberOfLeadingZeros(count - 1);
            for (int index : new int[] {0, 7, VoxelKey.VOLUME - 1}) {
                byte[] damaged = encoded.clone();
                // Independent bit writer covers unaligned indices and the checksum boundary.
                for (int bit = 0; bit < bits; bit++) {
                    int offset = index * bits + bit;
                    damaged[18 + count * 4 + (offset >>> 3)] |= (byte) (1 << (offset & 7));
                }
                var crc = new java.util.zip.CRC32C();
                crc.update(damaged, 0, damaged.length - 4);
                java.nio.ByteBuffer.wrap(damaged).putInt(damaged.length - 4, (int) crc.getValue());
                var failure = assertThrows(IllegalArgumentException.class, () -> VoxelCodec.decode(0, damaged));
                assertEquals("Invalid voxel palette index", failure.getMessage());
            }
        }
    }

    @Test
    void completedBatchRestoresObservedShapeAndFluidHeightAfterReopen() throws Exception {
        int[] cells = new int[4096];
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++)
                cells[x | z << 4 | 9 << 8] = VoxelSection.withFluidTop(7, 8);
        long leaf = VoxelKey.of(0, -1, 0, 0), root = leaf;
        for (int i = 0; i < VoxelKey.ROOT_LEVEL; i++) root = VoxelKey.parent(root);
        try (VoxelDatabase db = new VoxelDatabase(directory)) {
            VoxelWorld world = new VoxelWorld(db, 16);
            world.apply(
                    List.of(new VoxelWorld.Observation(-1, 0, 0, cells)),
                    Map.of(7, "water"),
                    id -> 1);
            assertEquals("water", db.materials().get(7));
            assertEquals(1, db.roots().size());
            assertEquals(2, world.get(leaf).knownChildren());
            assertEquals(2, world.get(leaf).occupiedChildren());
        }
        try (VoxelDatabase db = new VoxelDatabase(directory)) {
            VoxelSection section = db.read(root);
            assertNotNull(section);
            // y=9+8/9 remains the same world-space surface after four reductions.
            int surface = 0;
            for (int cell : section.cells())
                surface = Math.max(surface, VoxelSection.fluidTop(cell));
            assertEquals(89, surface);
            assertEquals(2, db.read(leaf).knownChildren());
        }
    }

    @Test
    void greedyMeshPreservesVolumeAndCullsNeighborBoundary() {
        int[] cells = new int[VoxelKey.VOLUME];
        Arrays.fill(cells, 1);
        VoxelSection solid = new VoxelSection(0, 1, 255, 255, cells);
        VoxelMesher mesher = new VoxelMesher();
        VoxelSection[] around = new VoxelSection[7];
        around[0] = solid;
        var mesh = mesher.build(around, (a, b, side) -> b != 0);
        assertEquals(6, mesh.quads().length);
        for (long q : mesh.quads()) {
            assertEquals(32, VoxelMesher.width(q));
            assertEquals(32, VoxelMesher.height(q));
        }
        around[2] = solid;
        assertEquals(5, mesher.build(around, (a, b, side) -> b != 0).quads().length);
        assertEquals(
                0,
                mesher.build(
                                new VoxelSection[] {
                                    new VoxelSection(0, 1, 255, 0, VoxelSection.AIR),
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null
                                },
                                (a, b, s) -> false)
                        .quads()
                        .length);
    }

    @Test
    void asymmetricBoundarySamplesPreserveSolidAndContainedFluidFaces() {
        int[][] points = {{7, 0, 23}, {7, 31, 23}, {7, 19, 0}, {7, 19, 31}, {0, 19, 23}, {31, 19, 23}};
        int[] axes = {1, 1, 2, 2, 0, 0};
        VoxelMesher.Occlusion waterlogged = new VoxelMesher.Occlusion() {
            @Override public boolean hidden(int a, int b, int side) { return a == 4 && b == 2; }
            @Override public int containedFluid(int id) { return id == 3 ? 4 : 0; }
        };
        for (int side = 0; side < 6; side++) {
            int[] p = points[side], opposite = p.clone();
            opposite[axes[side]] = (side & 1) == 0 ? 31 : 0;
            int[] source = new int[VoxelKey.VOLUME], neighbour = new int[VoxelKey.VOLUME];
            source[VoxelKey.index(p[0], p[1], p[2])] = VoxelSection.withFluidTop(3, 8);
            neighbour[VoxelKey.index(opposite[0], opposite[1], opposite[2])] = 2;
            VoxelSection[] around = new VoxelSection[7];
            around[0] = new VoxelSection(0, 1, 255, 255, source);
            around[1 + axes[side] * 2 + (side & 1)] = new VoxelSection(0, 1, 255, 255, neighbour);
            var mesh = new VoxelMesher().build(around, waterlogged);
            assertEquals(11, mesh.quads().length, "One fluid boundary, not the recessed model, must disappear");
            int solids = 0, liquids = 0;
            for (long quad : mesh.quads()) {
                assertEquals(p[0], VoxelMesher.x(quad));
                assertEquals(p[1], VoxelMesher.y(quad));
                assertEquals(p[2], VoxelMesher.z(quad));
                assertEquals(1, VoxelMesher.width(quad));
                assertEquals(1, VoxelMesher.height(quad));
                assertEquals(8, VoxelSection.fluidTop(VoxelMesher.cell(quad)));
                if (VoxelSection.material(VoxelMesher.cell(quad)) == 3) solids++;
                else {
                    assertEquals(4, VoxelSection.material(VoxelMesher.cell(quad)));
                    assertNotEquals(side, VoxelMesher.side(quad));
                    liquids++;
                }
            }
            assertEquals(6, solids);
            assertEquals(5, liquids);
        }
    }

    @Test
    void opaqueRowMasksPreserveMixedAndWaterloggedNeighborhoods() {
        VoxelMesher.Occlusion reference = new VoxelMesher.Occlusion() {
            public boolean hidden(int a, int b, int side) {
                return (a == 1 && b == 1) || (a == 3 && (b == 1 || b == 3 || b == 4))
                        || (a == 2 && b == 2 && (side & 1) == 0);
            }
            public int containedFluid(int material) { return material == 4 ? 3 : 0; }
        };
        VoxelMesher.Occlusion classified = new VoxelMesher.Occlusion() {
            public boolean hidden(int a, int b, int side) { return reference.hidden(a, b, side); }
            public int classify(int material) {
                return (material == 1 ? 1 : 0) | (reference.containedFluid(material) << 1);
            }
        };
        var random = new java.util.Random(42);
        var expected = new VoxelMesher();
        var actual = new VoxelMesher();
        for (int sample = 0; sample < 12; sample++) {
            VoxelSection[] neighborhood = new VoxelSection[7];
            for (int section = 0; section < neighborhood.length; section++) {
                if (section != 0 && (sample + section) % 3 == 0) continue;
                int[] cells = new int[VoxelKey.VOLUME];
                for (int i = 0; i < cells.length; i++) {
                    int material = sample < 2 ? 1 : random.nextInt(5);
                    cells[i] = material == 3 || material == 4
                            ? VoxelSection.withFluidTop(material, 8) : material;
                }
                neighborhood[section] = new VoxelSection(section, 1, 255, 255, cells);
            }
            assertArrayEquals(expected.build(neighborhood, reference).quads(),
                    actual.build(neighborhood, classified).quads(), "Neighbourhood " + sample);
        }
    }

    @Test
    void threeDimensionalSimplificationDoesNotExtrudeSingleLayerAcrossHeight() {
        int[] cells = new int[VoxelKey.VOLUME];
        cells[VoxelKey.index(3, 17, 9)] = 2;
        var reduced = VoxelMip.reduce(new VoxelSection(0, 1, 255, 255, cells), id -> 1);
        int count = 0;
        for (int cell : reduced) if (cell != 0) count++;
        assertEquals(1, count);
        assertEquals(2, reduced[1 | 4 << 4 | 8 << 8]);
    }

    @Test
    void meshChangesDistinguishInteriorAndEachBoundary() throws Exception {
        int[][] points = {
            {8, 8, 8}, {0, 8, 8}, {31, 8, 8}, {8, 0, 8}, {8, 31, 8}, {8, 8, 0}, {8, 8, 31}
        };
        for (int i = 0; i < points.length; i++) {
            int x = points[i][0], y = points[i][1], z = points[i][2];
            int[] cells = new int[4096];
            cells[(x & 15) | (z & 15) << 4 | (y & 15) << 8] = 1;
            try (VoxelDatabase db = new VoxelDatabase(directory.resolve("face-" + i))) {
                VoxelWorld world = new VoxelWorld(db, 16);
                var updates =
                        world.apply(
                                List.of(new VoxelWorld.Observation(x >> 4, y >> 4, z >> 4, cells)),
                                Map.of(),
                                id -> 1);
                var leaf =
                        updates.stream()
                                .filter(update -> update.section().key() == VoxelKey.of(0, 0, 0, 0))
                                .findFirst()
                                .orElseThrow();
                assertEquals(i == 0 ? 1 : 1 | 1 << i, leaf.meshChanges());
            }
        }
    }

    @Test
    void emptyObservationAndFluidHeightDoNotInvalidateNeighborMaterials() throws Exception {
        try (VoxelDatabase db = new VoxelDatabase(directory)) {
            VoxelWorld world = new VoxelWorld(db, 16);
            long leaf = VoxelKey.of(0, 0, 0, 0);
            var empty =
                    world.apply(
                            List.of(new VoxelWorld.Observation(0, 0, 0, new int[4096])),
                            Map.of(),
                            id -> 1);
            assertFalse(empty.isEmpty());
            assertTrue(empty.stream().allMatch(update -> update.meshChanges() == 0));

            int[] cells = new int[4096];
            cells[8 << 4 | 8 << 8] = VoxelSection.withFluidTop(1, 3);
            world.apply(List.of(new VoxelWorld.Observation(0, 0, 0, cells)), Map.of(), id -> 1);
            cells = cells.clone();
            cells[8 << 4 | 8 << 8] = VoxelSection.withFluidTop(1, 5);
            var updates =
                    world.apply(
                            List.of(new VoxelWorld.Observation(0, 0, 0, cells)), Map.of(), id -> 1);
            assertEquals(
                    1,
                    updates.stream()
                            .filter(update -> update.section().key() == leaf)
                            .findFirst()
                            .orElseThrow()
                            .meshChanges());

            int[] transientCells = cells.clone();
            transientCells[8 << 4 | 8 << 8] = 2;
            var reverted =
                    world.apply(
                            List.of(
                                    new VoxelWorld.Observation(0, 0, 0, transientCells),
                                    new VoxelWorld.Observation(0, 0, 0, cells)),
                            Map.of(),
                            id -> 1);
            assertEquals(
                    0,
                    reverted.stream()
                            .filter(update -> update.section().key() == leaf)
                            .findFirst()
                            .orElseThrow()
                            .meshChanges());
        }
    }
}
