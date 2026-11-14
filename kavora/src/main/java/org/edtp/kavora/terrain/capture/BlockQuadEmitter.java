/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.terrain.capture;

import org.edtp.kavora.sdk.RemixAPI;
import org.edtp.kavora.light.BlockLightRuleManager;
import org.edtp.kavora.texture.BlockTextureManager;
import org.edtp.kavora.light.LightEntry;
import org.edtp.kavora.light.AreaLightMerger;
import org.edtp.kavora.profiling.ProfileScope;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.level.material.FluidState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Per-block mesh emission for Kavora, designed to be driven from
 * {@link org.edtp.kavora.compat.sodium.mixin.SodiumMeshingTaskMixin} redirects
 * that piggyback on MC's own block iteration loop.
 *
 * <p>Lifecycle per section:
 * <ol>
 *   <li>{@link #beginSection}: reset state, called at HEAD of build()</li>
 *   <li>{@link #processBlock}: opacity/masks/lights, called per block from the region-state lookup</li>
 *   <li>{@link #captureEvaluatedQuad}: receives block and fluid quads from Sodium</li>
 *   <li>{@link #finishSection}: build SectionBlockData, called at RETURN</li>
 * </ol>
 *
 * <p>One instance per worker thread via {@code ThreadLocal}.
 */
public final class BlockQuadEmitter {

    private static final Direction[] ALL_DIRECTIONS = {
        null, Direction.UP, Direction.DOWN, Direction.NORTH,
        Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    private static final ThreadLocal<BlockQuadEmitter> INSTANCE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> threadNamed =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final DirectQuadWriter writer = new DirectQuadWriter();
    private final BlockPos.MutableBlockPos neighborPos = new BlockPos.MutableBlockPos();
    private final ModelSampling modelSampling = new ModelSampling();
    private final List<LightEntry> lights = new ArrayList<>();
    private final AreaLightMerger lightMerger = new AreaLightMerger();

    private static final int SECTION_SIZE = 16;
    private static final int LOCAL_BLOCK_COUNT = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;
    private final float[] tempPositions = new float[12];
    private final float[] tempUvs = new float[8];
    private final BlockState[] blockStateCache = new BlockState[LOCAL_BLOCK_COUNT];
    private final FluidState[] fluidStateCache = new FluidState[LOCAL_BLOCK_COUNT];

    // Per-section state set by beginSection / processBlock
    /**
     * Whether {@link #beginSection} has run without a matching {@link #finishSection}.
     * A build that aborts before its exit hook leaves it set, so the next build on
     * this thread releases the writer's buffers before it starts.
     */
    private boolean active;
    private BlockTextureManager texManager;
    private BlockStateModelSet blockRenderManager;
    private int baseX, baseY, baseZ;
    private long topMask, bottomMask, eastMask, westMask, southMask, northMask;
    private final long[] opacity = new long[64];
    private final long[] blockStateKnown = new long[64];
    private final long[] fluidStateKnown = new long[64];
    private BlockQuadEmitter() {}

    /** Returns the per-thread instance. */
    public static BlockQuadEmitter threadLocal() {
        BlockQuadEmitter emitter = INSTANCE.get();
        if (emitter == null) {
            emitter = new BlockQuadEmitter();
            INSTANCE.set(emitter);
        }
        return emitter;
    }

    // --- Section lifecycle (called from mixin) ---

    /**
     * Initialises emission state for a new section. Called from the
     * Sodium meshing hook before MC's block loop starts.
     */
    public void beginSection(SectionPos sectionPos,
                             BlockTextureManager tex,
                             BlockStateModelSet brm) {
        if (!threadNamed.get()) {
            RemixAPI.tracyThreadName("Worker " + Thread.currentThread().getName());
            threadNamed.set(Boolean.TRUE);
        }
        if (active) {
            writer.releaseBuffers();
        }
        this.active = true;
        this.texManager = tex;
        this.blockRenderManager = brm;
        this.baseX = sectionPos.minBlockX();
        this.baseY = sectionPos.minBlockY();
        this.baseZ = sectionPos.minBlockZ();

        writer.beginSection(baseX, baseY, baseZ);
        lights.clear();
        topMask = bottomMask = eastMask = westMask = southMask = northMask = 0;
        Arrays.fill(opacity, 0);
        Arrays.fill(blockStateKnown, 0);
        Arrays.fill(fluidStateKnown, 0);
    }

    /**
     * Captures opacity bitmap, boundary face masks, and light-emitting
     * blocks. Called once per block from the isOpaqueFullCube redirect.
     */
    public void processBlock(BlockState state, BlockGetter world, BlockPos pos, boolean opaque) {
        int lx = SectionPos.sectionRelative(pos.getX());
        int ly = SectionPos.sectionRelative(pos.getY());
        int lz = SectionPos.sectionRelative(pos.getZ());

        int bitIdx = (ly << 8) | (lz << 4) | lx;
        cacheLocalBlockState(bitIdx, state);

        long bit = 1L << (bitIdx & 63);
        int word = bitIdx >> 6;
        if (opaque) {
            opacity[word] |= bit;
        } else {
            opacity[word] &= ~bit;
        }

        if (!opaque && (ly == 0 || ly == 15 || lx == 0 || lx == 15 || lz == 0 || lz == 15)) {
            if (ly == 15) topMask    |= 1L << ((lz >> 1) * 8 + (lx >> 1));
            if (ly == 0)  bottomMask |= 1L << ((lz >> 1) * 8 + (lx >> 1));
            if (lx == 15) eastMask   |= 1L << ((ly >> 1) * 8 + (lz >> 1));
            if (lx == 0)  westMask   |= 1L << ((ly >> 1) * 8 + (lz >> 1));
            if (lz == 15) southMask  |= 1L << ((ly >> 1) * 8 + (lx >> 1));
            if (lz == 0)  northMask  |= 1L << ((ly >> 1) * 8 + (lx >> 1));
        }

        if (state.isAir()) return;
        if (!BlockLightRuleManager.hasMatchingRules(state)) return;

        // Use the block position's seed to sample the same model variant as Sodium.
        modelSampling.begin(blockRenderManager, state, state.getSeed(pos), world, pos);
        try {
            lights.addAll(BlockLightRuleManager.resolve(state, pos, modelSampling));
        } finally {
            modelSampling.end();
        }
    }

    /**
     * Finalises the section, producing a {@link SectionBlockData} with
     * all accumulated mesh/light/mask data. Deactivates the emitter.
     */
    public SectionBlockData finishSection(SectionPos sectionPos) {
        try (ProfileScope finishScope = ProfileScope.begin("finishSection")) {
            writer.flushBlock(texManager);
            SectionMeshData meshData = writer.buildMeshData();
            SectionBlockData result = new SectionBlockData(
                    sectionPos, meshData, lightMerger.merge(lights, baseX, baseY, baseZ), topMask, bottomMask,
                    eastMask, westMask, southMask, northMask,
                    Arrays.copyOf(opacity, opacity.length));
            lights.clear();
            return result;
        } finally {
            active = false;
        }
    }

    /** Receives a quad evaluated by Sodium, with unlit ARGB tint. */
    public void captureEvaluatedQuad(float[] positions, float[] uvs, int[] colors,
            TextureAtlasSprite sprite, String layerName,
            BlockAndTintGetter world, BlockPos blockPos, BlockState block, boolean glassLayer) {
        float ax = positions[3] - positions[0], ay = positions[4] - positions[1], az = positions[5] - positions[2];
        float bx = positions[6] - positions[0], by = positions[7] - positions[1], bz = positions[8] - positions[2];
        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 1.0e-6f) return;
        nx /= length; ny /= length; nz /= length;
        Identifier id = sprite == null ? null : sprite.contents().name();
        boolean waterSprite = isWaterSprite(id);
        if (waterSprite || WATER_OVERLAY_SPRITE.equals(id)) {
            if (block != null && block.getFluidState().is(FluidTags.WATER)) {
                Direction face = quadBoundary(positions, blockPos, nx, ny, nz);
                if (face != null && isGlassWaterContact(world, blockPos, face, false)) return;
            }
        }
        if (waterSprite) {
            System.arraycopy(positions, 0, tempPositions, 0, 12);
            emitBlockWaterQuad(nx, ny, nz, layerName, colors);
        } else {
            if (glassLayer) {
                Direction face = quadBoundary(positions, blockPos, nx, ny, nz);
                boolean wet = face != null && BlockTextureManager.isFullGlass(block)
                        && isGlassWaterContact(world, blockPos, face, true);
                for (int i = 0; i < 4; i++) {
                    colors[i] = (colors[i] & 0x00FFFFFF) | (wet ? 0 : 0xFF000000);
                }
            }
            writer.stageColoredQuad(positions, uvs, nx, ny, nz, colors, id,
                    sprite == null ? 0 : sprite.getU0(), sprite == null ? 0 : sprite.getV0(),
                    sprite == null ? 1 : sprite.getU1(), sprite == null ? 1 : sprite.getV1(), layerName);
        }
    }

    /** Closes one block so coplanar overlay classification keeps its scope. */
    public void finishEvaluatedBlock() { writer.flushBlock(texManager); }

    /** Discards a cancelled alternate build without publishing partially evaluated geometry. */
    public void discardSection() {
        writer.releaseBuffers();
        lights.clear();
        active = false;
    }

    private static final Identifier WATER_STILL_SPRITE = Identifier.withDefaultNamespace("block/water_still");
    private static final Identifier WATER_FLOW_SPRITE = Identifier.withDefaultNamespace("block/water_flow");
    private static final Identifier WATER_OVERLAY_SPRITE = Identifier.withDefaultNamespace("block/water_overlay");

    private static Direction quadBoundary(float[] positions, BlockPos pos, float nx, float ny, float nz) {
        // Sodium insets the fluid mesh by 0.001 blocks and can emit both windings.
        final float epsilon = 0.002f;
        int axis;
        int origin;
        Direction minimum, maximum;
        if (Math.abs(nx) > 0.9999f) {
            axis = 0; origin = SectionPos.sectionRelative(pos.getX());
            minimum = Direction.WEST; maximum = Direction.EAST;
        } else if (Math.abs(ny) > 0.9999f) {
            axis = 1; origin = SectionPos.sectionRelative(pos.getY());
            minimum = Direction.DOWN; maximum = Direction.UP;
        } else if (Math.abs(nz) > 0.9999f) {
            axis = 2; origin = SectionPos.sectionRelative(pos.getZ());
            minimum = Direction.NORTH; maximum = Direction.SOUTH;
        } else {
            return null;
        }
        float plane = positions[axis];
        int boundary = Math.round(plane - origin);
        if (boundary < 0 || boundary > 1) return null;
        for (int i = axis; i < positions.length; i += 3) {
            if (Math.abs(positions[i] - origin - boundary) > epsilon) return null;
        }
        return boundary == 0 ? minimum : maximum;
    }

    private boolean isGlassWaterContact(BlockAndTintGetter world, BlockPos pos, Direction face, boolean glassSide) {
        Direction waterToGlass = glassSide ? face.getOpposite() : face;
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        if (glassSide) {
            x += face.getStepX(); y += face.getStepY(); z += face.getStepZ();
        } else if (!BlockTextureManager.isFullGlass(getCachedBlockState(world,
                x + face.getStepX(), y + face.getStepY(), z + face.getStepZ()))) {
            return false;
        }

        FluidState water = getCachedFluidState(world, x, y, z);
        if (!water.is(FluidTags.WATER)) return false;
        if (waterToGlass == Direction.DOWN || getCachedFluidState(world, x, y + 1, z).is(FluidTags.WATER)) return true;

        if (waterToGlass == Direction.UP) {
            return fullWaterCorner(world, x, y, z) && fullWaterCorner(world, x + 1, y, z)
                    && fullWaterCorner(world, x, y, z + 1) && fullWaterCorner(world, x + 1, y, z + 1);
        }
        int edgeX = x + (waterToGlass == Direction.EAST ? 1 : 0);
        int edgeZ = z + (waterToGlass == Direction.SOUTH ? 1 : 0);
        return fullWaterCorner(world, edgeX, y, edgeZ)
                && fullWaterCorner(world, edgeX + Math.abs(waterToGlass.getStepZ()), y,
                        edgeZ + Math.abs(waterToGlass.getStepX()));
    }

    private boolean fullWaterCorner(BlockGetter world, int x, int y, int z) {
        for (int dx = -1; dx <= 0; dx++) {
            for (int dz = -1; dz <= 0; dz++) {
                if (getCachedFluidState(world, x + dx, y, z + dz).is(FluidTags.WATER)
                        && getCachedFluidState(world, x + dx, y + 1, z + dz).is(FluidTags.WATER)) return true;
            }
        }
        return false;
    }

    /** A block-model face painted with the water texture: the cauldron's water level is the vanilla case. */
    static boolean isWaterSprite(Identifier spriteId) {
        return WATER_STILL_SPRITE.equals(spriteId) || WATER_FLOW_SPRITE.equals(spriteId);
    }

    /**
     * Emits the staged quad in {@code tempPositions} as RTX water instead of an
     * atlas-textured face, so a cauldron's water matches the lake outside. The
     * water material tiles in world space, so the UVs come from the vertex
     * positions along the face's dominant axes, exactly as the fluid path maps
     * its top and side faces.
     */
    private void emitBlockWaterQuad(float nx, float ny, float nz, String layerName, int[] colors) {
        float tileSize = BlockTextureManager.getWaterTileSize();
        float ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
        for (int v = 0; v < 4; v++) {
            float wx = baseX + tempPositions[v * 3];
            float wy = baseY + tempPositions[v * 3 + 1];
            float wz = baseZ + tempPositions[v * 3 + 2];
            float u, w;
            if (ay >= ax && ay >= az) {
                u = wx; w = wz;
            } else if (ax >= az) {
                u = wz; w = wy;
            } else {
                u = wx; w = wy;
            }
            tempUvs[v * 2] = u / tileSize;
            tempUvs[v * 2 + 1] = w / tileSize;
        }
        writer.writeColoredFluidQuad(tempPositions, tempUvs, nx, ny, nz, colors,
                null, 0, 0, 1, 1, layerName, texManager);
    }

    private BlockState getCachedBlockState(BlockGetter world, int worldX, int worldY, int worldZ) {
        int lx = worldX - baseX;
        int ly = worldY - baseY;
        int lz = worldZ - baseZ;
        if ((lx | ly | lz) < 0 || lx >= SECTION_SIZE || ly >= SECTION_SIZE || lz >= SECTION_SIZE) {
            neighborPos.set(worldX, worldY, worldZ);
            return world.getBlockState(neighborPos);
        }

        int idx = localIndex(lx, ly, lz);
        int word = idx >> 6;
        long bit = 1L << (idx & 63);
        if ((blockStateKnown[word] & bit) != 0) {
            return blockStateCache[idx];
        }

        neighborPos.set(worldX, worldY, worldZ);
        BlockState state = world.getBlockState(neighborPos);
        cacheLocalBlockState(idx, state);
        return state;
    }

    private FluidState getCachedFluidState(BlockGetter world, int worldX, int worldY, int worldZ) {
        int lx = worldX - baseX;
        int ly = worldY - baseY;
        int lz = worldZ - baseZ;
        if ((lx | ly | lz) < 0 || lx >= SECTION_SIZE || ly >= SECTION_SIZE || lz >= SECTION_SIZE) {
            neighborPos.set(worldX, worldY, worldZ);
            return world.getFluidState(neighborPos);
        }

        int idx = localIndex(lx, ly, lz);
        int word = idx >> 6;
        long bit = 1L << (idx & 63);
        if ((fluidStateKnown[word] & bit) != 0) {
            return fluidStateCache[idx];
        }

        FluidState fluidState = getCachedBlockState(world, worldX, worldY, worldZ).getFluidState();
        cacheLocalFluidState(idx, fluidState);
        return fluidState;
    }

    private void cacheLocalBlockState(int idx, BlockState state) {
        blockStateCache[idx] = state;
        blockStateKnown[idx >> 6] |= 1L << (idx & 63);
    }

    private void cacheLocalFluidState(int idx, FluidState fluidState) {
        fluidStateCache[idx] = fluidState;
        fluidStateKnown[idx >> 6] |= 1L << (idx & 63);
    }

    private static int localIndex(int lx, int ly, int lz) {
        return (ly << 8) | (lz << 4) | lx;
    }

    /**
     * The terrain layer a block's quads are bucketed under, by
     * {@link BlockTextureManager#layerType} name. A held block item asks the same
     * question, so it shades as its placed counterpart does.
     */
    public static String classifyBlockLayer(BlockState state) {
        String layerOverride = BlockTextureManager.glassLayerOverride(state);
        if (layerOverride != null) return layerOverride;
        BlockStateModel model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
        LayerSampler sampler = LAYER_SAMPLER.get();
        sampler.parts.clear();
        sampler.random.setSeed(42L);
        model.collectParts(sampler.random, sampler.parts);
        String layer = "solid";
        for (int p = 0; p < sampler.parts.size(); p++) {
            BlockStateModelPart part = sampler.parts.get(p);
            for (Direction direction : ALL_DIRECTIONS) {
                List<BakedQuad> quads = part.getQuads(direction);
                for (int q = 0; q < quads.size(); q++) {
                    ChunkSectionLayer quadLayer = quads.get(q).materialInfo().layer();
                    if (quadLayer == ChunkSectionLayer.TRANSLUCENT) return "translucent";
                    if (quadLayer == ChunkSectionLayer.CUTOUT) layer = "cutout";
                }
            }
        }
        return layer;
    }

    private static final class LayerSampler {
        final List<BlockStateModelPart> parts = new ArrayList<>();
        final SingleThreadedRandomSource random = new SingleThreadedRandomSource(42L);
    }
    private static final ThreadLocal<LayerSampler> LAYER_SAMPLER = ThreadLocal.withInitial(LayerSampler::new);

}
