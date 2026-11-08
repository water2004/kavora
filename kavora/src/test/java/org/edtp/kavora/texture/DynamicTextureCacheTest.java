/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.texture;

import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.mojang.blaze3d.platform.NativeImage;

/**
 * Pins how glyph pages are served beside the snapshot cache and which snapshots survive
 * the world clear. A page has no snapshot to capture: it is read live from its mirror,
 * and it belongs to the font rather than to a world. A snapshot follows its texture: it
 * survives while the texture is registered and goes when the texture does.
 */
class DynamicTextureCacheTest {

    @Test
    void unchangedUploadsReuseSnapshotAndChangedUploadsPreservePublishedPixels() {
        Identifier id = Identifier.fromNamespaceAndPath("minecraft", "dynamic/immutable-upload");
        AbstractTexture texture = newTexture();
        DynamicTextureCache.onRegisterTexture(id, texture, null);
        try (NativeImage image = new NativeImage(2, 1, true)) {
            image.setPixel(0, 0, 0xff123456);
            image.setPixel(1, 0, 0xffabcdef);
            DynamicTextureCache.captureFromUpload(texture, image);
            var first = DynamicTextureCache.get(id);
            byte[] published = first.data().clone();
            DynamicTextureCache.captureFromUpload(texture, image);
            assertSame(first, DynamicTextureCache.get(id));
            image.setPixel(1, 0, 0x87654321);
            DynamicTextureCache.captureFromUpload(texture, image);
            var changed = DynamicTextureCache.get(id);
            assertNotSame(first.data(), changed.data());
            assertTrue(changed.version() > first.version());
            assertArrayEquals(published, first.data());
            assertArrayEquals(new byte[] {0x12, 0x34, 0x56, (byte) 0xff, 0x65, 0x43, 0x21, (byte) 0x87}, changed.data());
            DynamicTextureCache.captureFromUpload(texture, image);
            assertSame(changed, DynamicTextureCache.get(id));
        } finally {
            DynamicTextureCache.release(id);
        }
    }

    @Test
    void aGlyphPageIsReadLiveAndOutlivesClear() {
        Identifier id = Identifier.fromNamespaceAndPath("minecraft", "default/0");
        GlyphPageMirror page = new GlyphPageMirror(false);
        DynamicTextureCache.onRegisterGlyphPage(id, page);

        assertTrue(DynamicTextureCache.isGlyphPage(id));
        DynamicTextureCache.Pixels empty = DynamicTextureCache.get(id);
        assertSame(page.data(), empty.data());
        assertEquals(GlyphPageMirror.PAGE_SIZE, empty.width());
        assertEquals(GlyphPageMirror.PAGE_SIZE, empty.height());
        assertEquals(0L, empty.version());

        page.blit(0, 0, 1, 1, 4, ByteBuffer.wrap(new byte[] {1, 2, 3, 4}), 4);
        DynamicTextureCache.Pixels written = DynamicTextureCache.get(id);
        assertSame(page.data(), written.data(), "the mirror is read through, not copied");
        assertEquals(page.version(), written.version());

        DynamicTextureCache.clear();
        assertTrue(DynamicTextureCache.isGlyphPage(id));
        assertSame(page.data(), DynamicTextureCache.get(id).data());
    }

    @Test
    void aPageReRegisteredUnderItsIdReplacesItsPredecessor() {
        Identifier id = Identifier.fromNamespaceAndPath("minecraft", "default/1");
        GlyphPageMirror replaced = new GlyphPageMirror(false);
        GlyphPageMirror fresh = new GlyphPageMirror(true);
        DynamicTextureCache.onRegisterGlyphPage(id, replaced);

        DynamicTextureCache.onRegisterGlyphPage(id, fresh);

        assertSame(fresh.data(), DynamicTextureCache.get(id).data());
    }

    @Test
    void clearKeepsTheSnapshotAndRegistrationOfALiveTexture() {
        Identifier id = Identifier.fromNamespaceAndPath("minecraft", "skins/live");
        AbstractTexture texture = newTexture();
        DynamicTextureCache.onRegisterTexture(id, texture, null);
        try (NativeImage image = new NativeImage(1, 1, true)) {
            assertTrue(DynamicTextureCache.captureFromUpload(texture, image));
        }
        DynamicTextureCache.Pixels captured = DynamicTextureCache.get(id);
        assertNotNull(captured);

        DynamicTextureCache.clear();

        DynamicTextureCache.Pixels kept = DynamicTextureCache.get(id);
        assertNotNull(kept, "a skin is uploaded once per session and cannot be re-captured");
        assertSame(captured.data(), kept.data());
        assertEquals(captured.version(), kept.version());
        try (NativeImage image = new NativeImage(1, 1, true)) {
            assertTrue(DynamicTextureCache.captureFromUpload(texture, image),
                    "a registration belongs to the live texture, not to the cleared snapshots");
        }
    }

