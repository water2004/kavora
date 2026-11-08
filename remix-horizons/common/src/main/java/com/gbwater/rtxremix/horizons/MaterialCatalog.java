/* SPDX-License-Identifier: LGPL-3.0-only */
package com.gbwater.rtxremix.horizons;

import com.gbwater.rtxremix.horizons.voxel.VoxelSection;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import net.minecraft.world.level.block.state.BlockState;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * World-scoped identities, persisted with the observations that first use them. No model objects
 * cross threads.
 */
final class MaterialCatalog {
    record Key(BlockState state, String biome) {}

    record Entry(Key key, boolean solid, int opacity, int fluidMaterial) {}

    record Layer(TerrainMaterial.FaceMaterial material, int tint) {}

    record Face(List<Layer> layers) {
        Face {
            layers = List.copyOf(layers);
        }

        Face(TerrainMaterial.FaceMaterial face, int tint) {
            this(List.of(new Layer(face, tint)));
        }
    }

    private final ConcurrentHashMap<Key, Integer> ids = new ConcurrentHashMap<>();
    // Segments and their entries are published independently with volatile semantics.
    // IDs are never replaced after restore has completed and the session is ready.
    private static final int SEGMENT_BITS = 10, SEGMENT_SIZE = 1 << SEGMENT_BITS;
    private final AtomicReferenceArray<AtomicReferenceArray<Entry>> entries =
            new AtomicReferenceArray<>((VoxelSection.MATERIAL_MASK + 1) / SEGMENT_SIZE);
    private final Map<Integer, String> pending = new LinkedHashMap<>();
    private volatile TerrainMaterialPalette modelPalette;
    private int nextId = 1;

    int register(BlockState state, String biome) {
        if (state.isAir()) return 0;
        Key key = new Key(state, biome);
        Integer existing = ids.get(key);
        if (existing != null) return existing;
        return registerMissing(key);
    }

    private synchronized int registerMissing(Key key) {
        Integer existing = ids.get(key);
        if (existing != null) return existing;
        BlockState state = key.state();
        String biome = key.biome();
        if (nextId > VoxelSection.MATERIAL_MASK)
            throw new IllegalStateException("Voxel material identity limit exceeded");
        int id = nextId++;
        int fluid = 0;
        if (!state.getFluidState().isEmpty()
                && !(state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock))
            fluid = register(state.getFluidState().createLegacyBlock(), biome);
        publish(
                id,
                new Entry(
                        key,
                        state.isSolidRender(),
                        state.isSolidRender() ? 3 : !state.getFluidState().isEmpty() ? 2 : 1,
                        fluid));
        pending.put(
                id,
                BlockState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow().toString()
                        + "\n"
                        + biome);
        // Publishing the identity makes its entry and fluid companion available to all readers.
        ids.put(key, id);
        // Model discovery overlaps observation conversion and meshing. Baking stays on the render thread.
        TerrainMaterialPalette palette = modelPalette;
        if (palette != null) palette.request(state);
        return id;
    }

    synchronized void restore(Map<Integer, String> saved) {
        if (!ids.isEmpty()) throw new IllegalStateException("Material identities already active");
        // Reserve every persisted ID before resolving waterlogged companion states.
        for (var pair : saved.entrySet()) {
            int split = pair.getValue().lastIndexOf('\n');
            if (split < 0 || pair.getKey() < 1 || pair.getKey() > VoxelSection.MATERIAL_MASK)
                throw new IllegalArgumentException("Invalid cached material");
            BlockState state =
                    BlockState.CODEC
                            .parse(
                                    JsonOps.INSTANCE,
                                    JsonParser.parseString(pair.getValue().substring(0, split)))
                            .getOrThrow();
            Key key = new Key(state, pair.getValue().substring(split + 1));
            ids.put(key, pair.getKey());
            publish(
                    pair.getKey(),
                    new Entry(
                            key,
                            state.isSolidRender(),
                            state.isSolidRender() ? 3 : !state.getFluidState().isEmpty() ? 2 : 1,
                            0));
            nextId = Math.max(nextId, pair.getKey() + 1);
        }
        for (int id : saved.keySet()) {
            Entry entry = entry(id);
            BlockState state = entry.key().state();
            if (!state.getFluidState().isEmpty()
                    && !(state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock)) {
                int fluid =
                        register(state.getFluidState().createLegacyBlock(), entry.key().biome());
                publish(id, new Entry(entry.key(), entry.solid(), entry.opacity(), fluid));
            }
        }
    }

