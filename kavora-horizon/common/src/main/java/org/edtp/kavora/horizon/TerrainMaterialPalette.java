/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.horizon;

import org.edtp.kavora.texture.BlockTextureManager;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/** Resource-generation palette baked from current Minecraft models, never from another renderer. */
public final class TerrainMaterialPalette {
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final TerrainMaterial.Side[] SIDES = TerrainMaterial.Side.values();
    private final Thread owner = Thread.currentThread();
    private final BlockTextureManager textures;
    private final IdentityHashMap<BlockState, TerrainMaterial.CellMaterials> states =
            new IdentityHashMap<>();
    private final Map<TerrainMaterial.FaceMaterial, TerrainMaterial.FaceMaterial> faces =
            new HashMap<>();
    private final List<BlockStateModelPart> parts = new ArrayList<>();
    private final RandomSource random = RandomSource.create(42);
    private final Map<BlockState, TerrainMaterial.CellMaterials> published =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<BlockState, Boolean> requested = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<BlockState> pending = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingCount = new AtomicInteger();
    private volatile long revision;

    private record TintKey(BlockState state, String biome, int index) {}

    private final ConcurrentHashMap<TintKey, Integer> tints = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<TintKey, Boolean> tintRequests = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<TintKey> pendingTints = new ConcurrentLinkedQueue<>();

    public TerrainMaterialPalette(BlockTextureManager textures) {
        this.textures = textures;
    }

    /** Call on the render thread before handing the immutable descriptor to a worker. */
    public TerrainMaterial.CellMaterials bake(BlockState state) {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("LOD model bake outside render thread");
        var value = states.computeIfAbsent(state, this::bakeState);
        if (published.putIfAbsent(state, value) == null) revision++;
        return value;
    }

    /**
     * Worker lookup queues bounded discovery; null postpones the cell until drain advances
     * revision.
     */
    public TerrainMaterial.CellMaterials request(BlockState state) {
        var value = published.get(state);
        if (value != null) return value;
        if (requested.putIfAbsent(state, Boolean.TRUE) == null) {
            if (pendingCount.incrementAndGet() <= 4096) pending.add(state);
            else {
                pendingCount.decrementAndGet();
                requested.remove(state);
            }
        }
        return null;
    }

    /** Bakes at most maxStates current-resource models on the render thread. */
    public int drain(int maxStates) {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("LOD model drain outside render thread");
        int count = 0;
        for (BlockState state; count < maxStates && (state = pending.poll()) != null; count++) {
            pendingCount.decrementAndGet();
            try {
                bake(state);
            } finally {
                requested.remove(state);
            }
        }
        for (TintKey key; count < maxStates && (key = pendingTints.poll()) != null; count++) {
            pendingCount.decrementAndGet();
            try {
                tints.put(key, bakeTint(key));
                revision++;
            } finally {
                tintRequests.remove(key);
            }
        }
        return count;
    }

    public long revision() {
        return revision;
    }

    String diagnostics() {
        return "revision="
                + revision
                + "/pending="
                + pendingCount.get()
                + "/states="
                + published.size()
                + "/tints="
                + tints.size();
    }

    /** Immutable albedo tint only; no light engine is queried by a worker or by the tint view. */
    public Integer requestTint(BlockState state, String biome, int index) {
        if (index < 0) return -1;
        TintKey key = new TintKey(state, biome, index);
        Integer value = tints.get(key);
        if (value == null && tintRequests.putIfAbsent(key, Boolean.TRUE) == null) {
            if (pendingCount.incrementAndGet() <= 4096) pendingTints.add(key);
            else {
                pendingCount.decrementAndGet();
                tintRequests.remove(key);
            }
        }
        return value;
    }

