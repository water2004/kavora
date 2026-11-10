/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.texture;

import com.mojang.blaze3d.platform.NativeImage;
import org.edtp.kavora.sdk.InstanceInfoBlendEXT;
import org.edtp.kavora.sdk.MaterialInfo;
import org.edtp.kavora.sdk.MaterialInfoOpaqueEXT;
import org.edtp.kavora.sdk.MaterialInfoOpaqueSubsurfaceEXT;
import org.edtp.kavora.sdk.MaterialInfoTranslucentEXT;
import org.edtp.kavora.sdk.RemixAPI;
import org.edtp.kavora.sdk.RemixException;
import org.edtp.kavora.sdk.TextureFormat;
import org.edtp.kavora.sdk.TexturePurpose;
import org.edtp.kavora.asset.KavoraMaterial;
import org.edtp.kavora.asset.KavoraMaterialStore;
import org.edtp.kavora.asset.KavoraTexture;
import org.edtp.kavora.asset.KavoraTextureStore;
import org.edtp.kavora.mixin.SpriteAtlasAccessor;
import org.edtp.kavora.mixin.SpriteContentsAccessor;
import org.edtp.kavora.profiling.ProfileScope;
import org.edtp.kavora.util.DirectBufferPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Shared atlas textures, materials and per-sprite metadata for Minecraft terrain.
 *
 * <p>Near and distant terrain sample the same atlas and LabPBR channels, including POM and SSS.
 */