    synchronized Map<Integer, String> drainDictionary() {
        var result = new LinkedHashMap<>(pending);
        pending.clear();
        return result;
    }

    private void publish(int id, Entry entry) {
        int index = id >>> SEGMENT_BITS;
        AtomicReferenceArray<Entry> segment = entries.get(index);
        if (segment == null) {
            segment = new AtomicReferenceArray<>(SEGMENT_SIZE);
            entries.set(index, segment);
        }
        segment.set(id & (SEGMENT_SIZE - 1), entry);
    }

    Entry entry(int id) {
        AtomicReferenceArray<Entry> segment =
                id < 0 || id > VoxelSection.MATERIAL_MASK ? null : entries.get(id >>> SEGMENT_BITS);
        Entry result = segment == null ? null : segment.get(id & (SEGMENT_SIZE - 1));
        if (result == null) throw new IllegalStateException("Missing voxel material " + id);
        return result;
    }

    int opacity(int id) {
        return id == 0 ? 0 : entry(id).opacity();
    }

    int fluidMaterial(int id) {
        return id == 0 ? 0 : entry(id).fluidMaterial();
    }

    boolean hidden(int id, int adjacent, int side) {
        if (adjacent == 0) return false;
        Entry a = entry(id), b = entry(adjacent);
        return BlockFaceOcclusion.between(a.key().state(), b.key().state(), side)
                != BlockFaceOcclusion.VISIBLE;
    }

    /** Worker-owned bounded cache; collisions merely recompute the pair. */
    final class OcclusionView implements com.gbwater.rtxremix.horizons.voxel.VoxelMesher.Occlusion {
        private static final int SIZE = 4096;
        private final long[] keys = new long[SIZE];
        private final byte[] masks = new byte[SIZE];
        private int[] classifications = new int[1024];

        public int classify(int material) {
            if (material == 0) return 0;
            if (material >= classifications.length)
                classifications = Arrays.copyOf(classifications, Integer.highestOneBit(material) << 1);
            int value = classifications[material];
            if (value == 0) {
                Entry entry = entry(material);
                value = Integer.MIN_VALUE | (entry.fluidMaterial() << 1) | (entry.solid() ? 1 : 0);
                classifications[material] = value;
            }
            return value & Integer.MAX_VALUE;
        }

        public boolean hidden(int id, int adjacent, int side) {
            if (adjacent == 0) return false;
            long key = ((long) id << 20) | adjacent;
            int slot = (int) it.unimi.dsi.fastutil.HashCommon.mix(key) & (SIZE - 1);
            long stored = key + 1;
            if (keys[slot] != stored) {
                Entry a = entry(id), b = entry(adjacent);
                int mask = 0;
                for (int i = 0; i < 6; i++)
                    if (BlockFaceOcclusion.between(a.key().state(), b.key().state(), i)
                            != BlockFaceOcclusion.VISIBLE) mask |= 1 << i;
                keys[slot] = stored;
                masks[slot] = (byte) mask;
            }
            return (masks[slot] & (1 << side)) != 0;
        }

        public int containedFluid(int material) {
            return fluidMaterial(material);
        }
    }

    View view(TerrainMaterialPalette palette) {
        modelPalette = palette;
        return new View(palette);
    }

    final class View {
        private final TerrainMaterialPalette palette;
        private final Int2ObjectOpenHashMap<Face> faces = new Int2ObjectOpenHashMap<>();

        View(TerrainMaterialPalette palette) {
            this.palette = palette;
        }

        Face resolve(int key) {
            Face result = faces.get(key);
            if (result != null) return result;
            Entry entry = entry(key >>> 3);
            var materials = palette.request(entry.key().state());
            if (materials == null) return null;
            var layers = new ArrayList<Layer>();
            boolean pending = false;
            for (var face : materials.face(key & 7)) {
                Integer tint =
                        palette.requestTint(
                                entry.key().state(), entry.key().biome(), face.tintIndex());
                if (tint == null) pending = true;
                else layers.add(new Layer(face, tint));
            }
            if (pending) return null;
            result = new Face(layers);
            faces.put(key, result);
            return result;
        }
    }
}