    private int bakeTint(TintKey key) {
        var client = Minecraft.getInstance();
        var source = client.getBlockColors().getTintSource(key.state(), key.index());
        boolean water = key.state().is(Blocks.WATER);
        if (source == null && !water) return -1;
        var world = client.level;
        var biomeId =
                key.biome() == null
                        ? null
                        : net.minecraft.resources.Identifier.tryParse(key.biome());
        var biome =
                world == null || biomeId == null
                        ? null
                        : world.registryAccess()
                                .lookupOrThrow(net.minecraft.core.registries.Registries.BIOME)
                                .getValue(biomeId);
        int rgb;
        if (water) rgb = biome == null ? 0x3f76e4 : biome.getWaterColor();
        else if (biome == null) rgb = source.color(key.state());
        else {
            var view =
                    new net.minecraft.client.renderer.block.BlockAndTintGetter() {
                        public int getBlockTint(
                                net.minecraft.core.BlockPos pos,
                                net.minecraft.world.level.ColorResolver resolver) {
                            return resolver.getColor(biome, pos.getX(), pos.getZ());
                        }

                        public net.minecraft.world.level.CardinalLighting cardinalLighting() {
                            return net.minecraft.client.renderer.block.BlockAndTintGetter.EMPTY
                                    .cardinalLighting();
                        }

                        public net.minecraft.world.level.lighting.LevelLightEngine
                                getLightEngine() {
                            throw new IllegalStateException("LOD albedo tint requested lighting");
                        }

                        public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(
                                net.minecraft.core.BlockPos pos) {
                            return null;
                        }

                        public BlockState getBlockState(net.minecraft.core.BlockPos pos) {
                            return key.state();
                        }

                        public net.minecraft.world.level.material.FluidState getFluidState(
                                net.minecraft.core.BlockPos pos) {
                            return key.state().getFluidState();
                        }

                        public int getHeight() {
                            return 384;
                        }

                        public int getMinY() {
                            return -64;
                        }
                    };
            rgb = source.colorInWorld(key.state(), view, net.minecraft.core.BlockPos.ZERO);
        }
        return vertexTint(rgb);
    }

    /** remixapi_HardcodedVertex.color is VK_FORMAT_B8G8R8A8_UNORM: a little-endian ARGB integer. */
    static int vertexTint(int rgb) {
        return 0xff000000 | (rgb & 0xffffff);
    }

