/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.light;

import org.edtp.kavora.block.SpotLightBlock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.CoralFanBlock;
import net.minecraft.world.level.block.CoralPlantBlock;
import net.minecraft.world.level.block.CoralWallFanBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.RedstoneWallTorchBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Built-in block light rules. Resource packs can override these by using the same rule path.
 */
final class BlockLightDefaults {
    static final float DEFAULT_AUTHORED_INTENSITY_MULTIPLIER = 5.0f;
    static final float TORCH_AUTHORED_INTENSITY_MULTIPLIER = 7.5f;
    static final float CAMPFIRE_AUTHORED_INTENSITY_MULTIPLIER = 7.5f;
    static final float THIN_SPHERE_RADIUS_SCALE = 0.4f;
    static final float THIN_SPHERE_INTENSITY_SCALE = 3.0f;
    /** Pre-scale emitter radius of a torch's flame, shared with the light reach calibration. */
    static final float TORCH_RADIUS = 0.2f;
    private static final float AMETHYST_INTENSITY_SCALE = 2.0f;
    private static final Direction[] FACES = Direction.values();
    private static final float FACE_LIGHT_OFFSET = 1.0f / 1024.0f;
    private static final float LICHEN_FILL_POWER = 0.09f;
    private static final float LICHEN_FILL_STANDOFF = 0.4375f;
    private static final float LANTERN_RADIUS = .12f;
    private static final float CORAL_LIGHT_RADIUS = .43f;
    private static final float CORAL_DIFFUSE_INTENSITY = 6f;
    static final LightEntry.ColorOverride TORCH_COLOR = LightEntry.ColorOverride.of(1.0f, 0.35f, 0.1f);
    private static final LightEntry.ColorOverride CAMPFIRE_COLOR = LightEntry.ColorOverride.of(1.0f, 0.1f, 0.05f);
    private static final LightEntry.ColorOverride SOUL_COLOR = LightEntry.ColorOverride.of(0.05f, 0.2f, 1.0f);
    private static final LightEntry.ColorOverride COPPER_COLOR = LightEntry.ColorOverride.of(0.1f, 1.0f, 0.35f);
    private static final LightEntry.ColorOverride REDSTONE_COLOR = LightEntry.ColorOverride.of(1.0f, 0.15f, 0.02f);
    private static final LightEntry.ColorOverride CONDUIT_COLOR = LightEntry.ColorOverride.of(0.45f, 0.9f, 1.0f);
    private static final LightEntry.ColorOverride AMETHYST_COLOR = LightEntry.ColorOverride.of(0.65f, 0.35f, 1.0f);

    // Shared beacon geometry and path-specific radiance tuning live in
    // BeaconBeam; the block_display lighthouse remains independent.
    // Beam colour is per-variant and lives on the block (SpotLightBlock.beamColor).

    private BlockLightDefaults() {}