    @Test
    void releaseDropsTheSnapshotWhileTheTextureObjectIsStillLive() {
        Identifier id = Identifier.fromNamespaceAndPath("minecraft", "dynamic/closed-map");
        AbstractTexture texture = newTexture();
        DynamicTextureCache.onRegisterTexture(id, texture, null);
        DynamicTextureCache.capture(id, new DynamicTextureCache.Pixels(new byte[4], 1, 1, 1));
        assertNotNull(DynamicTextureCache.get(id));

        DynamicTextureCache.release(id);
        DynamicTextureCache.clear();

        assertNull(DynamicTextureCache.get(id));
    }

    @Test
    void clearKeepsAReplacementAfterItsPredecessorWasReleased() {
        Identifier id = Identifier.fromNamespaceAndPath("minecraft", "dynamic/replaced-map");
        AbstractTexture closed = newTexture();
        DynamicTextureCache.onRegisterTexture(id, closed, null);
        DynamicTextureCache.capture(id, new DynamicTextureCache.Pixels(new byte[4], 1, 1, 1));
        DynamicTextureCache.release(id);
        try (AbstractTexture live = newTexture()) {
            DynamicTextureCache.onRegisterTexture(id, live, null);
            DynamicTextureCache.capture(id, new DynamicTextureCache.Pixels(new byte[4], 1, 1, 2));
            DynamicTextureCache.Pixels captured = DynamicTextureCache.get(id);

            DynamicTextureCache.clear();

            assertSame(captured, DynamicTextureCache.get(id));
        }
    }

    @Test
    void releaseDropsSnapshotsWithoutWaitingForCollection() {
        Identifier closedId = Identifier.fromNamespaceAndPath("minecraft", "dynamic/pruned-map");
        AbstractTexture closed = newTexture();
        DynamicTextureCache.onRegisterTexture(closedId, closed, null);
        DynamicTextureCache.capture(closedId, new DynamicTextureCache.Pixels(new byte[4], 1, 1, 1));
        DynamicTextureCache.release(closedId);
        List<AbstractTexture> live = new ArrayList<>();
        for (int i = 0; i < DynamicTextureCache.capacity(); i++) {
            AbstractTexture texture = newTexture();
            live.add(texture);
            Identifier id = Identifier.fromNamespaceAndPath("minecraft", "skins/closed-map-neighbour-" + i);
            try (NativeImage image = new NativeImage(1, 1, true)) {
                DynamicTextureCache.onRegisterTexture(id, texture, image);
            }
        }

        assertNull(DynamicTextureCache.get(closedId));
        assertEquals(DynamicTextureCache.capacity(), live.size());
    }

    /**
     * Relies on the JVM collecting an unreachable texture; the assumption below skips the
     * test rather than failing it when a bounded {@code System.gc()} loop does not.
     */
    @Test
    void clearDropsTheSnapshotOfATextureTheGameReleased() throws InterruptedException {
        Identifier id = Identifier.fromNamespaceAndPath("minecraft", "skins/released");
        AbstractTexture texture = newTexture();
        DynamicTextureCache.onRegisterTexture(id, texture, null);
        try (NativeImage image = new NativeImage(1, 1, true)) {
            assertTrue(DynamicTextureCache.captureFromUpload(texture, image));
        }
        assertNotNull(DynamicTextureCache.get(id));

        WeakReference<AbstractTexture> collected = new WeakReference<>(texture);
        texture = null;
        for (int attempt = 0; attempt < 20 && collected.get() != null; attempt++) {
            System.gc();
            Thread.sleep(10L);
        }
        assumeTrue(collected.get() == null, "the JVM did not collect the released texture");

        DynamicTextureCache.clear();

        assertNull(DynamicTextureCache.get(id),
                "a snapshot vanilla can no longer refresh has nothing to keep it alive");
    }

    @Test
    void unknownIdsHaveNoPixels() {
        Identifier id = Identifier.fromNamespaceAndPath("minecraft", "textures/entity/cow/cow.png");

        assertNull(DynamicTextureCache.get(id));
        assertFalse(DynamicTextureCache.isGlyphPage(id));
    }

    /**
     * A downloaded skin uploads once per session, so a snapshot the capacity
     * bound dropped would be gone until restart. Every texture here stays
     * strongly referenced, so none has been released and none may be pruned.
     */
    @Test
    void capacityPruningNeverDropsTheSnapshotOfALiveTexture() {
        int count = DynamicTextureCache.capacity() + 8;
        List<AbstractTexture> live = new ArrayList<>(count);
        List<Identifier> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Identifier id = Identifier.fromNamespaceAndPath("minecraft", "skins/crowd-" + i);
            AbstractTexture texture = newTexture();
            live.add(texture);
            ids.add(id);
            DynamicTextureCache.onRegisterTexture(id, texture, null);
            try (NativeImage image = new NativeImage(1, 1, true)) {
                assertTrue(DynamicTextureCache.captureFromUpload(texture, image));
            }
        }

        for (Identifier id : ids) {
            assertNotNull(DynamicTextureCache.get(id), id + " was pruned while its texture is live");
        }
        assertEquals(count, live.size());
    }

    private static AbstractTexture newTexture() {
        return new AbstractTexture() {};
    }
}
