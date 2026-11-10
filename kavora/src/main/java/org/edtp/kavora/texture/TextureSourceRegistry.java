/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.texture;

import com.mojang.blaze3d.platform.NativeImage;

import com.mojang.renderpearl.api.textures.GpuTexture;
import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.HashMap;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;

/** Associates device-independent textures with the CPU bytes submitted by Minecraft. */
public final class TextureSourceRegistry {
    private static final Map<GpuTexture, Identifier> IDS = new IdentityHashMap<>();
    private static final java.util.Set<GpuTexture> SPRITE_ATLASES =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<AbstractTexture, Identifier> OWNERS = new java.util.WeakHashMap<>();
    private static final Map<Identifier, WeakReference<TextureAtlas>> ATLASES = new java.util.WeakHashMap<>();
    private static final ReferenceQueue<GpuTexture> RELEASED = new ReferenceQueue<>();
    private static final Map<Object, DynamicTextureCache.Pixels> PIXELS = new HashMap<>();
    private static final TextureLookup LOOKUP = new TextureLookup();

    private static final class TextureKey extends WeakReference<GpuTexture> {
        private final int hash;
        TextureKey(GpuTexture texture) {
            super(texture, RELEASED);
            hash = System.identityHashCode(texture);
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return this == other || (other instanceof TextureKey key && get() != null && get() == key.get());
        }
    }

    private static final class TextureLookup {
        GpuTexture texture;
        @Override public int hashCode() { return System.identityHashCode(texture); }
        @Override public boolean equals(Object other) {
            return other instanceof TextureKey key && key.get() == texture;
        }
    }

    private static DynamicTextureCache.Pixels findPixels(GpuTexture texture) {
        Object key;
        while ((key = RELEASED.poll()) != null) PIXELS.remove(key);
        LOOKUP.texture = texture;
        DynamicTextureCache.Pixels pixels = PIXELS.get(LOOKUP);
        LOOKUP.texture = null;
        return pixels;
    }
    private static final Map<GpuTexture, GlyphPageMirror> GLYPHS = new IdentityHashMap<>();

    public static synchronized void register(Identifier id, AbstractTexture texture, GpuTexture gpu) {
        OWNERS.put(texture, id);
        if (texture instanceof TextureAtlas atlas) ATLASES.put(id, new WeakReference<>(atlas));
        bind(texture, gpu);
    }

    /** Returns an atlas Minecraft already registered, without loading or uploading a new texture. */
    public static synchronized TextureAtlas registeredAtlas(Identifier id) {
        var reference = ATLASES.get(id);
        return reference == null ? null : reference.get();
    }

    /** Rebinds a re-created device texture while retaining the Minecraft resource identity. */
    public static synchronized void bind(AbstractTexture owner, GpuTexture gpu) {
        if (gpu == null) return;
        if (owner instanceof TextureAtlas) registerSpriteAtlas(gpu);
        Identifier id = OWNERS.get(owner);
        if (id == null || id.equals(IDS.get(gpu))) return;
        IDS.put(gpu, id);
        if (SPRITE_ATLASES.contains(gpu)) return;
        DynamicTextureCache.onRegisterTexture(id, owner, null);
        DynamicTextureCache.Pixels pixels = findPixels(gpu);
        if (pixels != null) DynamicTextureCache.capture(id, pixels);
    }

    public static synchronized Identifier identifier(GpuTexture texture) {
        return IDS.get(texture);
    }

    public static synchronized DynamicTextureCache.Pixels pixels(GpuTexture texture) {
        GlyphPageMirror page = GLYPHS.get(texture);
        return page == null ? findPixels(texture) : new DynamicTextureCache.Pixels(
                page.data(), GlyphPageMirror.PAGE_SIZE, GlyphPageMirror.PAGE_SIZE, page.version());
    }

    public static synchronized void registerGlyph(GpuTexture texture, GlyphPageMirror mirror) {
        GLYPHS.put(texture, mirror);
    }

    /** Mirrors a bitmap glyph from the original image used for Minecraft's GPU buffer copy. */
    public static synchronized void uploadBitmapGlyph(GpuTexture texture, NativeImage image,
            int sourceX, int sourceY, int x, int y, int width, int height) {
        GlyphPageMirror page = GLYPHS.get(texture);
        if (page == null) return;
        int channels = image.format().components();
        int rowPitch = image.getWidth() * channels;
        ByteBuffer pixels = image.getPixelBytes();
        pixels.position(pixels.position() + sourceY * rowPitch + sourceX * channels);
        page.blit(x, y, width, height, channels, pixels, rowPitch);
    }

    /** Atlas pixels remain in SpriteContents and use the atlas-specific capture and animation hooks. */
    public static synchronized void registerSpriteAtlas(GpuTexture texture) {
        if (!SPRITE_ATLASES.add(texture)) return;
        LOOKUP.texture = texture;
        PIXELS.remove(LOOKUP);
        LOOKUP.texture = null;
        Identifier id = IDS.get(texture);
        if (id != null) DynamicTextureCache.release(id);
    }

    public static synchronized void release(GpuTexture texture) {
        GLYPHS.remove(texture);
        SPRITE_ATLASES.remove(texture);
        Identifier id = IDS.remove(texture);
        LOOKUP.texture = texture;
        PIXELS.remove(LOOKUP);
        LOOKUP.texture = null;
        if (id != null) DynamicTextureCache.release(id);
    }

    /** Copies the exact mip-zero upload window before Minecraft may free its source. */
    public static synchronized void upload(GpuTexture texture, ByteBuffer source,
            int mip, int layer, int x, int y, int width, int height) {
        if (mip != 0 || layer != 0 || SPRITE_ATLASES.contains(texture)) return;
        int channels = texture.getFormat().componentCount();
        if (texture.getFormat().componentType() != com.mojang.renderpearl.api.GpuFormat.ComponentType.UNORM_8
                || channels < 1 || channels > 4 || texture.getFormat().blockSize() != channels) return;
        GlyphPageMirror page = GLYPHS.get(texture);
        if (page != null) {
            page.blit(x, y, width, height, channels, source, width * channels);
            return;
        }
        int textureWidth = texture.getWidth(0), textureHeight = texture.getHeight(0);
        DynamicTextureCache.Pixels old = findPixels(texture);
        byte[] data = old == null ? new byte[textureWidth * textureHeight * 4] : old.data();
        int start = source.position();
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int s = start + (row * width + col) * channels;
                int d = ((y + row) * textureWidth + x + col) * 4;
                data[d] = source.get(s);
                data[d + 1] = channels > 1 ? source.get(s + 1) : 0;
                data[d + 2] = channels > 2 ? source.get(s + 2) : 0;
                data[d + 3] = channels > 3 ? source.get(s + 3) : (byte) 255;
            }
        }
        DynamicTextureCache.Pixels pixels = new DynamicTextureCache.Pixels(data,
                textureWidth, textureHeight, DynamicTextureCache.allocateVersion());
        PIXELS.put(new TextureKey(texture), pixels);
        Identifier id = IDS.get(texture);
        if (id != null) DynamicTextureCache.capture(id, pixels);
    }

    private TextureSourceRegistry() {}
}
