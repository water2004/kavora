/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.texture;

import org.edtp.kavora.util.DirectBufferPool;
import org.edtp.kavora.mixin.SpriteAtlasAccessor;
import org.edtp.kavora.asset.KavoraTextureStore;
import org.edtp.kavora.asset.KavoraTexture;
import org.edtp.kavora.asset.KavoraMaterialStore;
import org.edtp.kavora.asset.KavoraMaterial;
import org.edtp.kavora.KavoraRenderer;
import com.mojang.blaze3d.platform.NativeImage;
import org.edtp.kavora.sdk.MaterialInfo;
import org.edtp.kavora.sdk.MaterialInfoOpaqueEXT;
import org.edtp.kavora.sdk.RemixAPI;
import org.edtp.kavora.sdk.RemixException;
import org.edtp.kavora.sdk.TextureFormat;
import org.edtp.kavora.sdk.TexturePurpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Captures the particle sprite atlas and builds parallel LabPBR atlas textures
 * plus a single atlas-wide {@link MaterialInfoOpaqueEXT}. Alpha testing and
 * blending follow the particle layer, supplied per instance
 * by {@link KavoraParticleRenderer} through {@link InstanceInfoBlendEXT}, so one
 * material serves both the alpha-tested and the blended particle passes.
 *
 * <p>Mirrors {@link BlockTextureManager#buildAtlasMaterials} but operates on
 * the particle atlas ({@link TextureAtlas#LOCATION_PARTICLES}).
 *
 * <p>Block-atlas particles reuse materials from {@link BlockTextureManager}
 * rather than creating new ones here.
 */
public class ParticleAtlasManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ParticleAtlasManager.class);

    private final KavoraRenderer renderer;
    private final Identifier atlasId;
    private final Map<Identifier, ParticleAtlasManager> additionalAtlases = new java.util.HashMap<>();

    private final List<KavoraTexture> managedTextures = new ArrayList<>();
    private final List<KavoraMaterial> managedMaterials = new ArrayList<>();

    private KavoraMaterial material;

    private boolean initialized;
    private boolean initFailed;

    public ParticleAtlasManager(KavoraRenderer renderer) {
        this(renderer, TextureAtlas.LOCATION_PARTICLES);
    }

    private ParticleAtlasManager(KavoraRenderer renderer, Identifier atlasId) {
        this.renderer = renderer;
        this.atlasId = atlasId;
    }

    /** Lookup-only: particle preparation reads this from its worker thread. */
    public KavoraMaterial getMaterialForAtlas(Identifier id) {
        if (atlasId.equals(id)) return material;
        ParticleAtlasManager manager = additionalAtlases.get(id);
        return manager == null ? null : manager.material;
    }

    public void initialize() {
        if (initialized || initFailed) return;
        try {
            RemixAPI api = renderer.getRemixApi();
            Minecraft client = Minecraft.getInstance();
            if (api == null || client == null || client.getTextureManager() == null) return;

            AbstractTexture tex = client.getTextureManager()
                    .getTexture(atlasId);
            if (!(tex instanceof TextureAtlas atlas)) return;

            SpriteAtlasAccessor acc = (SpriteAtlasAccessor) atlas;
            int atlasW = acc.getWidth();
            int atlasH = acc.getHeight();
            if (atlasW == 0 || atlasH == 0) {
                LOGGER.warn("Particle atlas dimensions are zero, deferring init");
                return;
            }

            Map<Identifier, TextureAtlasSprite> sprites = acc.getSprites();
            if (sprites == null || sprites.isEmpty()) {
                LOGGER.warn("Particle atlas has no sprites, deferring init");
                return;
            }

            byte[] albedoRGBA = BlockTextureManager.stitchAtlasRGBA(atlas);

            int pixels = atlasW * atlasH;
            byte[] normalAtlas    = new byte[pixels * 4];
            byte[] heightAtlas    = new byte[pixels];
            byte[] roughnessAtlas = new byte[pixels];
            byte[] metallicAtlas  = new byte[pixels];
            byte[] emissiveAtlas  = new byte[pixels * 4];

            Arrays.fill(heightAtlas, (byte) 255);
            Arrays.fill(roughnessAtlas, (byte) 180);
            for (int i = 0; i < pixels; i++) {
                int idx4 = i * 4;
                normalAtlas[idx4]     = (byte) 128;
                normalAtlas[idx4 + 1] = (byte) 128;
                normalAtlas[idx4 + 2] = (byte) 255;
                normalAtlas[idx4 + 3] = (byte) 255;
            }

            boolean hasAnyNormal = false, hasAnyHeight = false, hasAnyEmissive = false;
            ResourceManager rm = client.getResourceManager();
            LabPBRConverter.VariantCache labPBRVariants = LabPBRConverter.buildVariantCache(rm);

            for (var entry : sprites.entrySet()) {
                Identifier spriteId = entry.getKey();
                TextureAtlasSprite sprite = entry.getValue();
                int pixX = SpriteAtlasContent.x(sprite), pixY = SpriteAtlasContent.y(sprite);
                int pixW = sprite.contents().width();
                int pixH = sprite.contents().height();

                Identifier texId = atlasId.equals(TextureAtlas.LOCATION_PARTICLES)
                        ? Identifier.fromNamespaceAndPath(spriteId.getNamespace(), "particle/" + spriteId.getPath())
                        : spriteId;
                NativeImage nImg = labPBRVariants.loadVariant(texId, "_n");
                NativeImage sImg = labPBRVariants.loadVariant(texId, "_s");
                try {
                    if (nImg != null) {
                        LabPBRConverter.blitNormal(normalAtlas, atlasW, nImg, pixX, pixY, pixW, pixH);
                        LabPBRConverter.blitHeight(heightAtlas, atlasW, nImg, pixX, pixY, pixW, pixH);
                        LabPBRConverter.applyAO(albedoRGBA, atlasW, nImg, pixX, pixY, pixW, pixH);
                        hasAnyNormal = true;
                        hasAnyHeight = true;
                    }
                    if (sImg != null) {
                        LabPBRConverter.blitRoughness(roughnessAtlas, atlasW, sImg, pixX, pixY, pixW, pixH);
                        LabPBRConverter.blitMetallic(metallicAtlas, atlasW, sImg, pixX, pixY, pixW, pixH);
                    }
                    if (sImg != null && LabPBRConverter.hasEmissiveData(sImg)) {
                        LabPBRConverter.blitEmissive(emissiveAtlas, atlasW, albedoRGBA, sImg,
                                pixX, pixY, pixW, pixH, 0);
                        hasAnyEmissive = true;
                    }
                } finally {
                    if (nImg != null) nImg.close();
                    if (sImg != null) sImg.close();
                }
            }

            SpriteAtlasContent.extrudePadding(atlas, albedoRGBA, 4);
            SpriteAtlasContent.extrudePadding(atlas, normalAtlas, 4);
            SpriteAtlasContent.extrudePadding(atlas, heightAtlas, 1);
            SpriteAtlasContent.extrudePadding(atlas, roughnessAtlas, 1);
            SpriteAtlasContent.extrudePadding(atlas, metallicAtlas, 1);
            SpriteAtlasContent.extrudePadding(atlas, emissiveAtlas, 4);
            KavoraTexture albedoTex = uploadTexture("albedo",
                    albedoRGBA, atlasW, atlasH, TextureFormat.RGBA8_UNORM, TexturePurpose.COLOR);

            KavoraTexture normalTex = hasAnyNormal
                    ? uploadTexture("normal", normalAtlas, atlasW, atlasH,
                            TextureFormat.RGBA8_UNORM, TexturePurpose.NORMAL_DX) : null;
            KavoraTexture heightTex = hasAnyHeight
                    ? uploadTexture("height", heightAtlas, atlasW, atlasH,
                            TextureFormat.R8_UNORM, TexturePurpose.HEIGHTMAP) : null;
            KavoraTexture roughnessTex = uploadTexture("roughness",
                    roughnessAtlas, atlasW, atlasH, TextureFormat.R8_UNORM, TexturePurpose.DATA);
            KavoraTexture metallicTex = uploadTexture("metallic",
                    metallicAtlas, atlasW, atlasH, TextureFormat.R8_UNORM, TexturePurpose.DATA);
            KavoraTexture emissiveTex = hasAnyEmissive
                    ? uploadTexture("emissive", emissiveAtlas, atlasW, atlasH,
                            TextureFormat.RGBA8_UNORM, TexturePurpose.COLOR) : null;

            if (albedoTex == null) {
                LOGGER.error("Failed to upload particle atlas albedo, aborting");
                initFailed = true;
                return;
            }

            material = buildOpaqueMaterial(albedoTex, normalTex, heightTex,
                    roughnessTex, metallicTex, emissiveTex);

            if (material == null) {
                LOGGER.error("Failed to create particle material(s)");
                initFailed = true;
                return;
            }

            if (atlasId.equals(TextureAtlas.LOCATION_PARTICLES)) {
                ParticleAtlasManager items = new ParticleAtlasManager(renderer, TextureAtlas.LOCATION_ITEMS);
                items.initialize();
                additionalAtlases.put(TextureAtlas.LOCATION_ITEMS, items);
            }
            initialized = true;
            LOGGER.info("Particle atlas initialized ({}x{}, {} sprites, normal={}, height={}, emissive={})",
                    atlasW, atlasH, sprites.size(), hasAnyNormal, hasAnyHeight, hasAnyEmissive);

        } catch (Exception e) {
            LOGGER.error("Particle atlas init failed", e);
            initFailed = true;
        }
    }

    private KavoraMaterial buildOpaqueMaterial(KavoraTexture albedo, KavoraTexture normal,
                                              KavoraTexture height, KavoraTexture roughness,
                                              KavoraTexture metallic, KavoraTexture emissive) {
        MaterialInfoOpaqueEXT.Builder ob = MaterialInfoOpaqueEXT.builder()
                .albedoConstant(1f, 1f, 1f)
                .roughnessConstant(1f)
                .metallicConstant(0f)
                .useDrawCallAlphaState(true);
        if (roughness != null) ob.roughnessTexture(roughness.getHandle());
        if (metallic != null)  ob.metallicTexture(metallic.getHandle());
        if (height != null)    ob.heightTexture(height.getHandle()).displaceIn(0.02f);

        MaterialInfo.Builder mb = MaterialInfo.builder()
                .albedoTexture(albedo.getHandle())
                .filterMode(0);
        if (normal != null)   mb.normalTexture(normal.getHandle());
        if (emissive != null) mb.emissiveTexture(emissive.getHandle()).emissiveIntensity(40.0f);

        return registerMaterial(mb.opaqueExt(ob.build()).build());
    }

    public KavoraMaterial getMaterial()  { return material; }
    public boolean isInitialized()     { return initialized; }

    private KavoraTexture uploadTexture(String name, byte[] data, int w, int h, int fmt, int purpose) {
        ByteBuffer buf = DirectBufferPool.acquire(data.length);
        buf.put(data).flip();
        try {
            KavoraTexture tex = new KavoraTexture(w, h, fmt, purpose, buf, true);
            // The texture owns the buffer from here: KavoraAssetBase disposes it after a
            // successful create and on the create failure path alike, so releasing it
            // here as well would release it twice.
            KavoraTextureStore.get().create(tex);
            managedTextures.add(tex);
            return tex;
        } catch (RemixException e) {
            LOGGER.error("Failed to upload particle texture '{}': {}", name, e.getMessage());
            return null;
        }
    }

    private KavoraMaterial registerMaterial(MaterialInfo matInfo) {
        try {
            KavoraMaterial mat = new KavoraMaterial(matInfo);
            KavoraMaterialStore.get().create(mat);
            managedMaterials.add(mat);
            return mat;
        } catch (RemixException e) {
            LOGGER.error("Failed to create particle material: {}", e.getMessage());
            return null;
        }
    }

    public void cleanup() {
        for (ParticleAtlasManager manager : additionalAtlases.values()) manager.cleanup();
        additionalAtlases.clear();
        for (KavoraMaterial mat : managedMaterials)
            if (!mat.isDeleted()) mat.destroy();
        managedMaterials.clear();

        for (KavoraTexture tex : managedTextures)
            if (!tex.isDeleted()) tex.destroy();
        managedTextures.clear();

        material = null;
        initialized = false;
        initFailed = false;
    }
}