    /** Resource reload requires a new palette and invalidates meshes using this generation. */
    @SuppressWarnings("unchecked")
    private TerrainMaterial.CellMaterials bakeState(BlockState state) {
        List<TerrainMaterial.FaceMaterial>[] result = new List[6];
        Arrays.fill(result, List.of());
        if (state.isAir()) return new TerrainMaterial.CellMaterials(result);
        boolean water = state.getFluidState().is(FluidTags.WATER);
        if (water && state.is(Blocks.WATER)) {
            var surface =
                    new TerrainMaterial.SurfaceMaterial(BlockTextureManager.L_TRANS, false, true);
            Arrays.fill(
                    result,
                    List.of(
                            intern(
                                    new TerrainMaterial.FaceMaterial(
                                            surface, 0, 0, 0, 1, 1, 0, 0, 1, 0, 0, 1))));
            return new TerrainMaterial.CellMaterials(result);
        }
        if (state.is(Blocks.LAVA)) {
            var sprite =
                    textures.getSpriteById(
                            net.minecraft.resources.Identifier.withDefaultNamespace(
                                    "block/lava_still"));
            if (sprite == null) throw new IllegalStateException("LOD lava sprite unavailable");
            var surface =
                    new TerrainMaterial.SurfaceMaterial(
                            BlockTextureManager.L_SOLID, sprite.hasSSS, false);
            Arrays.fill(
                    result,
                    List.of(
                            intern(
                                    new TerrainMaterial.FaceMaterial(
                                            surface,
                                            -1,
                                            sprite.u0,
                                            sprite.v0,
                                            sprite.u1,
                                            sprite.v1,
                                            sprite.u0,
                                            sprite.v0,
                                            sprite.u1 - sprite.u0,
                                            0,
                                            0,
                                            sprite.v1 - sprite.v0))));
            return new TerrainMaterial.CellMaterials(result);
        }
        var model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
        parts.clear();
        random.setSeed(42);
        model.collectParts(random, parts);
        List<FaceCandidate>[] candidates = new List[6];
        for (int i = 0; i < 6; i++) candidates[i] = new ArrayList<>();
        boolean glass = state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE);
        for (BlockStateModelPart part : parts) {
            for (Direction side : DIRECTIONS) collect(candidates, part.getQuads(side), glass);
            collect(candidates, part.getQuads(null), glass);
        }
        parts.clear();
        for (int side = 0; side < 6; side++) result[side] = selectLayers(candidates[side]);
        return new TerrainMaterial.CellMaterials(result);
    }

    private record FaceCandidate(
            TerrainMaterial.FaceMaterial material,
            boolean blended,
            float plane,
            float minS,
            float minT,
            float maxS,
            float maxT) {
        boolean coversSamePlane(FaceCandidate other) {
            return java.util.Objects.equals(material.shape(), other.material.shape())
                    && Math.abs(plane - other.plane) < 1e-5f
                    && Math.abs(minS - other.minS) < 1e-5f
                    && Math.abs(minT - other.minT) < 1e-5f
                    && Math.abs(maxS - other.maxS) < 1e-5f
                    && Math.abs(maxT - other.maxT) < 1e-5f;
        }
    }

    private void collect(List<FaceCandidate>[] result, List<BakedQuad> quads, boolean glass) {
        for (BakedQuad quad : quads) {
            int side = quad.direction().ordinal();
            var sprite = quad.materialInfo().sprite();
            var entry = textures.getSpriteById(sprite.contents().name());
            int faceLayer =
                    glass
                            ? BlockTextureManager.L_GLASS
                            : switch (quad.materialInfo().layer()) {
                                case TRANSLUCENT -> BlockTextureManager.L_TRANS;
                                case CUTOUT -> BlockTextureManager.L_CUTOUT;
                                default -> BlockTextureManager.L_SOLID;
                            };
            int axis = SIDES[side].axis;
            float[] uv = fitUv(quad, axis);
            if (uv == null) continue;
            float minS = Float.POSITIVE_INFINITY, minT = minS, minU = minS, minV = minS;
            float maxS = Float.NEGATIVE_INFINITY, maxT = maxS, maxU = maxS, maxV = maxS;
            for (int v = 0; v < 4; v++) {
                var position = quad.position(v);
                minS = Math.min(minS, position.get((axis + 1) % 3));
                minT = Math.min(minT, position.get((axis + 2) % 3));
                maxS = Math.max(maxS, position.get((axis + 1) % 3));
                maxT = Math.max(maxT, position.get((axis + 2) % 3));
                float u = net.minecraft.client.model.geom.builders.UVPair.unpackU(quad.packedUV(v));
                float t = net.minecraft.client.model.geom.builders.UVPair.unpackV(quad.packedUV(v));
                minU = Math.min(minU, u);
                minV = Math.min(minV, t);
                maxU = Math.max(maxU, u);
                maxV = Math.max(maxV, t);
            }
            boolean blended =
                    faceLayer == BlockTextureManager.L_TRANS
                            || glass
                            || entry != null && entry.hasTransparency(minU, minV, maxU, maxV);
            boolean sss =
                    entry != null
                            && entry.hasSSS
                            && textures.getAtlasMaterialSSS(faceLayer) != null;
            if (!blended && !sss) faceLayer = BlockTextureManager.L_SOLID;
            var face =
                    intern(
                            new TerrainMaterial.FaceMaterial(
                                    new TerrainMaterial.SurfaceMaterial(faceLayer, sss, false),
                                    quad.materialInfo().tintIndex(),
                                    sprite.getU0(),
                                    sprite.getV0(),
                                    sprite.getU1(),
                                    sprite.getV1(),
                                    uv[0],
                                    uv[1],
                                    uv[2],
                                    uv[3],
                                    uv[4],
                                    uv[5],
                                    fitShape(quad, side, minS, minT, maxS, maxT)));
            result[side].add(
                    new FaceCandidate(
                            face, blended, quad.position(0).get(axis), minS, minT, maxS, maxT));
        }
    }

    private List<TerrainMaterial.FaceMaterial> selectLayers(List<FaceCandidate> candidates) {
        if (candidates.isEmpty()) return List.of();
        var result = new ArrayList<TerrainMaterial.FaceMaterial>();
        for (FaceCandidate candidate : candidates) {
            var face = candidate.material();
            boolean overlay = false;
            if (candidate.blended())
                for (FaceCandidate other : candidates) {
                    if (!other.blended() && candidate.coversSamePlane(other)) {
                        overlay = true;
                        break;
                    }
                }
            if (overlay) {
                var surface = face.surface();
                face =
                        intern(
                                new TerrainMaterial.FaceMaterial(
                                        new TerrainMaterial.SurfaceMaterial(
                                                surface.layer(), surface.subsurface(), false, true),
                                        face.tintIndex(),
                                        face.minU(),
                                        face.minV(),
                                        face.maxU(),
                                        face.maxV(),
                                        face.originU(),
                                        face.originV(),
                                        face.duU(),
                                        face.duV(),
                                        face.dvU(),
                                        face.dvV(),
                                        face.shape()));
            }
            // Stairs, fences and crossed plants can have several different planes in one direction.
            // Keeping only the largest one loses part of the silhouette before LOD selection runs.
            if (!result.contains(face)) result.add(face);
        }
        result.sort(java.util.Comparator.comparing(face -> face.surface().overlay()));
        return result;
    }

    /** Atlas UV origin and derivatives in the face's cyclic position axes. */
    static float[] fitUv(BakedQuad quad, int axis) {
        int uAxis = (axis + 1) % 3, vAxis = (axis + 2) % 3;
        // Fit the model's planar UV transform, including rotated and mirrored textures.
        var p0 = quad.position(0);
        float u0 = net.minecraft.client.model.geom.builders.UVPair.unpackU(quad.packedUV(0));
        float v0 = net.minecraft.client.model.geom.builders.UVPair.unpackV(quad.packedUV(0));
        for (int a = 1; a < 4; a++)
            for (int b = a + 1; b < 4; b++) {
                var pa = quad.position(a);
                var pb = quad.position(b);
                float au = pa.get(uAxis) - p0.get(uAxis), av = pa.get(vAxis) - p0.get(vAxis);
                float bu = pb.get(uAxis) - p0.get(uAxis), bv = pb.get(vAxis) - p0.get(vAxis);
                float det = au * bv - av * bu;
                if (Math.abs(det) < 1e-6f) continue;
                float ua =
                        net.minecraft.client.model.geom.builders.UVPair.unpackU(quad.packedUV(a))
                                - u0;
                float va =
                        net.minecraft.client.model.geom.builders.UVPair.unpackV(quad.packedUV(a))
                                - v0;
                float ub =
                        net.minecraft.client.model.geom.builders.UVPair.unpackU(quad.packedUV(b))
                                - u0;
                float vb =
                        net.minecraft.client.model.geom.builders.UVPair.unpackV(quad.packedUV(b))
                                - v0;
                float duU = (ua * bv - ub * av) / det, dvU = (ub * au - ua * bu) / det;
                float duV = (va * bv - vb * av) / det, dvV = (vb * au - va * bu) / det;
                return new float[] {
                    u0 - duU * p0.get(uAxis) - dvU * p0.get(vAxis),
                    v0 - duV * p0.get(uAxis) - dvV * p0.get(vAxis),
                    duU,
                    duV,
                    dvU,
                    dvV
                };
            }
        return null;
    }

    /** Bake geometry once with the resource model; workers never read a mutable Minecraft model. */
    static TerrainMaterial.FaceShape fitShape(
            BakedQuad quad, int side, float minS, float minT, float maxS, float maxT) {
        int axis = SIDES[side].axis, s = (axis + 1) % 3, t = (axis + 2) % 3;
        var p = quad.position(0);
        for (int a = 1; a < 4; a++)
            for (int b = a + 1; b < 4; b++) {
                var q = quad.position(a);
                var r = quad.position(b);
                float qs = q.get(s) - p.get(s), qt = q.get(t) - p.get(t);
                float rs = r.get(s) - p.get(s), rt = r.get(t) - p.get(t);
                float determinant = qs * rt - qt * rs;
                if (Math.abs(determinant) < 1e-6f) continue;
                float qd = q.get(axis) - p.get(axis), rd = r.get(axis) - p.get(axis);
                float ds = snap((qd * rt - rd * qt) / determinant);
                float dt = snap((rd * qs - qd * rs) / determinant);
                float origin = snap(p.get(axis) - ds * p.get(s) - dt * p.get(t));
                minS = snap(minS);
                minT = snap(minT);
                maxS = snap(maxS);
                maxT = snap(maxT);
                if (ds == 0
                        && dt == 0
                        && origin == (SIDES[side].sign > 0 ? 1 : 0)
                        && minS == 0
                        && minT == 0
                        && maxS == 1
                        && maxT == 1) return null;
                return new TerrainMaterial.FaceShape(minS, minT, maxS, maxT, origin, ds, dt);
            }
        throw new IllegalArgumentException("Degenerate model face passed UV fitting");
    }

    private static float snap(float value) {
        float rounded = Math.round(value);
        return Math.abs(value - rounded) < 1e-6f ? rounded : value;
    }

    private TerrainMaterial.FaceMaterial intern(TerrainMaterial.FaceMaterial face) {
        return faces.computeIfAbsent(face, key -> key);
    }
}