    static Map<Identifier, BlockLightRuleManager.Rule> create() {
        LinkedHashMap<Identifier, BlockLightRuleManager.Rule> rules = new LinkedHashMap<>();
        // Built as locals, not static fields: a static field touching Blocks
        // would force the block registry to bootstrap just to load this class,
        // even for a caller that only wants a constant such as TORCH_COLOR.
        Set<Block> candles = candleBlocks();
        Set<Block> candleCakes = candleCakeBlocks();
        add(rules, "torch", exact(Blocks.TORCH),
                (id, state, pos, centroid) -> List.of(thinSphere(id, 0, state, pos, 0.5f, 0.7f, 0.5f, TORCH_RADIUS, TORCH_COLOR)),
                TORCH_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "soul_torch", exact(Blocks.SOUL_TORCH),
                (id, state, pos, centroid) -> List.of(thinSphere(id, 0, state, pos, 0.5f, 0.7f, 0.5f, TORCH_RADIUS, SOUL_COLOR)),
                TORCH_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "copper_torch", exact(Blocks.COPPER_TORCH),
                (id, state, pos, centroid) -> List.of(thinSphere(id, 0, state, pos, 0.5f, 0.7f, 0.5f, TORCH_RADIUS, COPPER_COLOR)),
                TORCH_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "wall_torch", exact(Blocks.WALL_TORCH),
                (id, state, pos, centroid) -> List.of(wallTorch(id, state, pos, TORCH_COLOR)),
                TORCH_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "soul_wall_torch", exact(Blocks.SOUL_WALL_TORCH),
                (id, state, pos, centroid) -> List.of(wallTorch(id, state, pos, SOUL_COLOR)),
                TORCH_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "copper_wall_torch", exact(Blocks.COPPER_WALL_TORCH),
                (id, state, pos, centroid) -> List.of(wallTorch(id, state, pos, COPPER_COLOR)),
                TORCH_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "redstone_torch", state -> state.is(Blocks.REDSTONE_TORCH) && state.getValue(RedstoneTorchBlock.LIT),
                (id, state, pos, centroid) -> List.of(thinSphere(id, 0, state, pos, 0.5f, 0.7f, 0.5f, 0.16f, REDSTONE_COLOR)),
                TORCH_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "redstone_wall_torch", state -> state.is(Blocks.REDSTONE_WALL_TORCH) && state.getValue(RedstoneWallTorchBlock.LIT),
                (id, state, pos, centroid) -> List.of(wallTorch(id, state, pos, REDSTONE_COLOR)),
                TORCH_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "small_amethyst_bud", exact(Blocks.SMALL_AMETHYST_BUD),
                (id, state, pos, centroid) -> List.of(amethystCrystal(id, state, pos, 3.0f, 0.08f, 1)));
        add(rules, "medium_amethyst_bud", exact(Blocks.MEDIUM_AMETHYST_BUD),
                (id, state, pos, centroid) -> List.of(amethystCrystal(id, state, pos, 4.0f, 0.10f, 2)));
        add(rules, "large_amethyst_bud", exact(Blocks.LARGE_AMETHYST_BUD),
                (id, state, pos, centroid) -> List.of(amethystCrystal(id, state, pos, 5.0f, 0.13f, 4)));
        add(rules, "amethyst_cluster", exact(Blocks.AMETHYST_CLUSTER),
                (id, state, pos, centroid) -> List.of(amethystCrystal(id, state, pos, 7.0f, 0.18f, 5)));
        add(rules, "campfire", state -> state.is(Blocks.CAMPFIRE) && state.getValue(BlockStateProperties.LIT),
                (id, state, pos, centroid) -> List.of(sphere(id, 0, state, pos, 0.5f, 0.5f, 0.5f, 0.25f, CAMPFIRE_COLOR, null)),
                CAMPFIRE_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "soul_campfire", state -> state.is(Blocks.SOUL_CAMPFIRE) && state.getValue(BlockStateProperties.LIT),
                (id, state, pos, centroid) -> List.of(sphere(id, 0, state, pos, 0.5f, 0.5f, 0.5f, 0.25f, SOUL_COLOR, null)),
                CAMPFIRE_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "conduit", exact(Blocks.CONDUIT),
                (id, state, pos, centroid) -> List.of(sphere(id, 0, state, pos, 0.5f, 0.5f, 0.5f, 0.1875f, CONDUIT_COLOR, null)));
        add(rules, "light", state -> state.is(Blocks.LIGHT) && state.getValue(BlockStateProperties.LEVEL) > 0,
                (id, state, pos, centroid) -> List.of(sphere(id, 0, state, pos, 0.5f, 0.5f, 0.5f, 0.2f, LightEntry.ColorOverride.of(1, 1, 1), null)));
        add(rules, "candles", state -> candles.contains(state.getBlock()) && state.getValue(CandleBlock.LIT),
                BlockLightDefaults::candles);
        add(rules, "candle_cakes", state -> candleCakes.contains(state.getBlock()) && state.getValue(BlockStateProperties.LIT),
                (id, state, pos, centroid) -> List.of(thinSphere(id, 0, state, pos, 0.5f, 1.0f, 0.5f, 0.08f, null)));
        Set<Block> panels = panelBlocks();
        add(rules, "glowing_blocks", state -> state.getLightEmission() > 0 && panels.contains(state.getBlock()),
                BlockLightDefaults::surfaceEmitter, 1.0f);
        add(rules, "lanterns", state -> state.getLightEmission() > 0 && state.getBlock() instanceof LanternBlock,
                BlockLightDefaults::lantern, 1.0f);
        add(rules, "end_rod", exact(Blocks.END_ROD),
                (id, state, pos, sampler) -> List.of(endRod(id, state, pos)), 1.0f);
        add(rules, "glow_berries", state -> state.getLightEmission() > 0
                        && (state.is(Blocks.CAVE_VINES) || state.is(Blocks.CAVE_VINES_PLANT)),
                (id, state, pos, sampler) -> List.of(sphere(id, 0, state, pos, .5f, .5f, .5f,
                        .1f, LightEntry.ColorOverride.of(1, .55f, .12f),
                        torchMatchedIntensity(state.getLightEmission(), 4 * (float) Math.PI * .01f))), 1.0f);
        add(rules, "sea_pickles", state -> state.is(Blocks.SEA_PICKLE) && state.getLightEmission() > 0,
                (id, state, pos, sampler) -> List.of(sphere(id, 0, state, pos, .5f, .35f, .5f,
                        .1f, LightEntry.ColorOverride.of(.65f, 1, .4f),
                        torchMatchedIntensity(state.getLightEmission(), 4 * (float) Math.PI * .01f))), 1.0f);
        add(rules, "coral", state -> state.getBlock() instanceof CoralPlantBlock
                        || state.getBlock() instanceof CoralFanBlock || state.getBlock() instanceof CoralWallFanBlock,
                BlockLightDefaults::coral, 1.0f);
        Set<BlockState> surfaceEmitters = new java.util.HashSet<>();
        for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
            // Fluid surfaces emit through the material on their captured geometry.
            Block block = state.getBlock();
            if (state.getLightEmission() <= 0 || block instanceof SpotLightBlock || block instanceof LiquidBlock) continue;
            if (rules.values().stream().noneMatch(rule -> rule.matches(state))) surfaceEmitters.add(state);
        }
        add(rules, "surface_emitters", surfaceEmitters::contains, BlockLightDefaults::surfaceEmitter, 1.0f);
        BlockLightIntensityControls.validateBuiltInRules(rules.keySet());
        return Collections.unmodifiableMap(new LinkedHashMap<>(rules));
    }

    private static void add(Map<Identifier, BlockLightRuleManager.Rule> rules,
                            String path,
                            Predicate<BlockState> matcher,
                            BlockLightRuleManager.Resolver resolver) {
        add(rules, path, matcher, resolver, DEFAULT_AUTHORED_INTENSITY_MULTIPLIER);
    }

    private static void add(Map<Identifier, BlockLightRuleManager.Rule> rules,
                            String path,
                            Predicate<BlockState> matcher,
                            BlockLightRuleManager.Resolver resolver,
                            float authoredIntensityMultiplier) {
        Identifier id = BlockLightRuleIds.builtIn(path);
        add(rules, id, matcher, resolver, authoredIntensityMultiplier);
    }

    private static void add(Map<Identifier, BlockLightRuleManager.Rule> rules,
                            Identifier id,
                            Predicate<BlockState> matcher,
                            BlockLightRuleManager.Resolver resolver,
                            float authoredIntensityMultiplier) {
        rules.put(id, BlockLightRuleManager.rule(
                id,
                matcher,
                (ruleId, state, pos, sampler) -> resolver
                        .resolve(ruleId, state, pos, sampler)
                        .stream()
                        .map(entry -> entry.withAuthoredIntensityMultiplier(
                                authoredIntensityMultiplier))
                        .toList()));
    }

    private static Predicate<BlockState> exact(Block block) {
        return state -> state.is(block);
    }

    private static LightEntry sphere(Identifier id, int index, BlockState state, BlockPos pos,
                                     float x, float y, float z, float radius,
                                     LightEntry.ColorOverride color, Float intensity) {
        return BlockLightRuleManager.sphere(id, index, state, pos, x, y, z, radius, color, intensity);
    }

    private static LightEntry thinSphere(Identifier id, int index, BlockState state, BlockPos pos,
                                         float x, float y, float z, float radius,
                                         LightEntry.ColorOverride color) {
        float intensity = state.getLightEmission()
                * BlockLightRuleManager.RADIANCE_PER_LIGHT_LEVEL
                * THIN_SPHERE_INTENSITY_SCALE;
        return sphere(id, index, state, pos, x, y, z, radius * THIN_SPHERE_RADIUS_SCALE, color, intensity);
    }

    private static LightEntry wallTorch(Identifier id, BlockState state, BlockPos pos,
                                        LightEntry.ColorOverride color) {
        Direction facing = state.getValue(WallTorchBlock.FACING).getOpposite();
        return thinSphere(id, 0, state, pos,
                0.5f + 0.27f * facing.getStepX(),
                0.92f,
                0.5f + 0.27f * facing.getStepZ(),
                0.2f, color);
    }

    private static LightEntry endRod(Identifier id, BlockState state, BlockPos pos) {
        Direction facing = state.getValue(BlockStateProperties.FACING);
        BlockLightRuleManager.Vec3 axis = BlockLightRuleManager.Vec3.fromAxis(facing.getAxis());
        float radius = .0625f, length = .875f;
        return BlockLightRuleManager.cylinder(id, 0, state, pos,
                .5f, .5f, .5f, radius, axis, length, null,
                torchMatchedIntensity(state.getLightEmission(), 2 * (float) Math.PI * radius * length));
    }

    /** Matches emitted power to the torch reference rather than the visible texel brightness. */
    static float torchMatchedIntensity(int lightLevel, float area) {
        return LightPower.torchReference() * (lightLevel / 14.0f) / (area * (float) Math.PI);
    }

    private static List<LightEntry> surfaceEmitter(Identifier id, BlockState state, BlockPos pos,
                                                  BlockLightRuleManager.ModelSampler sampler) {
        if (state.is(Blocks.GLOW_LICHEN)) return lichenFill(id, state, pos);

        Direction only = (state.is(Blocks.JACK_O_LANTERN)
                || state.getBlock() instanceof net.minecraft.world.level.block.AbstractFurnaceBlock)
                ? state.getValue(BlockStateProperties.HORIZONTAL_FACING) : null;
        int exposed = 0;
        for (Direction face : FACES)
            if ((only == null || face == only) && sampler.isFaceExposed(face)) exposed |= 1 << face.ordinal();
        if (exposed == 0) return List.of();
        var bounds = sampler.bounds();
        float width = bounds.maxX() - bounds.minX();
        float height = bounds.maxY() - bounds.minY();
        float depth = bounds.maxZ() - bounds.minZ();
        float area = 2 * (width * height + width * depth + height * depth);
        if (only != null) area = only.getAxis() == Direction.Axis.X ? depth * height : width * height;
        if (!(area > 0)) return List.of();
        float intensity = torchMatchedIntensity(state.getLightEmission(), area);
        double cx = pos.getX() + (bounds.minX() + bounds.maxX()) * .5;
        float cy = pos.getY() + (bounds.minY() + bounds.maxY()) * .5f;
        double cz = pos.getZ() + (bounds.minZ() + bounds.maxZ()) * .5;
        var lights = new ArrayList<LightEntry>(6);
        for (Direction face : FACES) {
            if ((exposed & (1 << face.ordinal())) == 0) continue;
            var axis = face.getAxis();
            float u = axis == Direction.Axis.X ? depth : width;
            float v = axis == Direction.Axis.Y ? depth : height;
            if (u <= 0 || v <= 0) continue;
            float nx = face.getStepX(), ny = face.getStepY(), nz = face.getStepZ();
            lights.add(faceRectangle(id, state, pos, face,
                    cx + nx * (width * .5f + FACE_LIGHT_OFFSET),
                    cy + ny * (height * .5f + FACE_LIGHT_OFFSET),
                    cz + nz * (depth * .5f + FACE_LIGHT_OFFSET), u, v, intensity,
                    LightEntry.defaultVolumetricRadianceScale()));
        }
        return lights;
    }

    private static List<LightEntry> lichenFill(Identifier id, BlockState state, BlockPos pos) {
        Set<Direction> faces = MultifaceBlock.availableFaces(state);
        if (faces.isEmpty()) return List.of();

        // A weak unit rectangle fills the support plane behind the cutout. Adjacent faces can merge.
        float intensity = LICHEN_FILL_POWER * torchMatchedIntensity(state.getLightEmission(), faces.size());
        float offset = 0.5f - LICHEN_FILL_STANDOFF;
        var lights = new ArrayList<LightEntry>(faces.size());
        for (Direction face : faces) {
            lights.add(faceRectangle(id, state, pos, face,
                    pos.getX() + 0.5 + face.getStepX() * offset,
                    pos.getY() + 0.5f + face.getStepY() * offset,
                    pos.getZ() + 0.5 + face.getStepZ() * offset, 1.0f, 1.0f, intensity, 0.0f));
        }
        return lights;
    }

    private static LightEntry faceRectangle(Identifier id, BlockState state, BlockPos pos, Direction face,
                                            double x, float y, double z, float u, float v, float intensity,
                                            float volumetricRadianceScale) {
        var axis = face.getAxis();
        return LightEntry.rect(BlockLightRuleManager.key(pos, id, face.ordinal()),
                state.getLightEmission(), state.getBlock(), x, y, z,
                axis == Direction.Axis.X ? 0 : 1, 0, axis == Direction.Axis.X ? 1 : 0, u,
                0, axis == Direction.Axis.Y ? 0 : 1, axis == Direction.Axis.Y ? 1 : 0, v,
                face.getStepX(), face.getStepY(), face.getStepZ(), null, intensity, volumetricRadianceScale);
    }

    private static List<LightEntry> lantern(Identifier id, BlockState state, BlockPos pos,
                                           BlockLightRuleManager.ModelSampler sampler) {
        boolean hanging = state.getValue(BlockStateProperties.HANGING);
        return List.of(sphere(id, 0, state, pos, .5f, hanging ? .375f : .3125f, .5f,
                LANTERN_RADIUS, null,
                torchMatchedIntensity(state.getLightEmission(), 4 * (float) Math.PI * LANTERN_RADIUS * LANTERN_RADIUS)));
    }

    private static List<LightEntry> coral(Identifier id, BlockState state, BlockPos pos,
                                          BlockLightRuleManager.ModelSampler sampler) {
        var emission = sampler.diffuseEmission();
        if (emission == null) return List.of();
        float[] center = sampler.centroid();
        var offset = state.getOffset(pos);
        float radius = CORAL_LIGHT_RADIUS;
        // Keep the center above the fronds so the finite emitter spreads light
        // onto the seabed without concentrating it at the coral's center.
        float height = Math.max(.82f, center[1] + (float) offset.y + radius);
        return List.of(LightEntry.sphere(BlockLightRuleManager.key(pos, id, 0), 0, state.getBlock(),
                pos.getX() + Math.clamp(center[0] + offset.x, radius, 1 - radius),
                pos.getY() + height,
                pos.getZ() + Math.clamp(center[2] + offset.z, radius, 1 - radius),
                radius, LightEntry.ColorOverride.of(emission.r(), emission.g(), emission.b()),
                CORAL_DIFFUSE_INTENSITY, 0f));
    }

    private static Set<Block> panelBlocks() {
        var blocks = new java.util.HashSet<>(Set.of(Blocks.GLOWSTONE, Blocks.SEA_LANTERN,
                Blocks.SHROOMLIGHT, Blocks.JACK_O_LANTERN, Blocks.REDSTONE_LAMP,
                Blocks.OCHRE_FROGLIGHT, Blocks.VERDANT_FROGLIGHT, Blocks.PEARLESCENT_FROGLIGHT,
                Blocks.CRYING_OBSIDIAN));
        blocks.addAll(Blocks.COPPER_BULB.asList());
        return Set.copyOf(blocks);
    }

    private static LightEntry amethystCrystal(Identifier id, BlockState state, BlockPos pos,
                                              float heightPixels, float radius, int lightLevel) {
        Direction facing = state.getValue(BlockStateProperties.FACING);
        float halfHeight = heightPixels / 32.0f;
        float offsetFromCenter = 0.5f - halfHeight;
        float intensity = lightLevel
                * BlockLightRuleManager.RADIANCE_PER_LIGHT_LEVEL
                * AMETHYST_INTENSITY_SCALE;
        return sphere(id, 0, state, pos,
                0.5f - facing.getStepX() * offsetFromCenter,
                0.5f - facing.getStepY() * offsetFromCenter,
                0.5f - facing.getStepZ() * offsetFromCenter,
                radius, AMETHYST_COLOR, intensity);
    }

    private static List<LightEntry> candles(Identifier id, BlockState state, BlockPos pos,
                                            BlockLightRuleManager.ModelSampler centroid) {
        List<LightEntry> entries = new ArrayList<>(state.getValue(CandleBlock.CANDLES));
        float[][] offsets = candleOffsets(state.getValue(CandleBlock.CANDLES));
        for (int i = 0; i < offsets.length; i++) {
            float[] o = offsets[i];
            entries.add(thinSphere(id, i, state, pos, o[0], o[1], o[2], 0.06f, null));
        }
        return entries;
    }

    private static Set<Block> candleBlocks() {
        var blocks = new java.util.HashSet<Block>(Blocks.DYED_CANDLE.asList());
        blocks.add(Blocks.CANDLE);
        return Set.copyOf(blocks);
    }

    private static Set<Block> candleCakeBlocks() {
        var blocks = new java.util.HashSet<Block>(Blocks.DYED_CANDLE_CAKE.asList());
        blocks.add(Blocks.CANDLE_CAKE);
        return Set.copyOf(blocks);
    }

    private static float[][] candleOffsets(int candles) {
        return switch (candles) {
            case 1 -> new float[][]{{0.5f, 0.5f, 0.5f}};
            case 2 -> new float[][]{{0.375f, 0.44f, 0.5f}, {0.625f, 0.5f, 0.44f}};
            case 3 -> new float[][]{{0.5f, 0.313f, 0.625f}, {0.375f, 0.44f, 0.5f}, {0.56f, 0.5f, 0.44f}};
            default -> new float[][]{{0.44f, 0.313f, 0.56f}, {0.625f, 0.44f, 0.56f}, {0.375f, 0.44f, 0.375f}, {0.56f, 0.5f, 0.375f}};
        };
    }
}
