/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import java.util.Map;
import net.minecraft.resources.Identifier;

/**
 * Default material detail for static block sprites missing authored channels: surface detail, a roughness
 * that suits the material, and real metals.
 *
 * <p>Applies only where a texture has no LabPBR sidecar of its own, so an
 * installed PBR pack always wins. A normal map is derived from the albedo's
 * luminance, the way shader packs do it without a pack: darker texels sit
 * lower, so cracks, grain, and block seams catch light. Roughness and
 * metalness come from the block's name: metal blocks reflect as metal, gems,
 * ice, polished stone, and glazed terracotta are glossy dielectrics, and
 * natural blocks stay rough. Within a texture, darker texels are a little
 * rougher, which breaks up reflections the way wear does.
 */
public final class AutoPbr {

    public static final boolean DEFAULT_ENABLED = true;

    /** How a block's surface is treated. Roughness is perceptual, 0 to 1. */
    record Surface(float roughness, boolean metal, float normalStrength, float roughnessVariation) {}

    static final Surface NATURAL = new Surface(0.82f, false, 1.6f, 0.12f);
    private static final Surface WOOD = new Surface(0.70f, false, 1.4f, 0.12f);
    private static final Surface LOOSE = new Surface(0.92f, false, 1.8f, 0.06f);
    private static final Surface SOFT = new Surface(0.95f, false, 1.0f, 0.04f);
    private static final Surface FOLIAGE = new Surface(0.80f, false, 0.8f, 0.10f);

    private static final Surface POLISHED_METAL = new Surface(0.16f, true, 0.35f, 0.08f);
    private static final Surface METAL = new Surface(0.16f, true, 0.15f, 0.04f);
    private static final Surface COPPER = new Surface(0.28f, true, 0.4f, 0.10f);
    private static final Surface TARNISHED_COPPER = new Surface(0.45f, true, 0.6f, 0.12f);
    private static final Surface PATINA = new Surface(0.72f, false, 1.0f, 0.10f);
    private static final Surface RAW_METAL = new Surface(0.55f, true, 1.4f, 0.12f);
    private static final Surface IRONWORK = new Surface(0.35f, true, 0.6f, 0.10f);

    private static final Surface GEM = new Surface(0.06f, false, 0.3f, 0.04f);
    private static final Surface GLOSSY = new Surface(0.20f, false, 0.4f, 0.06f);
    private static final Surface ICE = new Surface(0.05f, false, 0.2f, 0.03f);
    private static final Surface POLISHED = new Surface(0.36f, false, 0.6f, 0.08f);
    private static final Surface SMOOTH = new Surface(0.48f, false, 0.8f, 0.08f);

    /** Exact block texture names. */
    private static final Map<String, Surface> EXACT = Map.ofEntries(
            Map.entry("gold_block", POLISHED_METAL),
            Map.entry("iron_block", METAL),
            Map.entry("netherite_block", new Surface(0.30f, true, 0.4f, 0.10f)),
            Map.entry("raw_iron_block", RAW_METAL),
            Map.entry("raw_gold_block", RAW_METAL),
            Map.entry("raw_copper_block", RAW_METAL),
            Map.entry("diamond_block", GEM),
            Map.entry("emerald_block", new Surface(0.08f, false, 0.3f, 0.04f)),
            Map.entry("lapis_block", new Surface(0.25f, false, 0.5f, 0.06f)),
            Map.entry("amethyst_block", GLOSSY),
            Map.entry("budding_amethyst", GLOSSY),
            Map.entry("redstone_block", new Surface(0.30f, false, 0.6f, 0.08f)),
            Map.entry("ice", ICE),
            Map.entry("packed_ice", new Surface(0.12f, false, 0.3f, 0.04f)),
            Map.entry("blue_ice", new Surface(0.08f, false, 0.25f, 0.03f)),
            Map.entry("obsidian", new Surface(0.12f, false, 0.4f, 0.05f)),
            Map.entry("crying_obsidian", new Surface(0.12f, false, 0.4f, 0.05f)),
            Map.entry("smooth_stone", SMOOTH),
            Map.entry("smooth_stone_slab_side", SMOOTH),
            Map.entry("calcite", new Surface(0.55f, false, 1.0f, 0.08f)),
            Map.entry("honey_block_side", GLOSSY),
            Map.entry("slime_block", GLOSSY),
            Map.entry("iron_bars", IRONWORK),
            Map.entry("chain", IRONWORK),
            Map.entry("iron_trapdoor", IRONWORK),
            Map.entry("iron_door_top", IRONWORK),
            Map.entry("iron_door_bottom", IRONWORK),
            Map.entry("heavy_core", IRONWORK),
            Map.entry("lightning_rod", COPPER));

    private AutoPbr() {}

