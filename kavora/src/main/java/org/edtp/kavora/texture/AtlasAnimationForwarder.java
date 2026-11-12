/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.texture;

import org.edtp.kavora.sdk.RemixAPI;
import org.edtp.kavora.sdk.TextureBatchEntry;
import org.edtp.kavora.sdk.TextureFormat;
import org.edtp.kavora.sdk.TexturePatch;
import org.edtp.kavora.sdk.TexturePatchInfo;
import org.edtp.kavora.util.DirectBufferPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.Identifier;

/**
 * Collects animated sprite upload patches each tick and flushes them to Remix
 * in a single batched call before present.
 *
 * <p>LabPBR-aware: sprites that have {@code _n} / {@code _s} siblings are
 * re-processed on every animation frame so AO (from {@code _n.b}) and the
 * animated LabPBR material channels in {@code _s} stay in phase with albedo.
 * Without this, later albedo frames would overwrite the AO-modulated atlas
 * while roughness, metallic, SSS, and emissive atlases stayed stuck on the
 * init-time sidecar frame. Non-LabPBR sprites are copied as albedo only.
 *
 * <p>All patches for one frame across every animated atlas texture go out in
 * one {@code remixapi_UpdateTextureFromMemory} call using the multi-handle
 * batch API. This amortizes the FFM crossing and, on the dxvk side, lets mip
 * regeneration be scoped to the dirty rect per atlas instead of regenerating
 * the whole atlas once per atlas.
 */
public final class AtlasAnimationForwarder {

    private static final Logger LOGGER = LoggerFactory.getLogger(AtlasAnimationForwarder.class);

    // Gate on patch queueing: read by addAnimationPatch and toggled by
    // init/clear/pause/resume, all of which run on the render thread. Each
    // toggle is sequenced against the state it guards at its own call site.
    private static volatile boolean active = false;

    private static final List<PatchRecord> pendingPatches = new ArrayList<>();
    private static Map<String, BlockTextureManager.AnimatedSpriteLabPBR> labPBRMap;
    private static Set<Identifier> blockAtlasSpriteIds;

    private static final class PatchRecord {
        final Identifier id;
        final int dstX, dstY, width, height, rowPitch, paddingX, paddingY;
        final ByteBuffer ownedPixels;
        final FrameSampling sampling;
        PatchRecord(Identifier id, int dstX, int dstY, int width, int height,
                int paddingX, int paddingY, ByteBuffer pixels, FrameSampling sampling) {
            this.id = id;
            this.dstX = dstX;
            this.dstY = dstY;
            this.width = width;
            this.height = height;
            this.rowPitch = width * 4;
            this.paddingX = paddingX;
            this.paddingY = paddingY;
            this.ownedPixels = pixels;
            this.sampling = sampling;
        }
    }

    private static final class FrameOrigin {
        final int x;
        final int y;