public class BlockTextureManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(BlockTextureManager.class);
    public static final Identifier BLOCK_ATLAS_ID = Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png");
    /** Blend state for draw calls using the atlas cutout material, which sets useDrawCallAlphaState=true. */
    public static final InstanceInfoBlendEXT ATLAS_CUTOUT_BLEND =
            InstanceInfoBlendEXT.textureTimesVertexColorAlphaTest(128);
    /** Blend state for draw calls using the atlas translucent material, as terrain pairs its translucent pages. */
    public static final InstanceInfoBlendEXT ATLAS_TRANSLUCENT_BLEND =
            InstanceInfoBlendEXT.textureTimesVertexColor();
    private static final float POM_DISPLACE_IN = 0.25f;
    private static final float COMMON_GLASS_IOR = 1.52f;
    private static final float GLASS_TRANSMITTANCE_DISTANCE = 0.35f;
    // A glass pane is 2/16 of a block thick. Match its tint at normal incidence.
    private static final float STAINED_GLASS_BLOCK_TRANSMITTANCE_DISTANCE = 8.0f * GLASS_TRANSMITTANCE_DISTANCE;
    public static final String STAINED_GLASS_BLOCK_LAYER = "stained_glass_block";
    private static final float THIN_SSS_MEASUREMENT_DISTANCE = 0.75f;
    private static final int THIN_SSS_DEFAULT_THICKNESS = Math.round(THIN_SSS_MEASUREMENT_DISTANCE * 255.0f);
    private static final float DEFAULT_CUTOUT_SSS_TRANSMISSION_DISTANCE = THIN_SSS_MEASUREMENT_DISTANCE;
    private static final float DEFAULT_CUTOUT_SSS_ANISOTROPY = 0.0f;
    private static final float DEFAULT_CUTOUT_SSS_ALBEDO = 0.5f;
    public static final float DEFAULT_TERRAIN_EMISSION_MULTIPLIER = 1.0f;
    public static final float MAX_TERRAIN_EMISSION_MULTIPLIER = 50.0f;
    private static final float BASE_TERRAIN_EMISSION_INTENSITY = 100.0f;
    public static final int L_SOLID = 0, L_CUTOUT = 1, L_CUTOUT_MIPPED = 2, L_TRANS = 3,
            L_GLASS = 4, L_STAINED_GLASS_BLOCK = 5, LAYER_COUNT = 6;

    private static BlockTextureManager INSTANCE;

    public static BlockTextureManager get() { return INSTANCE; }

    private final RemixAPI api;
    private byte[] albedoRGBA;
    private KavoraTexture atlasAlbedo;
    private KavoraTexture atlasEmissive;
    private KavoraTexture atlasNormalTex;
    private KavoraTexture atlasHeightTex;
    private KavoraTexture atlasRoughnessTex;
    private KavoraTexture atlasMetallicTex;
    private KavoraTexture atlasSssTex;
    private KavoraTexture atlasSssAlbedoTex;
    private int atlasW, atlasH;
    private final List<SpriteEntry> sprites = new ArrayList<>();
    private final Map<Identifier, SpriteEntry> spriteById = new HashMap<>();
    private final KavoraMaterial[] atlasMaterials = new KavoraMaterial[LAYER_COUNT];
    private final KavoraMaterial[] atlasMaterialsSSS = new KavoraMaterial[LAYER_COUNT];
    private final List<KavoraTexture> managedTextures = new ArrayList<>();
    private final List<KavoraMaterial> managedMaterials = new ArrayList<>();
    private KavoraMaterial waterSurfaceMaterial;
    private KavoraMaterial waterCameraMediumMaterial;
    private long waterNormalHandle;
    private boolean initialized, initFailed;
    private float cutoutSssTransmissionDistance = DEFAULT_CUTOUT_SSS_TRANSMISSION_DISTANCE;
    private float cutoutSssAnisotropy = DEFAULT_CUTOUT_SSS_ANISOTROPY;
    private float cutoutSssAlbedoR = DEFAULT_CUTOUT_SSS_ALBEDO;
    private float cutoutSssAlbedoG = DEFAULT_CUTOUT_SSS_ALBEDO;
    private float cutoutSssAlbedoB = DEFAULT_CUTOUT_SSS_ALBEDO;
    private boolean cutoutSssAlbedoTextureEnabled = true;
    private float terrainEmissionMultiplier = DEFAULT_TERRAIN_EMISSION_MULTIPLIER;
    private float appliedTerrainEmissionMultiplier = DEFAULT_TERRAIN_EMISSION_MULTIPLIER;
    private boolean materialSettingsInitialized;
    private boolean autoPbr = AutoPbr.DEFAULT_ENABLED;
    private boolean appliedAutoPbr = AutoPbr.DEFAULT_ENABLED;

    /**
     * Cached LabPBR data for one animated sprite, built once at atlas init
     * so the animation forwarder can re-apply AO and animated _s material
     * channels on every animation frame without re-loading _n / _s images.
     *
     * <p>{@code aoTable} comes from the {@code _n} image and the {@code s*}
     * tables from {@code _s}; any of them is null when the sprite has no LabPBR
     * sibling of that type. Tables are stored at the source image's dimensions and
     * sampled with modulo wrap so a small _n / _s can modulate a larger
     * animation frame.
     */
    public static final class AnimatedSpriteLabPBR {
        public final byte[] aoTable;
        public final int aoW, aoH;
        public final byte[] sAlphaTable;
        public final byte[] sRoughnessTable;
        public final byte[] sMetallicTable;
        public final byte[] sSssTable;
        public final int sW, sH;
        /** Whether any pixel of the sprite's {@code _s} alpha channel is below 255. */
        public final boolean hasPerPixelEmissive;
        public final boolean hasSss;
        public final boolean animatedRoughness, animatedMetallic, animatedSss;

        AnimatedSpriteLabPBR(byte[] aoTable, int aoW, int aoH,
                             byte[] sAlphaTable, byte[] sRoughnessTable,
                             byte[] sMetallicTable, byte[] sSssTable,
                             int sW, int sH,
                             boolean hasPerPixelEmissive, boolean hasSss, int frameWidth, int frameHeight) {
            this.aoTable = aoTable; this.aoW = aoW; this.aoH = aoH;
            this.sAlphaTable = sAlphaTable;
            this.sRoughnessTable = sRoughnessTable;
            this.sMetallicTable = sMetallicTable;
            this.sSssTable = sSssTable;
            this.sW = sW; this.sH = sH;
            this.hasPerPixelEmissive = hasPerPixelEmissive;
            this.hasSss = hasSss;
            animatedRoughness = sRoughnessTable != null && !LabPBRConverter.isFrameInvariant(
                    sRoughnessTable, sW, sH, frameWidth, frameHeight);
            animatedMetallic = sMetallicTable != null && !LabPBRConverter.isFrameInvariant(
                    sMetallicTable, sW, sH, frameWidth, frameHeight);
            animatedSss = sSssTable != null && !LabPBRConverter.isFrameInvariant(
                    sSssTable, sW, sH, frameWidth, frameHeight);
        }
    }

    /** Per-sprite UV bounds, alpha coverage and subsurface classification. */
    public static final class SpriteEntry {
        public final int index;
        public final Identifier id;
        public final float u0, v0, u1, v1;
        public final boolean hasTransparency;
        final boolean animated;
        final int pixW, pixH, pixX, pixY;
        private final BitSet transparentPixels;
        public boolean hasSSS;

        SpriteEntry(int i, Identifier id, TextureAtlasSprite s) {
            this.index = i; this.id = id;
            u0 = s.getU0(); v0 = s.getV0(); u1 = s.getU1(); v1 = s.getV1();
            pixX = SpriteAtlasContent.x(s); pixY = SpriteAtlasContent.y(s);
            SpriteContents contents = s.contents();
            animated = ((SpriteContentsAccessor) contents).getAnimation() != null;
            pixW = contents.width(); pixH = contents.height();
            transparentPixels = scanTransparency(contents, pixW, pixH);
            hasTransparency = transparentPixels != null && !transparentPixels.isEmpty();
        }

        /**
         * Returns true if the atlas-space UV rectangle used by one quad samples
         * any transparent texel in this sprite.
         */
        public boolean hasTransparency(float minU, float minV, float maxU, float maxV) {
            if (!hasTransparency) return false;

            float uMin = clamp(Math.min(minU, maxU), u0, u1);
            float uMax = clamp(Math.max(minU, maxU), u0, u1);
            float vMin = clamp(Math.min(minV, maxV), v0, v1);
            float vMax = clamp(Math.max(minV, maxV), v0, v1);
            if (uMax <= uMin || vMax <= vMin) return true;

            int x0 = uvToMinTexel(uMin, u0, u1, pixW);
            int x1 = uvToMaxTexel(uMax, u0, u1, pixW);
            int y0 = uvToMinTexel(vMin, v0, v1, pixH);
            int y1 = uvToMaxTexel(vMax, v0, v1, pixH);
            if (x1 < x0 || y1 < y0) return false;

            for (int y = y0; y <= y1; y++) {
                int rowStart = y * pixW + x0;
                int rowEnd = y * pixW + x1;
                int transparent = transparentPixels.nextSetBit(rowStart);
                if (transparent >= 0 && transparent <= rowEnd) return true;
            }
            return false;
        }

        private static BitSet scanTransparency(SpriteContents contents, int w, int h) {
            NativeImage img = ((SpriteContentsAccessor) contents).getImage();
            if (img == null) return null;
            BitSet transparent = null;
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                    if (((NativeImagePixels.abgr(img, x, y) >> 24) & 0xFF) < 255) {
                        if (transparent == null) transparent = new BitSet(w * h);
                        transparent.set(y * w + x);
                    }
            return transparent;
        }

        private static int uvToMinTexel(float uv, float spriteMin, float spriteMax, int size) {
            float local = (uv - spriteMin) / (spriteMax - spriteMin) * size;
            return Math.max(0, Math.min(size - 1, (int) Math.floor(local + 1.0E-4f)));
        }

        private static int uvToMaxTexel(float uv, float spriteMin, float spriteMax, int size) {
            float local = (uv - spriteMin) / (spriteMax - spriteMin) * size;
            return Math.max(0, Math.min(size - 1, (int) Math.ceil(local - 1.0E-4f) - 1));
        }

        private static float clamp(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    public BlockTextureManager(RemixAPI api) {
        this.api = api;
        INSTANCE = this;
    }

    // ====================================================================
    //  Init
    // ====================================================================

    public void initialize() {
        if (initialized || initFailed) return;
        try {
            Minecraft client = Minecraft.getInstance();
            if (api == null || client == null || client.getTextureManager() == null) return;

            AbstractTexture tex = client.getTextureManager().getTexture(BLOCK_ATLAS_ID);
            if (!(tex instanceof TextureAtlas spriteAtlas)) return;

            captureAtlas(spriteAtlas);
            LabPBRConverter.VariantCache labPBRVariants;
            try (ProfileScope ignored = ProfileScope.begin("LabPBR.variantScan")) {
                labPBRVariants = LabPBRConverter.buildVariantCache(client.getResourceManager());
            }
            buildSprites(spriteAtlas, labPBRVariants);
            Map<SpriteEntry, SpriteEntry> stainedGlassEdges = prepareVanillaStainedGlass(client.getResourceManager());
            if (!materialSettingsInitialized) {
                appliedTerrainEmissionMultiplier = terrainEmissionMultiplier;
                appliedAutoPbr = autoPbr;
            }
            Map<String, AnimatedSpriteLabPBR> labPBRMap =
                buildAnimatedSpriteLabPBRMap(labPBRVariants, spriteAtlas);
            buildAtlasMaterials(api, spriteAtlas, labPBRVariants, labPBRMap, stainedGlassEdges);
            buildWaterMaterials(api);
            AtlasAnimationForwarder.init(labPBRMap, Set.copyOf(spriteById.keySet()));

            albedoRGBA = null;
            initialized = true;
            materialSettingsInitialized = true;
        } catch (Exception e) {
            LOGGER.error("Init failed", e);
            initFailed = true;
        }
    }

    private void captureAtlas(TextureAtlas atlas) {
        atlasW = atlasWidth(atlas);
        atlasH = atlasHeight(atlas);
        if (atlasW == 0 || atlasH == 0) {
            LOGGER.warn("Atlas dimensions are zero");
            return;
        }
        albedoRGBA = stitchAtlasRGBA(atlas);
        LOGGER.info("Atlas {}x{} (stitched from {} sprites)",
                atlasW, atlasH, ((SpriteAtlasAccessor) atlas).getSprites().size());
    }

    /** Bakes a uniform dye and identifies the corresponding clear-glass edge normal templates. */
    private Map<SpriteEntry, SpriteEntry> prepareVanillaStainedGlass(ResourceManager resources) {
        Map<SpriteEntry, SpriteEntry> edges = new HashMap<>();
        for (SpriteEntry sprite : sprites) {
            String path = sprite.id.getPath();
            if (!sprite.id.getNamespace().equals("minecraft") || !path.startsWith("block/")) continue;
            boolean paneTop = path.endsWith("_pane_top");
            String facePath = paneTop ? path.substring(0, path.length() - "_pane_top".length()) : path;
            if (!facePath.endsWith("_stained_glass")) continue;

            SpriteEntry face = spriteById.get(sprite.id.withPath(facePath));
            if (face == null || !isVanillaBlockTexture(resources, face.id)
                    || (face != sprite && !isVanillaBlockTexture(resources, sprite.id))) continue;

            // The center of the vanilla face is the dye color and alpha between its frame marks.
            int center = ((face.pixY + face.pixH / 2) * atlasW + face.pixX + face.pixW / 2) * 4;
            byte[] dye = Arrays.copyOfRange(albedoRGBA, center, center + 4);
            for (int y = 0; y < sprite.pixH; y++) {
                int first = ((sprite.pixY + y) * atlasW + sprite.pixX) * 4;
                for (int x = 0; x < sprite.pixW; x++) {
                    System.arraycopy(dye, 0, albedoRGBA, first + x * 4, 4);
                }
            }

            SpriteEntry clear = spriteById.get(sprite.id.withPath(paneTop ? "block/glass_pane_top" : "block/glass"));
            if (clear != null && clear.pixW == sprite.pixW && clear.pixH == sprite.pixH) {
                edges.put(sprite, clear);
            }
        }
        return edges;
    }

    private static boolean isVanillaBlockTexture(ResourceManager resources, Identifier sprite) {
        return resources.getResource(sprite.withPath("textures/" + sprite.getPath() + ".png"))
                .filter(resource -> resource.sourcePackId().equals("vanilla")).isPresent();
    }

    private void copyGlassEdgeNormals(byte[] normals, SpriteEntry stained, SpriteEntry clear) {
        // The pane edge uses a narrow strip inside its texture. The face uses only the outermost texels.
        boolean paneTop = clear.id.getPath().equals("block/glass_pane_top");
        int rowBytes = stained.pixW * 4;
        for (int y = 0; y < stained.pixH; y++) {
            int source = ((clear.pixY + y) * atlasW + clear.pixX) * 4;
            int target = ((stained.pixY + y) * atlasW + stained.pixX) * 4;
            if (paneTop || y == 0 || y == stained.pixH - 1) {
                System.arraycopy(normals, source, normals, target, rowBytes);
            } else {
                System.arraycopy(normals, source, normals, target, 4);
                System.arraycopy(normals, source + rowBytes - 4, normals, target + rowBytes - 4, 4);
            }
        }
    }

    // ====================================================================
    //  Per-sprite metadata
    // ====================================================================

    private void buildSprites(TextureAtlas atlas, LabPBRConverter.VariantCache labPBRVariants) {
        Map<Identifier, TextureAtlasSprite> map = ((SpriteAtlasAccessor) atlas).getSprites();
        if (map == null) return;

        Map<String, Integer> lumMap = buildLuminanceMap();
        int pbr = 0, emissiveCount = 0, idx = 0;

        for (var e : map.entrySet()) {
            SpriteEntry se = new SpriteEntry(idx++, e.getKey(), e.getValue());
            int luminance = lumMap.getOrDefault(e.getKey().toString(), 0);

            NativeImage nI = labPBRVariants.loadVariant(e.getKey(), "_n");
            NativeImage sI = labPBRVariants.loadVariant(e.getKey(), "_s");
            NativeImage sssI = labPBRVariants.loadVariant(e.getKey(), "_sss");
            try {
                if (nI != null || sI != null || sssI != null) {
                    pbr++;
                }
                boolean emissive = sI != null && LabPBRConverter.hasEmissiveData(sI);
                if (emissive) emissiveCount++;
                if (emissive || luminance > 0) {
                    byte[] alb = extractRGBA(se.pixX, se.pixY, se.pixW, se.pixH);
                    if (nI != null) LabPBRConverter.applyAO(alb, se.pixW, nI, 0, 0, se.pixW, se.pixH);
                    byte[] emission = null;
                    if (emissive) {
                        emission = new byte[alb.length];
                        LabPBRConverter.blitEmissive(emission, se.pixW, alb, sI, 0, 0, se.pixW, se.pixH, 0f);
                    }
                    spriteLightColors.put(se.id.toString(), computeLightColor(alb, emission, se.pixW, se.pixH));
                }
            } finally {
                if (nI != null) nI.close();
                if (sI != null) sI.close();
                if (sssI != null) sssI.close();
            }
            sprites.add(se);
            spriteById.put(se.id, se);
        }
        LOGGER.info("{} sprites indexed ({} by id), {} with LabPBR, {} emissive",
                sprites.size(), spriteById.size(), pbr, emissiveCount);
    }

    // ====================================================================
    //  Animated-sprite LabPBR cache
    // ====================================================================

    /**
     * Builds per-animated-sprite LabPBR data tables so the atlas animation
     * forwarder can keep AO and animated _s material channels in phase with
     * each animated frame. Without this, animated albedo updates would
     * overwrite the AO baked into the atlas at init while roughness,
     * metallic, SSS, and emissive atlases stayed on the init-time sidecar
     * frame.
     *
     * <p>Only animated sprites with at least one of {@code _n} / {@code _s}
     * are cached. Non-LabPBR animated sprites are copied as albedo only.
     */
    private Map<String, AnimatedSpriteLabPBR> buildAnimatedSpriteLabPBRMap(
            LabPBRConverter.VariantCache labPBRVariants, TextureAtlas atlas) {
        Map<String, AnimatedSpriteLabPBR> result = new HashMap<>();
        Map<Identifier, TextureAtlasSprite> map = ((SpriteAtlasAccessor) atlas).getSprites();
        if (map == null) return result;

        for (var e : map.entrySet()) {
            SpriteContents contents = e.getValue().contents();
            SpriteContents.AnimatedTexture animation = ((SpriteContentsAccessor) contents).getAnimation();
            if (animation == null) continue;

            NativeImage nI = labPBRVariants.loadVariant(e.getKey(), "_n");
            NativeImage sI = labPBRVariants.loadVariant(e.getKey(), "_s");
            try {
                if (nI == null && sI == null) continue;
                byte[] aoTable     = (nI != null) ? LabPBRConverter.buildAOTable(nI)     : null;
                int aoW            = (nI != null) ? nI.getWidth()                        : 0;
                int aoH            = (nI != null) ? nI.getHeight()                       : 0;
                byte[] sAlphaTable = (sI != null)
                        ? LabPBRConverter.buildSAlphaTable(sI)
                        : null;
                byte[] sRoughnessTable = (sI != null) ? LabPBRConverter.buildSRoughnessTable(sI) : null;
                byte[] sMetallicTable = (sI != null) ? LabPBRConverter.buildSMetallicTable(sI) : null;
                byte[] sSssTable = (sI != null) ? LabPBRConverter.buildSSssTable(sI) : null;
                int sW             = (sI != null) ? sI.getWidth()                        : 0;
                int sH             = (sI != null) ? sI.getHeight()                       : 0;
                boolean hasPerPixelEmissive = sI != null && LabPBRConverter.hasEmissiveData(sI);
                boolean hasSss = sI != null && LabPBRConverter.hasSSSData(sI);
                result.put(e.getKey().toString(),
                    new AnimatedSpriteLabPBR(aoTable, aoW, aoH,
                                             sAlphaTable, sRoughnessTable, sMetallicTable, sSssTable,
                                             sW, sH, hasPerPixelEmissive, hasSss, contents.width(), contents.height()));
            } finally {
                if (nI != null) nI.close();
                if (sI != null) sI.close();
            }
        }
        int invariantChannels = 0;
        for (var sprite : result.values()) {
            if (sprite.sRoughnessTable != null && !sprite.animatedRoughness) invariantChannels++;
            if (sprite.sMetallicTable != null && !sprite.animatedMetallic) invariantChannels++;
            if (sprite.sSssTable != null && !sprite.animatedSss) invariantChannels++;
        }
        LOGGER.info("Animated LabPBR cache: {} sprites, {} frame-invariant material channels", result.size(), invariantChannels);
        return result;
    }

    // ====================================================================
    //  Atlas-level PBR textures & materials
    // ====================================================================

    private void buildAtlasMaterials(RemixAPI api, TextureAtlas atlas, LabPBRConverter.VariantCache labPBRVariants,
                                     Map<String, AnimatedSpriteLabPBR> animations,
                                     Map<SpriteEntry, SpriteEntry> stainedGlassEdges) {
        int pixels = atlasW * atlasH;
        boolean animatedRoughness = false, animatedMetallic = false, animatedThickness = false;
        for (var animation : animations.values()) {
            animatedRoughness |= animation.animatedRoughness;
            animatedMetallic |= animation.animatedMetallic;
            animatedThickness |= animation.hasSss && animation.animatedSss;
        }
        LOGGER.info("Material atlas animation: roughness={}, metallic={}, thickness={}",
                animatedRoughness, animatedMetallic, animatedThickness);

        byte[] normalAtlas    = new byte[pixels * 4];
        byte[] heightAtlas    = new byte[pixels];
        byte[] roughnessAtlas = new byte[pixels];
        byte[] metallicAtlas  = new byte[pixels];
        byte[] emissiveAtlas  = new byte[pixels * 4];
        byte[] sssThicknessAtlas = new byte[pixels];
        byte[] sssAlbedoAtlas = LabPBRConverter.createSingleScatteringAlbedoFallback(albedoRGBA);

        Arrays.fill(heightAtlas, (byte) 255);
        Arrays.fill(roughnessAtlas, (byte) 180);
        Arrays.fill(sssThicknessAtlas, (byte) THIN_SSS_DEFAULT_THICKNESS);
        for (int i = 0; i < pixels; i++) {
            int idx4 = i * 4;
            normalAtlas[idx4]     = (byte) 128;
            normalAtlas[idx4 + 1] = (byte) 128;
            normalAtlas[idx4 + 2] = (byte) 255;
            normalAtlas[idx4 + 3] = (byte) 255;
        }

        boolean hasAnyNormal = false, hasAnyHeight = false, hasAnyEmissive = false, hasAnySSS = false;

        for (SpriteEntry se : sprites) {
            NativeImage nI = labPBRVariants.loadVariant(se.id, "_n");
            NativeImage sI = labPBRVariants.loadVariant(se.id, "_s");
            NativeImage sssI = labPBRVariants.loadVariant(se.id, "_sss");
            try {
                if (nI != null) {
                    stainedGlassEdges.remove(se);
                    LabPBRConverter.blitNormal(normalAtlas, atlasW, nI, se.pixX, se.pixY, se.pixW, se.pixH);
                    LabPBRConverter.blitHeight(heightAtlas, atlasW, nI, se.pixX, se.pixY, se.pixW, se.pixH);
                    LabPBRConverter.applyAO(albedoRGBA, atlasW, nI, se.pixX, se.pixY, se.pixW, se.pixH);
                    LabPBRConverter.applyAO(sssAlbedoAtlas, atlasW, nI, se.pixX, se.pixY, se.pixW, se.pixH);
                    hasAnyNormal = true;
                    hasAnyHeight = true;
                }
                if (sI != null) {
                    LabPBRConverter.blitRoughness(roughnessAtlas, atlasW, sI, se.pixX, se.pixY, se.pixW, se.pixH);
                    LabPBRConverter.blitMetallic(metallicAtlas, atlasW, sI, se.pixX, se.pixY, se.pixW, se.pixH);
                    if (LabPBRConverter.hasSSSData(sI)) {
                        LabPBRConverter.blitSssThickness(sssThicknessAtlas, atlasW, sI, se.pixX, se.pixY, se.pixW, se.pixH);
                        se.hasSSS = true;
                        hasAnySSS = true;
                    }
                }
                if (appliedAutoPbr && (nI == null || sI == null)) {
                    AutoPbr.Surface surface = AutoPbr.surface(se.id, se.animated);
                    if (surface != null) {
                        if (nI == null) {
                            AutoPbr.writeNormal(albedoRGBA, normalAtlas, atlasW,
                                    se.pixX, se.pixY, se.pixW, se.pixH, surface.normalStrength());
                            hasAnyNormal = true;
                        }
                        if (sI == null) {
                            AutoPbr.writeMaterial(albedoRGBA, roughnessAtlas, metallicAtlas, atlasW,
                                    se.pixX, se.pixY, se.pixW, se.pixH, surface);
                        }
                    }
                }
                if (sssI != null) {
                    LabPBRConverter.blitSingleScatteringAlbedo(
                            sssAlbedoAtlas, atlasW, sssI, se.pixX, se.pixY, se.pixW, se.pixH);
                    se.hasSSS = true;
                    hasAnySSS = true;
                }
                boolean needsEmissive = sI != null && LabPBRConverter.hasEmissiveData(sI);
                if (needsEmissive) {
                    LabPBRConverter.blitEmissive(emissiveAtlas, atlasW, albedoRGBA, sI,
                            se.pixX, se.pixY, se.pixW, se.pixH, 0f);
                    hasAnyEmissive = true;
                } else if (BuiltinEmission.mask(se.id) != null) {
                    LabPBRConverter.blitEmissiveMask(emissiveAtlas, atlasW, albedoRGBA,
                            se.pixX, se.pixY, se.pixW, se.pixH, BuiltinEmission.mask(se.id));
                    hasAnyEmissive = true;
                }
            } finally {
                if (nI != null) nI.close();
                if (sI != null) sI.close();
                if (sssI != null) sssI.close();
            }
        }

        stainedGlassEdges.forEach((stained, clear) -> copyGlassEdgeNormals(normalAtlas, stained, clear));
        SpriteAtlasContent.extrudePadding(atlas, albedoRGBA, 4);
        SpriteAtlasContent.extrudePadding(atlas, normalAtlas, 4);
        SpriteAtlasContent.extrudePadding(atlas, heightAtlas, 1);
        SpriteAtlasContent.extrudePadding(atlas, roughnessAtlas, 1);
        SpriteAtlasContent.extrudePadding(atlas, metallicAtlas, 1);
        SpriteAtlasContent.extrudePadding(atlas, emissiveAtlas, 4);
        SpriteAtlasContent.extrudePadding(atlas, sssThicknessAtlas, 1);
        SpriteAtlasContent.extrudePadding(atlas, sssAlbedoAtlas, 4);
        String auditDirectory = System.getProperty("kavora.materialSnapshotDirectory");
        if (auditDirectory != null) {
            try {
                var audit = new java.util.TreeMap<String, java.util.List<String>>();
                var digest = java.security.MessageDigest.getInstance("SHA-256");
                byte[][] channels = { albedoRGBA, normalAtlas, heightAtlas, roughnessAtlas,
                        metallicAtlas, emissiveAtlas, sssThicknessAtlas, sssAlbedoAtlas };
                int[] strides = { 4, 4, 1, 1, 1, 4, 1, 4 };
                var layoutAudit = new java.util.TreeMap<String, java.util.List<Number>>();
                java.nio.file.Files.createDirectories(java.nio.file.Path.of(auditDirectory));
                for (SpriteEntry entry : sprites) {
                    layoutAudit.put(entry.id.toString(), java.util.List.of(entry.pixX, entry.pixY, entry.pixW,
                            entry.pixH, entry.u0, entry.v0, entry.u1, entry.v1));
                    var hashes = new java.util.ArrayList<String>();
                    for (int channel = 0; channel < channels.length; channel++) {
                        for (int y = 0; y < entry.pixH; y++) {
                            digest.update(channels[channel], ((entry.pixY + y) * atlasW + entry.pixX) * strides[channel],
                                    entry.pixW * strides[channel]);
                        }
                        hashes.add(java.util.HexFormat.of().formatHex(digest.digest()));
                    }
                    audit.put(entry.id.toString(), hashes);
                    if (entry.id.toString().equals("minecraft:block/cherry_leaves")) {
                        byte[] pixelsCopy = new byte[entry.pixW * entry.pixH * 4];
                        for (int y = 0; y < entry.pixH; y++) {
                            System.arraycopy(albedoRGBA, ((entry.pixY + y) * atlasW + entry.pixX) * 4,
                                    pixelsCopy, y * entry.pixW * 4, entry.pixW * 4);
                        }
                        java.nio.file.Files.write(java.nio.file.Path.of(auditDirectory, "cherry-leaves.rgba"), pixelsCopy);
                    }
                }
                java.nio.file.Files.createDirectories(java.nio.file.Path.of(auditDirectory));
                java.nio.file.Files.writeString(java.nio.file.Path.of(auditDirectory, "atlas-layout.json"),
                        new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(layoutAudit));
                java.nio.file.Files.writeString(java.nio.file.Path.of(auditDirectory, "atlas-channels.json"),
                        new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(audit));
            } catch (java.io.IOException | java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
        atlasAlbedo = upload(api, "atlas_albedo", albedoRGBA, atlasW, atlasH,
                TextureFormat.RGBA8_UNORM, TexturePurpose.COLOR);

        KavoraTexture normalTex    = hasAnyNormal ? upload(api, "atlas_normal", normalAtlas, atlasW, atlasH,
                TextureFormat.RGBA8_UNORM, TexturePurpose.NORMAL_DX, true) : null;
        KavoraTexture heightTex    = hasAnyHeight ? upload(api, "atlas_height", heightAtlas, atlasW, atlasH,
                TextureFormat.R8_UNORM, TexturePurpose.HEIGHTMAP, true) : null;
        KavoraTexture roughnessTex = upload(api, "atlas_roughness", roughnessAtlas, atlasW, atlasH,
                TextureFormat.R8_UNORM, TexturePurpose.DATA, !animatedRoughness);
        KavoraTexture metallicTex  = upload(api, "atlas_metallic", metallicAtlas, atlasW, atlasH,
                TextureFormat.R8_UNORM, TexturePurpose.DATA, !animatedMetallic);
        KavoraTexture emissiveTex  = hasAnyEmissive ? upload(api, "atlas_emissive", emissiveAtlas, atlasW, atlasH,
                TextureFormat.RGBA8_UNORM, TexturePurpose.COLOR) : null;
        atlasEmissive = emissiveTex;
        KavoraTexture sssTex       = hasAnySSS ? upload(api, "atlas_sss", sssThicknessAtlas, atlasW, atlasH,
                TextureFormat.R8_UNORM, TexturePurpose.DATA, !animatedThickness) : null;
        KavoraTexture sssAlbedoTex = hasAnySSS ? upload(api, "atlas_sss_albedo",
                sssAlbedoAtlas, atlasW, atlasH, TextureFormat.RGBA8_UNORM, TexturePurpose.DATA, true) : null;
        atlasNormalTex = normalTex;
        atlasHeightTex = heightTex;
        atlasRoughnessTex = roughnessTex;
        atlasMetallicTex = metallicTex;
        atlasSssTex = sssTex;
        atlasSssAlbedoTex = sssAlbedoTex;

        for (int lt = 0; lt < LAYER_COUNT; lt++) {
            if (isTranslucentLayer(lt)) {
                atlasMaterials[lt] = createAtlasTranslucentMaterial(lt, emissiveTex, normalTex);
            } else {
                atlasMaterials[lt] = createAtlasOpaqueMaterial(lt, normalTex, heightTex,
                        roughnessTex, metallicTex, emissiveTex, null, null);
                atlasMaterialsSSS[lt] = hasAnySSS ? createAtlasOpaqueMaterial(lt, normalTex, heightTex,
                        roughnessTex, metallicTex, emissiveTex,
                        sssTex, sssAlbedoTex) : null;
            }
        }
        int sssCount = 0;
        StringBuilder sssSprites = new StringBuilder();
        for (SpriteEntry se : sprites) {
            if (se.hasSSS) {
                sssCount++;
                int maxBlue = 0;
                for (int y = 0; y < se.pixH; y++)
                    for (int x = 0; x < se.pixW; x++) {
                        int idx = (se.pixY + y) * atlasW + se.pixX + x;
                        maxBlue = Math.max(maxBlue, sssThicknessAtlas[idx] & 0xFF);
                    }
                if (sssSprites.length() < 1000)
                    sssSprites.append("\n  ").append(se.id).append(" (maxSSS=").append(maxBlue).append(')');
            }
        }
        LOGGER.info("Atlas materials created (normal={} height={} emissive={} sss={}, sssSprites={}{})",
                hasAnyNormal, hasAnyHeight, hasAnyEmissive, hasAnySSS, sssCount, sssSprites);
    }

    private KavoraMaterial createAtlasOpaqueMaterial(int lt, KavoraTexture normalTex,
                                               KavoraTexture heightTex, KavoraTexture roughnessTex,
                                               KavoraTexture metallicTex, KavoraTexture emissiveTex,
                                               KavoraTexture sssTex,
                                               KavoraTexture sssAlbedoTex) {
        return createMaterialViaStore(buildAtlasOpaqueMaterialInfo(
                lt, normalTex, heightTex, roughnessTex, metallicTex, emissiveTex,
                sssTex, sssAlbedoTex, terrainEmissionIntensity()));
    }

    private MaterialInfo buildAtlasOpaqueMaterialInfo(int lt, KavoraTexture normalTex,
                                               KavoraTexture heightTex, KavoraTexture roughnessTex,
                                               KavoraTexture metallicTex, KavoraTexture emissiveTex,
                                               KavoraTexture sssTex,
                                               KavoraTexture sssAlbedoTex,
                                               float emissionIntensity) {
        MaterialInfoOpaqueEXT.Builder ob = MaterialInfoOpaqueEXT.builder()
                .albedoConstant(1f, 1f, 1f).roughnessConstant(1f).metallicConstant(1f)
                .useDrawCallAlphaState(true);
        if (lt != L_SOLID) ob.alphaTestType(4).alphaReferenceValue(128);
        if (roughnessTex != null) ob.roughnessTexture(roughnessTex.getHandle());
        if (metallicTex != null)  ob.metallicTexture(metallicTex.getHandle());
        if (heightTex != null)    ob.heightTexture(heightTex.getHandle()).displaceIn(POM_DISPLACE_IN);
        if (sssTex != null)       ob.subsurfaceExt(buildAtlasSSS(
                lt == L_SOLID, sssTex, sssAlbedoTex));

        MaterialInfo.Builder mb = MaterialInfo.builder()
                .albedoTexture(atlasAlbedo.getHandle()).filterMode(0);
        if (normalTex != null)   mb.normalTexture(normalTex.getHandle());
        if (emissiveTex != null) {
            mb.emissiveTexture(emissiveTex.getHandle())
                    .emissiveIntensity(emissionIntensity);
        }
        return mb.opaqueExt(ob.build()).build();
    }

    /**
     * Creates a cutout atlas material whose emission can be updated independently
     * without duplicating any atlas textures.
     */
    public KavoraMaterial createEmissionScaledAtlasMaterial(float emissionMultiplier) {
        return createMaterialViaStore(emissionScaledAtlasMaterialInfo(emissionMultiplier));
    }

    /** Updates a material created by {@link #createEmissionScaledAtlasMaterial(float)}. */
    public void updateEmissionScaledAtlasMaterial(
            KavoraMaterial material, float emissionMultiplier) {
        KavoraMaterialStore.get().update(
                material, emissionScaledAtlasMaterialInfo(emissionMultiplier));
    }

    /** Releases a material created by {@link #createEmissionScaledAtlasMaterial(float)}. */
    public void destroyEmissionScaledAtlasMaterial(KavoraMaterial material) {
        destroyManagedMaterial(material);
    }

    private MaterialInfo emissionScaledAtlasMaterialInfo(float emissionMultiplier) {
        return buildAtlasOpaqueMaterialInfo(
                L_CUTOUT,
                atlasNormalTex,
                atlasHeightTex,
                atlasRoughnessTex,
                atlasMetallicTex,
                atlasEmissive,
                null,
                null,
                scaledEmissionIntensity(
                        terrainEmissionIntensity(), emissionMultiplier));
    }

    static float scaledEmissionIntensity(
            float baseEmissionIntensity, float emissionMultiplier) {
        return baseEmissionIntensity * Math.max(0.0f, emissionMultiplier);
    }

    private MaterialInfoOpaqueSubsurfaceEXT buildAtlasSSS(boolean solid, KavoraTexture sssTex,
                                                          KavoraTexture sssAlbedoTex) {
        MaterialInfoOpaqueSubsurfaceEXT.Builder sb = MaterialInfoOpaqueSubsurfaceEXT.builder()
                .transmittanceTexture(atlasAlbedo.getHandle());
        if (solid) {
            KavoraTexture radiusTexture = (sssAlbedoTex != null) ? sssAlbedoTex : atlasAlbedo;
            sb.diffusionProfile(true).radiusTexture(radiusTexture.getHandle())
              .radiusScale(LabPBRConverter.DIFFUSION_SSS_RADIUS_SCALE)
              .maxSampleRadius(LabPBRConverter.DIFFUSION_SSS_MAX_SAMPLE_RADIUS);
        } else {
            sb.diffusionProfile(false).thicknessTexture(sssTex.getHandle())
              .singleScatteringAlbedo(cutoutSssAlbedoR, cutoutSssAlbedoG, cutoutSssAlbedoB)
              .measurementDistance(cutoutSssTransmissionDistance)
              .volumetricAnisotropy(cutoutSssAnisotropy);
            if (cutoutSssAlbedoTextureEnabled && sssAlbedoTex != null) {
                sb.singleScatteringAlbedoTexture(sssAlbedoTex.getHandle());
            }
        }
        return sb.build();
    }


    private KavoraMaterial createAtlasTranslucentMaterial(
            int layer, KavoraTexture emissiveTex, KavoraTexture normalTex) {
        MaterialInfo.Builder mb = MaterialInfo.builder()
                .albedoTexture(atlasAlbedo.getHandle()).filterMode(0);
        if (normalTex != null) mb.normalTexture(normalTex.getHandle());
        if (emissiveTex != null) {
            mb.emissiveTexture(emissiveTex.getHandle())
                    .emissiveIntensity(terrainEmissionIntensity());
        }
        return createMaterialViaStore(mb.translucentExt(atlasTranslucentOptics(atlasAlbedo.getHandle(), layer)).build());
    }

    /** Common optical parameters for near terrain and baked far-terrain atlases. */
    public static MaterialInfoTranslucentEXT atlasTranslucentOptics(long albedo, boolean clearGlass) {
        return atlasTranslucentOptics(albedo, clearGlass ? L_GLASS : L_TRANS);
    }

    static MaterialInfoTranslucentEXT atlasTranslucentOptics(long albedo, int layer) {
        var optics = MaterialInfoTranslucentEXT.builder()
                .refractiveIndex(COMMON_GLASS_IOR).transmittanceColor(0.97f, 0.97f, 0.97f)
                .transmittanceMeasurementDistance(layer == L_STAINED_GLASS_BLOCK
                        ? STAINED_GLASS_BLOCK_TRANSMITTANCE_DISTANCE : GLASS_TRANSMITTANCE_DISTANCE)
                .useDiffuseLayer(layer == L_GLASS);
        if (layer != L_GLASS) optics.transmittanceTexture(albedo);
        return optics.build();
    }

    // ====================================================================
    //  Custom water material (animated normal flipbook, translucent)
    // ====================================================================

    private static final String WATER_NORMALS_DDS = "textures/water_normals_flipbook.dds";
    private static final float WATER_TILE_SIZE = 10.0f;
    private static final float DEFAULT_WATER_IOR = 1.33f;
    private static final float DEFAULT_WATER_TRANSMITTANCE_R = 0.6f;
    private static final float DEFAULT_WATER_TRANSMITTANCE_G = 0.9f;
    private static final float DEFAULT_WATER_TRANSMITTANCE_B = 0.89f;
    private static final float DEFAULT_WATER_TRANSMITTANCE_DISTANCE = 1f;
    private static final float WATER_CAMERA_MEDIUM_TRANSMITTANCE = 0.98f;
    private static final float WATER_CAMERA_MEDIUM_TRANSMITTANCE_DISTANCE = 1f;
    private static final String WATER_WHITE_TEXTURE_NAME = "water_white";

    private KavoraTexture waterWhiteTexture;
    private float waterIor = DEFAULT_WATER_IOR;
    private float waterTransmittanceR = DEFAULT_WATER_TRANSMITTANCE_R;
    private float waterTransmittanceG = DEFAULT_WATER_TRANSMITTANCE_G;
    private float waterTransmittanceB = DEFAULT_WATER_TRANSMITTANCE_B;
    private float waterTransmittanceDistance = DEFAULT_WATER_TRANSMITTANCE_DISTANCE;
    private int waterAnimationFps = 20;

    private void destroyManagedMaterial(KavoraMaterial material) {
        if (material == null) {
            return;
        }
        managedMaterials.remove(material);
        if (!material.isDeleted()) {
            material.destroy();
        }
    }

    private void destroyManagedTexture(KavoraTexture texture) {
        if (texture == null) {
            return;
        }
        managedTextures.remove(texture);
        if (!texture.isDeleted()) {
            texture.destroy();
        }
    }

    private void destroyWaterResources(RemixAPI api) {
        destroyManagedMaterial(waterSurfaceMaterial);
        waterSurfaceMaterial = null;
        destroyManagedMaterial(waterCameraMediumMaterial);
        waterCameraMediumMaterial = null;

        destroyManagedTexture(waterWhiteTexture);
        waterWhiteTexture = null;

        if (waterNormalHandle != 0) {
            try {
                api.destroyTexture(waterNormalHandle);
            } catch (Exception e) {
                LOGGER.warn("Failed to destroy water normal texture: {}", e.getMessage());
            }
            waterNormalHandle = 0;
        }
    }

    private void buildWaterMaterials(RemixAPI api) {
        boolean success = false;
        try {
            destroyWaterResources(api);

            String mcDir = Minecraft.getInstance().gameDirectory.getAbsolutePath();
            String ddsPath = mcDir + File.separator + WATER_NORMALS_DDS;

            waterNormalHandle = api.createTextureFromFile(ddsPath);

            byte[] white = {(byte) 255, (byte) 255, (byte) 255, (byte) 255};
            waterWhiteTexture = upload(api, WATER_WHITE_TEXTURE_NAME, white, 1, 1,
                    TextureFormat.RGBA8_UNORM, TexturePurpose.COLOR);
            if (waterWhiteTexture == null) {
                LOGGER.warn("Failed to create white texture for water material");
                return;
            }

            waterSurfaceMaterial = createMaterialViaStore(waterSurfaceInfo());
            waterCameraMediumMaterial = createMaterialViaStore(waterCameraMediumInfo());

            if (waterSurfaceMaterial != null && waterCameraMediumMaterial != null) {
                LOGGER.info("Water surface and camera-medium materials created "
                                + "(ior={}, surfaceTransmittance=({}, {}, {}), distance={}, "
                                + "cameraTransmittance={}, cameraDistance={}, tileSize={})",
                        waterIor, waterTransmittanceR, waterTransmittanceG, waterTransmittanceB,
                        waterTransmittanceDistance, WATER_CAMERA_MEDIUM_TRANSMITTANCE,
                        WATER_CAMERA_MEDIUM_TRANSMITTANCE_DISTANCE, WATER_TILE_SIZE);
                success = true;
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to create water materials (falling back to atlas): {}", e.getMessage());
        } finally {
            if (!success) {
                destroyWaterResources(api);
            }
        }
    }

    public KavoraMaterial getWaterSurfaceMaterial() { return waterSurfaceMaterial; }
    public KavoraMaterial getWaterCameraMediumMaterial() { return waterCameraMediumMaterial; }
    public static float getWaterTileSize() { return WATER_TILE_SIZE; }
    public float getWaterIor() { return waterIor; }
    public float getWaterTransmittanceR() { return waterTransmittanceR; }
    public float getWaterTransmittanceG() { return waterTransmittanceG; }
    public float getWaterTransmittanceB() { return waterTransmittanceB; }
    public float getWaterTransmittanceDistance() { return waterTransmittanceDistance; }
    public int getWaterAnimationFps() { return waterAnimationFps; }

    private MaterialInfo waterSurfaceInfo() {
        return MaterialInfo.builder()
                .albedoTexture(waterWhiteTexture.getHandle())
                .normalTexture(waterNormalHandle)
                .filterMode(1)
                .spriteSheetRow(10)
                .spriteSheetCol(12)
                .spriteSheetFps(waterAnimationFps)
                .translucentExt(MaterialInfoTranslucentEXT.builder()
                        .refractiveIndex(waterIor)
                        .transmittanceColor(waterTransmittanceR, waterTransmittanceG, waterTransmittanceB)
                        .transmittanceMeasurementDistance(waterTransmittanceDistance)
                        .useDiffuseLayer(false)
                        .simulatedWater(true)
                        .build())
                .build();
    }

    private MaterialInfo waterCameraMediumInfo() {
        return MaterialInfo.builder()
                .albedoTexture(waterWhiteTexture.getHandle())
                .filterMode(1)
                .translucentExt(MaterialInfoTranslucentEXT.builder()
                        .refractiveIndex(waterIor)
                        .transmittanceColor(
                                WATER_CAMERA_MEDIUM_TRANSMITTANCE,
                                WATER_CAMERA_MEDIUM_TRANSMITTANCE,
                                WATER_CAMERA_MEDIUM_TRANSMITTANCE)
                        .transmittanceMeasurementDistance(WATER_CAMERA_MEDIUM_TRANSMITTANCE_DISTANCE)
                        .useDiffuseLayer(false)
                        .build())
                .build();
    }
    public float getCutoutSssTransmissionDistance() { return cutoutSssTransmissionDistance; }
    public float getCutoutSssAnisotropy() { return cutoutSssAnisotropy; }
    public float getCutoutSssAlbedoR() { return cutoutSssAlbedoR; }
    public float getCutoutSssAlbedoG() { return cutoutSssAlbedoG; }
    public float getCutoutSssAlbedoB() { return cutoutSssAlbedoB; }
    public boolean isCutoutSssAlbedoTextureEnabled() { return cutoutSssAlbedoTextureEnabled; }
    public float getTerrainEmissionMultiplier() { return terrainEmissionMultiplier; }
    public boolean hasCutoutSssAtlasMaterial() {
        return atlasMaterialsSSS[L_CUTOUT] != null || atlasMaterialsSSS[L_CUTOUT_MIPPED] != null;
    }

    public void setTerrainEmissionMultiplier(float multiplier) {
        terrainEmissionMultiplier = Math.max(
                0.0f, Math.min(MAX_TERRAIN_EMISSION_MULTIPLIER, multiplier));
    }

    public boolean isAutoPbr() { return autoPbr; }

    /** Stages automatic material generation until the settings transaction commits. */
    public void setAutoPbr(boolean enabled) { autoPbr = enabled; }

    public boolean commitMaterialSettings() {
        boolean changed = Float.compare(appliedTerrainEmissionMultiplier, terrainEmissionMultiplier) != 0
                || appliedAutoPbr != autoPbr;
        appliedTerrainEmissionMultiplier = terrainEmissionMultiplier;
        appliedAutoPbr = autoPbr;
        return changed;
    }

    float terrainEmissionIntensity() {
        return terrainEmissionIntensity(appliedTerrainEmissionMultiplier);
    }

    private static float terrainEmissionIntensity(float multiplier) {
        return BASE_TERRAIN_EMISSION_INTENSITY * multiplier;
    }

    public boolean updateWaterIor(float ior) {
        waterIor = ior;
        return updateWaterMaterials();
    }

    public boolean updateWaterTransmittanceColor(float r, float g, float b) {
        waterTransmittanceR = r;
        waterTransmittanceG = g;
        waterTransmittanceB = b;
        return updateWaterMaterials();
    }

    public boolean updateWaterTransmittanceDistance(float distance) {
        waterTransmittanceDistance = distance;
        return updateWaterMaterials();
    }

    public boolean updateCutoutSssTransmissionDistance(float distance) {
        cutoutSssTransmissionDistance = Math.max(0.0001f, distance);
        return rebuildCutoutSssMaterials();
    }

    public boolean updateCutoutSssAnisotropy(float anisotropy) {
        cutoutSssAnisotropy = Math.max(-1.0f, Math.min(1.0f, anisotropy));
        return rebuildCutoutSssMaterials();
    }

    public boolean updateCutoutSssAlbedo(float r, float g, float b) {
        cutoutSssAlbedoR = clamp01(r);
        cutoutSssAlbedoG = clamp01(g);
        cutoutSssAlbedoB = clamp01(b);
        return rebuildCutoutSssMaterials();
    }

    public boolean updateCutoutSssAlbedoTextureEnabled(boolean enabled) {
        cutoutSssAlbedoTextureEnabled = enabled;
        return rebuildCutoutSssMaterials();
    }

    public boolean resetCutoutSss() {
        cutoutSssTransmissionDistance = DEFAULT_CUTOUT_SSS_TRANSMISSION_DISTANCE;
        cutoutSssAnisotropy = DEFAULT_CUTOUT_SSS_ANISOTROPY;
        cutoutSssAlbedoR = DEFAULT_CUTOUT_SSS_ALBEDO;
        cutoutSssAlbedoG = DEFAULT_CUTOUT_SSS_ALBEDO;
        cutoutSssAlbedoB = DEFAULT_CUTOUT_SSS_ALBEDO;
        cutoutSssAlbedoTextureEnabled = true;
        return rebuildCutoutSssMaterials();
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private boolean rebuildCutoutSssMaterials() {
        if (api == null) {
            LOGGER.warn("Cannot rebuild cutout SSS materials: Remix API unavailable");
            return false;
        }
        if (!initialized || atlasAlbedo == null || atlasSssTex == null) {
            return false;
        }

        rebuildAtlasSssMaterial(L_CUTOUT);
        rebuildAtlasSssMaterial(L_CUTOUT_MIPPED);

        LOGGER.info("Cutout SSS updated (transmissionDistance={}, anisotropy={}, albedo=({}, {}, {}), albedoTexture={})",
                cutoutSssTransmissionDistance, cutoutSssAnisotropy,
                cutoutSssAlbedoR, cutoutSssAlbedoG, cutoutSssAlbedoB,
                cutoutSssAlbedoTextureEnabled);
        return hasCutoutSssAtlasMaterial();
    }

    private void rebuildAtlasSssMaterial(int layerType) {
        destroyManagedMaterial(atlasMaterialsSSS[layerType]);
        atlasMaterialsSSS[layerType] = createAtlasOpaqueMaterial(layerType, atlasNormalTex,
                atlasHeightTex, atlasRoughnessTex, atlasMetallicTex, atlasEmissive, atlasSssTex,
                atlasSssAlbedoTex);
    }

    public boolean updateWaterAnimationFps(int fps) {
        if (fps < 0 || fps > 120) throw new IllegalArgumentException("Water animation rate must be between 0 and 120 FPS");
        waterAnimationFps = fps;
        return updateWaterMaterials();
    }

    private boolean updateWaterMaterials() {
        if (waterSurfaceMaterial == null || waterCameraMediumMaterial == null) return false;
        KavoraMaterialStore.get().update(waterSurfaceMaterial, waterSurfaceInfo());
        KavoraMaterialStore.get().update(waterCameraMediumMaterial, waterCameraMediumInfo());
        return true;
    }

    /** Returns the atlas-based material (no SSS) for the given layer type. */
    public KavoraMaterial getAtlasMaterial(int layerType) {
        return atlasMaterials[layerType];
    }

    /** Returns the atlas-based SSS material for the given layer type, or null if none. */
    public KavoraMaterial getAtlasMaterialSSS(int layerType) {
        return atlasMaterialsSSS[layerType];
    }

    /** Maps sprite identifiers (e.g. "minecraft:block/glowstone") to max block luminance. */
    private static Map<String, Integer> buildLuminanceMap() {
        Map<String, Integer> map = new HashMap<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            int maxLum = 0;
            for (BlockState state : block.getStateDefinition().getPossibleStates())
                maxLum = Math.max(maxLum, state.getLightEmission());
            if (maxLum > 0) {
                Identifier id = BuiltInRegistries.BLOCK.getKey(block);
                map.merge(id.getNamespace() + ":block/" + id.getPath(), maxLum, Math::max);
            }
        }
        return map;
    }

    private byte[] extractRGBA(int sx, int sy, int sw, int sh) {
        byte[] out = new byte[sw * sh * 4];
        for (int y = 0; y < sh; y++)
            System.arraycopy(albedoRGBA, ((sy + y) * atlasW + sx) * 4, out, y * sw * 4, sw * 4);
        return out;
    }

    /** Reads per-sprite NativeImages and stitches into an RGBA byte[] atlas buffer. */
    public static byte[] stitchAtlasRGBA(TextureAtlas atlas) {
        SpriteAtlasAccessor acc = (SpriteAtlasAccessor) atlas;
        int w = acc.getWidth(), h = acc.getHeight();
        byte[] buf = new byte[w * h * 4];
        for (TextureAtlasSprite sprite : acc.getSprites().values()) {
            NativeImage img = ((SpriteContentsAccessor) sprite.contents()).getImage();
            if (img == null) continue;
            int sx = SpriteAtlasContent.x(sprite), sy = SpriteAtlasContent.y(sprite);
            int sw = sprite.contents().width(), sh = sprite.contents().height();
            for (int y = 0; y < sh; y++)
                for (int x = 0; x < sw; x++) {
                    int abgr = NativeImagePixels.abgr(img, x, y);
                    int idx = ((sy + y) * w + (sx + x)) * 4;
                    buf[idx]     = (byte) (abgr & 0xFF);
                    buf[idx + 1] = (byte) ((abgr >> 8) & 0xFF);
                    buf[idx + 2] = (byte) ((abgr >> 16) & 0xFF);
                    buf[idx + 3] = (byte) ((abgr >> 24) & 0xFF);
                }
        }
        SpriteAtlasContent.extrudePadding(atlas, buf, 4);
        return buf;
    }

    /** Returns the atlas width, or 0 if not initialized. */
    public static int atlasWidth(TextureAtlas atlas) {
        return ((SpriteAtlasAccessor) atlas).getWidth();
    }

    /** Returns the atlas height, or 0 if not initialized. */
    public static int atlasHeight(TextureAtlas atlas) {
        return ((SpriteAtlasAccessor) atlas).getHeight();
    }

    // ====================================================================
    //  Sprite lookup + material factory
    // ====================================================================

    /** Finds a sprite by its content identifier (O(1) HashMap lookup). */
    public SpriteEntry getSpriteById(Identifier id) {
        return spriteById.get(id);
    }

    public static int layerType(String name) {
        return switch (name) {
            case "cutout" -> L_CUTOUT;
            case "cutout_mipped" -> L_CUTOUT_MIPPED;
            case "translucent" -> L_TRANS;
            case "glass" -> L_GLASS;
            case STAINED_GLASS_BLOCK_LAYER -> L_STAINED_GLASS_BLOCK;
            default -> L_SOLID;
        };
    }

    /** Returns the material layer override for glass with distinct atlas optics. */
    public static String glassLayerOverride(BlockState state) {
        if (state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE)) return "glass";
        return state.getBlock() instanceof StainedGlassBlock ? STAINED_GLASS_BLOCK_LAYER : null;
    }

    public static boolean isTranslucentLayer(int layer) {
        return layer == L_TRANS || layer == L_GLASS || layer == L_STAINED_GLASS_BLOCK;
    }

    // ====================================================================
    //  Light color
    // ====================================================================

    public static final float[] FALLBACK_WARM_WHITE = {1f, 0.95f, 0.9f};
    private final Map<String, float[]> spriteLightColors = new HashMap<>();

    private static final float[] WARM   = {1.00f, 0.57f, 0.30f};
    private static final float[] SOUL   = {0.45f, 0.73f, 1.00f};
    private static final float[] RED    = {1.00f, 0.18f, 0.10f};
    private static final float[] WHITE  = {1.00f, 1.00f, 1.00f};
    private static final float[] GREEN  = {0.50f, 0.80f, 0.40f};
    private static final float[] PURPLE = {0.70f, 0.50f, 0.90f};
    private static final float[] SCULK  = {0.20f, 0.50f, 0.80f};
    private static final float[] COPPER = {0.85f, 1.00f, 0.90f};

    /** Curated colors for vanilla light-emitting blocks. */
    private static final Map<String, float[]> KNOWN_LIGHT_COLORS = Map.ofEntries(
            Map.entry("torch", WARM), Map.entry("wall_torch", WARM),
            Map.entry("lantern", WARM), Map.entry("campfire", WARM),
            Map.entry("furnace", WARM), Map.entry("smoker", WARM),
            Map.entry("blast_furnace", WARM), Map.entry("brewing_stand", WARM),
            Map.entry("cave_vines", WARM), Map.entry("cave_vines_plant", WARM),

            Map.entry("soul_torch", SOUL), Map.entry("soul_wall_torch", SOUL),
            Map.entry("soul_lantern", SOUL), Map.entry("soul_campfire", SOUL),

            Map.entry("redstone_torch", RED), Map.entry("redstone_wall_torch", RED),

            Map.entry("end_rod", WHITE), Map.entry("beacon", WHITE),
            Map.entry("conduit", WHITE),

            Map.entry("glow_lichen", GREEN),

            Map.entry("amethyst_cluster", PURPLE), Map.entry("large_amethyst_bud", PURPLE),
            Map.entry("medium_amethyst_bud", PURPLE), Map.entry("small_amethyst_bud", PURPLE),
            Map.entry("respawn_anchor", PURPLE), Map.entry("dragon_egg", PURPLE),

            Map.entry("sculk_sensor", SCULK), Map.entry("calibrated_sculk_sensor", SCULK),
            Map.entry("sculk_catalyst", SCULK),

            Map.entry("copper_bulb", COPPER), Map.entry("waxed_copper_bulb", COPPER),
            Map.entry("exposed_copper_bulb", COPPER), Map.entry("waxed_exposed_copper_bulb", COPPER),
            Map.entry("weathered_copper_bulb", COPPER), Map.entry("waxed_weathered_copper_bulb", COPPER),
            Map.entry("oxidized_copper_bulb", COPPER), Map.entry("waxed_oxidized_copper_bulb", COPPER)
    );

    /**
     * Brightness-weighted average color of the sprite's emissive or albedo pixels.
     * Dark pixels (torch stick) contribute almost nothing; bright pixels (flame) dominate.
     */
    static float[] computeLightColor(byte[] rgba, byte[] emissiveRGBA, int w, int h) {
        byte[] src = (emissiveRGBA != null) ? emissiveRGBA : rgba;
        double rSum = 0, gSum = 0, bSum = 0, wSum = 0;
        for (int i = 0; i < w * h; i++) {
            float r = (src[i * 4] & 0xFF) / 255f;
            float g = (src[i * 4 + 1] & 0xFF) / 255f;
            float b = (src[i * 4 + 2] & 0xFF) / 255f;
            float lum = (r + g + b)/3.0f;
            rSum += r * lum; gSum += g * lum; bSum += b * lum; wSum += lum;
        }
        if (wSum < 0.001) return FALLBACK_WARM_WHITE;
        return new float[]{(float) (rSum / wSum), (float) (gSum / wSum), (float) (bSum / wSum)};
    }

    /**
     * Returns the light color for a block. Lookup order:
     * 1. Curated table (vanilla blocks with known colors)
     * 2. Texture-derived brightness-weighted average (from atlas)
     * 3. Warm white fallback
     */
    public float[] getLightColorForBlock(Block block) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(block);
        String path = id.getPath();

        float[] known = KNOWN_LIGHT_COLORS.get(path);
        if (known != null) return known;

        if (path.contains("candle")) return WARM;

        float[] derived = spriteLightColors.get(id.getNamespace() + ":block/" + path);
        return derived != null ? derived : FALLBACK_WARM_WHITE;
    }

    // ====================================================================
    //  Upload helper
    // ====================================================================

    private KavoraTexture upload(RemixAPI api, String name, byte[] data, int w, int h, int fmt, int purpose) {
        return upload(api, name, data, w, h, fmt, purpose, false);
    }

    private KavoraTexture upload(RemixAPI api, String name, byte[] data, int w, int h, int fmt, int purpose,
                                boolean immutable) {
        ByteBuffer buf = DirectBufferPool.acquire(data.length);
        buf.put(data).flip();
        try {
            KavoraTexture tex = new KavoraTexture(w, h, fmt, purpose, buf, immutable);
            KavoraTextureStore.get().create(tex);
            managedTextures.add(tex);
            return tex;
        } catch (RemixException e) {
            LOGGER.error("Failed to upload texture {}: {}", name, e.getMessage());
            return null;
        }
    }

    private KavoraMaterial createMaterialViaStore(MaterialInfo matInfo) {
        try {
            KavoraMaterial mat = new KavoraMaterial(matInfo, null, null);
            KavoraMaterialStore.get().create(mat);
            managedMaterials.add(mat);
            return mat;
        } catch (RemixException e) {
            LOGGER.error("Failed to create material: {}", e.getMessage());
            return null;
        }
    }

    // ====================================================================
    //  Accessors & lifecycle
    // ====================================================================

    public boolean isInitialized() { return initialized && !sprites.isEmpty(); }
    public KavoraTexture getBlockAtlasTexture() { ensureInit(); return atlasAlbedo; }
    public KavoraTexture getEmissiveAtlasTexture() { ensureInit(); return atlasEmissive; }
    public KavoraTexture getRoughnessAtlasTexture() { ensureInit(); return atlasRoughnessTex; }
    public KavoraTexture getMetallicAtlasTexture() { ensureInit(); return atlasMetallicTex; }
    public KavoraTexture getSssAtlasTexture() { ensureInit(); return atlasSssTex; }
    private void ensureInit() { if (!initialized) initialize(); }

    public void reload() { cleanup(); initialized = initFailed = false; initialize(); }

    public void cleanup() {
        for (KavoraMaterial mat : managedMaterials)
            if (!mat.isDeleted()) mat.destroy();
        managedMaterials.clear();
        for (KavoraTexture tex : managedTextures)
            if (!tex.isDeleted()) tex.destroy();
        managedTextures.clear();
        sprites.clear(); spriteById.clear(); spriteLightColors.clear();
        Arrays.fill(atlasMaterials, null);
        Arrays.fill(atlasMaterialsSSS, null);
        atlasNormalTex = null;
        atlasHeightTex = null;
        atlasRoughnessTex = null;
        atlasMetallicTex = null;
        atlasSssTex = null;
        atlasSssAlbedoTex = null;
        if (api != null) {
            destroyWaterResources(api);
        } else {
            waterSurfaceMaterial = null;
            waterCameraMediumMaterial = null;
            waterWhiteTexture = null;
            waterNormalHandle = 0;
        }
        albedoRGBA = null; initialized = false;
    }
}