    /**
     * Surface for a block texture, or null for textures auto PBR leaves alone: animated
     * sprites and liquids, whose appearance is supplied by authored material maps.
     */
    static Surface surface(Identifier spriteId, boolean animated) {
        if (animated) return null;
        String path = spriteId.getPath();
        String name = path.startsWith("block/") ? path.substring("block/".length()) : path;
        if (name.contains("water") || name.contains("lava") || name.contains("fire")
                || name.contains("portal")) {
            return null;
        }
        Surface exact = EXACT.get(name);
        if (exact != null) return exact;

        if (name.contains("copper")) {
            if (name.startsWith("oxidized_") || name.startsWith("waxed_oxidized_")
                    || name.startsWith("weathered_") || name.startsWith("waxed_weathered_")) {
                return PATINA;
            }
            if (name.startsWith("exposed_") || name.startsWith("waxed_exposed_")) {
                return TARNISHED_COPPER;
            }
            return COPPER;
        }
        if (name.startsWith("anvil") || name.startsWith("chipped_anvil") || name.startsWith("damaged_anvil")
                || name.startsWith("cauldron") || name.startsWith("hopper")) {
            return IRONWORK;
        }
        if (name.endsWith("_glazed_terracotta")) return GLOSSY;
        if (name.startsWith("quartz") || name.startsWith("smooth_quartz") || name.startsWith("chiseled_quartz")) {
            return new Surface(0.30f, false, 0.6f, 0.06f);
        }
        if (name.startsWith("polished_")) return POLISHED;
        if (name.endsWith("_concrete")) return new Surface(0.45f, false, 0.6f, 0.06f);
        if (name.startsWith("prismarine") || name.startsWith("dark_prismarine")) return SMOOTH;
        if (name.contains("leaves") || name.contains("grass") || name.contains("fern") || name.contains("vine")) {
            return FOLIAGE;
        }
        if (name.contains("planks") || name.contains("log") || name.contains("wood") || name.contains("stem")
                || name.contains("hyphae")) {
            return WOOD;
        }
        if (name.contains("sand") || name.contains("gravel") || name.contains("dirt") || name.contains("mud")
                || name.contains("soul_soil") || name.contains("powder")) {
            return LOOSE;
        }
        if (name.endsWith("_wool") || name.endsWith("_carpet") || name.contains("snow")) return SOFT;
        return NATURAL;
    }

    /**
     * Writes a luminance-derived normal into an RGBA normal atlas region (LabPBR layout:
     * x in red, y in green, DirectX orientation). Neighbours wrap inside the region, since
     * block textures tile; fully transparent texels keep a flat normal.
     */
    static void writeNormal(byte[] albedoRGBA, byte[] normalAtlas, int atlasW,
                            int ox, int oy, int w, int h, float strength) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = ((oy + y) * atlasW + ox + x) * 4;
                if ((albedoRGBA[idx + 3] & 0xFF) == 0) continue;
                float left = luminance(albedoRGBA, ((oy + y) * atlasW + ox + Math.floorMod(x - 1, w)) * 4);
                float right = luminance(albedoRGBA, ((oy + y) * atlasW + ox + Math.floorMod(x + 1, w)) * 4);
                float up = luminance(albedoRGBA, ((oy + Math.floorMod(y - 1, h)) * atlasW + ox + x) * 4);
                float down = luminance(albedoRGBA, ((oy + Math.floorMod(y + 1, h)) * atlasW + ox + x) * 4);
                float nx = -(right - left) * strength;
                float ny = -(down - up) * strength;
                float length = (float) Math.sqrt(nx * nx + ny * ny + 1.0f);
                normalAtlas[idx] = (byte) toByte(nx / length * 0.5f + 0.5f);
                normalAtlas[idx + 1] = (byte) toByte(ny / length * 0.5f + 0.5f);
                normalAtlas[idx + 2] = (byte) toByte(1.0f / length);
                normalAtlas[idx + 3] = (byte) 255;
            }
        }
    }

    /** Writes roughness (perceptual, as the atlas holds it) and metalness for an atlas region. */
    static void writeMaterial(byte[] albedoRGBA, byte[] roughnessAtlas, byte[] metallicAtlas, int atlasW,
                              int ox, int oy, int w, int h, Surface surface) {
        float mean = 0.0f;
        int count = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = ((oy + y) * atlasW + ox + x) * 4;
                if ((albedoRGBA[idx + 3] & 0xFF) == 0) continue;
                mean += luminance(albedoRGBA, idx);
                count++;
            }
        }
        mean = count > 0 ? mean / count : 0.5f;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pixel = (oy + y) * atlasW + ox + x;
                float lum = luminance(albedoRGBA, pixel * 4);
                float roughness = surface.roughness() + surface.roughnessVariation() * (mean - lum) * 2.0f;
                roughnessAtlas[pixel] = (byte) toByte(Math.max(0.02f, Math.min(1.0f, roughness)));
                metallicAtlas[pixel] = (byte) (surface.metal() ? 255 : 0);
            }
        }
    }

    private static float luminance(byte[] rgba, int idx) {
        return (0.2126f * (rgba[idx] & 0xFF) + 0.7152f * (rgba[idx + 1] & 0xFF) + 0.0722f * (rgba[idx + 2] & 0xFF))
                / 255.0f;
    }

    private static int toByte(float value) {
        return Math.round(Math.max(0.0f, Math.min(1.0f, value)) * 255.0f);
    }
}