        FrameOrigin(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    private static final class FrameSampling {
        final FrameOrigin current;
        final FrameOrigin next;
        final float nextBlend;

        FrameSampling(FrameOrigin current, FrameOrigin next, float nextBlend) {
            this.current = current;
            this.next = next;
            this.nextBlend = nextBlend;
        }
    }

    public static void init(
            Map<String, BlockTextureManager.AnimatedSpriteLabPBR> labPBR,
            Set<Identifier> blockAtlasSprites) {
        labPBRMap = labPBR;
        blockAtlasSpriteIds = Set.copyOf(blockAtlasSprites);
        discardPatches();
        // Flip active on LAST so any addAnimationPatch probe that races initialization
        // sees a fully constructed forwarder (or no forwarder at all).
        active = true;
        LOGGER.info("AtlasAnimationForwarder initialized ({} LabPBR sprites, {} atlas sprites)",
                    labPBR.size(), blockAtlasSpriteIds.size());
    }

    static boolean acceptsAnimation(Identifier atlas, Identifier sprite) {
        return active && BlockTextureManager.BLOCK_ATLAS_ID.equals(atlas)
                && blockAtlasSpriteIds != null && blockAtlasSpriteIds.contains(sprite);
    }

    /** Mirrors the same frame indices and blend used by Minecraft's atlas render pass. */
    public static void addAnimationPatch(net.minecraft.client.renderer.texture.TextureAtlasSprite sprite,
            int currentFrame, int nextFrame, float blend) {
        if (!acceptsAnimation(sprite.atlasLocation(), sprite.contents().name())) return;
        com.mojang.blaze3d.platform.NativeImage image =
                ((org.edtp.kavora.mixin.SpriteContentsAccessor) sprite.contents()).getImage();
        int width = sprite.contents().width(), height = sprite.contents().height();
        int columns = image.getWidth() / width;
        int srcX = (currentFrame % columns) * width, srcY = (currentFrame / columns) * height;
        int nextX = (nextFrame % columns) * width, nextY = (nextFrame / columns) * height;
        ByteBuffer pixels = DirectBufferPool.acquire(width * height * 4);
        try {
            NativeImagePixels.copyFrame(image, srcX, srcY, nextX, nextY, width, height, blend, pixels);
        } catch (RuntimeException | Error failure) {
            DirectBufferPool.release(pixels);
            throw failure;
        }
        pixels.flip();
        int contentX = SpriteAtlasContent.x(sprite), contentY = SpriteAtlasContent.y(sprite);
        pendingPatches.add(new PatchRecord(sprite.contents().name(), contentX, contentY,
                width, height, contentX - sprite.getX(), contentY - sprite.getY(), pixels,
                new FrameSampling(new FrameOrigin(srcX, srcY), new FrameOrigin(nextX, nextY), blend)));
    }

    private static void discardPatches() {
        for (PatchRecord patch : pendingPatches) {
            if (patch.ownedPixels != null) DirectBufferPool.release(patch.ownedPixels);
        }
        pendingPatches.clear();
    }

    /**
     * Builds one multi-handle batch covering animated albedo and LabPBR atlas
     * sidecars, then issues a single {@code remixapi_UpdateTextureFromMemory}
     * call. LabPBR data is applied per patch using pre-computed tables cached
     * at init.
     *
     * @param api            the Remix API handle
     * @param albedoHandle   handle of the albedo atlas texture (required)
     * @param emissiveHandle handle of the emissive atlas, or 0 if none exists
     * @param roughnessHandle handle of the roughness atlas, or 0 if none exists
     * @param metallicHandle handle of the metallic atlas, or 0 if none exists
     * @param sssHandle      handle of the SSS atlas, or 0 if none exists
     * @param lava           dimension-dependent emission for the shared atlas
     */
    static void flush(RemixAPI api, long albedoHandle, long emissiveHandle,
                      long roughnessHandle, long metallicHandle, long sssHandle, LavaEmission lava) {
        int count = pendingPatches.size();
        if (count == 0 && (emissiveHandle == 0 || !lava.hasDirtyRegions())) {
            return;
        }

        List<ByteBuffer> ownedBuffers = new ArrayList<>(count * 2);

        try {
            TexturePatch[] albedoPatches = new TexturePatch[count];
            List<TexturePatch> roughnessPatches = new ArrayList<>();
            List<TexturePatch> metallicPatches = new ArrayList<>();
            List<TexturePatch> emissivePatches = new ArrayList<>();
            List<TexturePatch> sssPatches = new ArrayList<>();

            for (int i = 0; i < count; i++) {
                PatchRecord p = pendingPatches.get(i);
                BlockTextureManager.AnimatedSpriteLabPBR pbr =
                    (labPBRMap != null) ? labPBRMap.get(p.id.toString()) : null;

                TexturePatch albedo = allocatePatch(p, TextureFormat.RGBA8_UNORM, ownedBuffers);
                TexturePatch roughness = pbr != null && pbr.animatedRoughness && roughnessHandle != 0
                        ? allocatePatch(p, TextureFormat.R8_UNORM, ownedBuffers) : null;
                TexturePatch metallic = pbr != null && pbr.animatedMetallic && metallicHandle != 0
                        ? allocatePatch(p, TextureFormat.R8_UNORM, ownedBuffers) : null;
                TexturePatch thickness = pbr != null && pbr.hasSss && pbr.animatedSss && sssHandle != 0
                        ? allocatePatch(p, TextureFormat.R8_UNORM, ownedBuffers) : null;
                boolean authoredEmission = pbr != null && pbr.hasPerPixelEmissive;
                BuiltinEmission.Mask mask = authoredEmission ? null : BuiltinEmission.mask(p.id);
                TexturePatch emissive = emissiveHandle != 0 && (authoredEmission || mask != null)
                        ? allocatePatch(p, TextureFormat.RGBA8_UNORM, ownedBuffers) : null;
                FrameSampling sampling = p.sampling;
                AnimatedTexturePatches.compose(p.ownedPixels, p.rowPitch, p.width, p.height, pbr,
                        sampling.current.x, sampling.current.y, sampling.next.x, sampling.next.y, sampling.nextBlend,
                        mask, p.paddingX, p.paddingY, albedo, emissive, roughness, metallic, thickness);
                albedoPatches[i] = albedo;
                if (roughness != null) roughnessPatches.add(roughness);
                if (metallic != null) metallicPatches.add(metallic);
                if (thickness != null) sssPatches.add(thickness);
                if (emissive != null) {
                    lava.applyAnimation(p.id, emissive);
                    emissivePatches.add(emissive);
                }
            }

            if (emissiveHandle != 0) lava.appendDirtyPatches(emissivePatches, ownedBuffers);

            // One batch per frame: the runtime handles each entry's texture
            // independently, so all five channels travel in a single call.
            List<TextureBatchEntry> entries = new ArrayList<>(5);
            addBatchEntry(entries, albedoHandle, albedoPatches);
            addBatchEntry(entries, roughnessHandle, roughnessPatches);
            addBatchEntry(entries, metallicHandle, metallicPatches);
            addBatchEntry(entries, emissiveHandle, emissivePatches);
            addBatchEntry(entries, sssHandle, sssPatches);

            TexturePatchInfo info = new TexturePatchInfo();
            info.entries = entries.toArray(new TextureBatchEntry[0]);
            api.updateTextureFromMemory(info);

        } catch (Exception e) {
            lava.invalidate();
            LOGGER.error("Error flushing animation patches", e);
        } finally {
            for (ByteBuffer buffer : ownedBuffers) {
                DirectBufferPool.release(buffer);
            }
            discardPatches();
        }
    }

    private static TexturePatch allocatePatch(PatchRecord p, int format, List<ByteBuffer> ownedBuffers) {
        TexturePatch patch = new TexturePatch();
        int channels = format == TextureFormat.R8_UNORM ? 1 : 4;
        patch.dstX = p.dstX - p.paddingX;
        patch.dstY = p.dstY - p.paddingY;
        patch.width = p.width + p.paddingX * 2;
        patch.height = p.height + p.paddingY * 2;
        patch.rowPitch = patch.width * channels;
        patch.format = format;
        patch.pixelData = DirectBufferPool.acquire(patch.rowPitch * patch.height);
        ownedBuffers.add(patch.pixelData);
        return patch;
    }

    private static void addBatchEntry(List<TextureBatchEntry> entries, long handle, TexturePatch[] patches) {
        if (handle == 0 || patches.length == 0) {
            return;
        }
        TextureBatchEntry entry = new TextureBatchEntry();
        entry.handle = handle;
        entry.patches = patches;
        entries.add(entry);
    }

    private static void addBatchEntry(List<TextureBatchEntry> entries, long handle, List<TexturePatch> patches) {
        if (handle == 0 || patches.isEmpty()) {
            return;
        }
        addBatchEntry(entries, handle, patches.toArray(new TexturePatch[0]));
    }

    public static void clear() {
        // Flip active OFF first so a concurrent addAnimationPatch drops its record
        // rather than inserting into a list we're about to wipe.
        active = false;
        discardPatches();
        labPBRMap = null;
        blockAtlasSpriteIds = null;
        LOGGER.info("AtlasAnimationForwarder cleared");
    }

    /**
     * Deactivates queueing and drops any pending records without wiping
     * {@link #labPBRMap} / {@link #blockAtlasSpriteIds}.
     * Use for reversible pauses like the RTX-toggle path where
     * {@link #resume()} should be a cheap flip without a resource-pack reload.
     */
    public static void pause() {
        active = false;
        discardPatches();
    }

    public static void resume() {
        active = blockAtlasSpriteIds != null;
    }

    private AtlasAnimationForwarder() {}
}
