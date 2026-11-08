/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TextureSourceRegistryTest {
    @Test
    void nativeImageArgbConvertsToRemixRgbaByteOrder() {
        try (com.mojang.blaze3d.platform.NativeImage image =
                new com.mojang.blaze3d.platform.NativeImage(1, 1, true)) {
            image.setPixel(0, 0, 0x7f123456);
            assertEquals(0x7f563412, NativeImagePixels.abgr(image, 0, 0));
        }
    }

    @Test
    void capturesBeforeRegistrationAndPatchesWithoutChangingSourcePosition() {
        FakeTexture gpu = new FakeTexture(2, 2, GpuFormat.RGBA8_UNORM);
        Identifier id = Identifier.withDefaultNamespace("test/cpu-upload");
        ByteBuffer bytes = ByteBuffer.wrap(new byte[] {99, 1, 2, 3, 4});
        bytes.position(1);
        TextureSourceRegistry.upload(gpu, bytes, 0, 0, 1, 1, 1, 1);
        TextureOwner owner = new TextureOwner(gpu);
        TextureSourceRegistry.register(id, owner, owner.getTexture());
        assertEquals(1, bytes.position());
        assertEquals(id, TextureSourceRegistry.identifier(gpu));
        DynamicTextureCache.Pixels pixels = DynamicTextureCache.get(id);
        assertArrayEquals(new byte[] {1, 2, 3, 4}, java.util.Arrays.copyOfRange(pixels.data(), 12, 16));
        long before = pixels.version();
        TextureSourceRegistry.upload(gpu, ByteBuffer.wrap(new byte[] {5, 6, 7, 8}), 0, 0, 0, 0, 1, 1);
        assertTrue(TextureSourceRegistry.pixels(gpu).version() > before);
        assertArrayEquals(new byte[] {1, 2, 3, 4}, java.util.Arrays.copyOfRange(pixels.data(), 12, 16));
        java.lang.ref.Reference.reachabilityFence(owner);
        TextureSourceRegistry.release(gpu);
        assertNull(TextureSourceRegistry.identifier(gpu));
        assertNull(TextureSourceRegistry.pixels(gpu));
        assertNull(DynamicTextureCache.get(id));
    }

    @Test
    void texturesWithEqualValuesKeepSeparateCpuSources() {
        FakeTexture first = new FakeTexture(1, 1, GpuFormat.RGBA8_UNORM);
        FakeTexture second = new FakeTexture(1, 1, GpuFormat.RGBA8_UNORM);
        TextureSourceRegistry.upload(first, ByteBuffer.wrap(new byte[] {1, 2, 3, 4}), 0, 0, 0, 0, 1, 1);
        TextureSourceRegistry.upload(second, ByteBuffer.wrap(new byte[] {5, 6, 7, 8}), 0, 0, 0, 0, 1, 1);
        assertEquals(1, TextureSourceRegistry.pixels(first).data()[0]);
        assertEquals(5, TextureSourceRegistry.pixels(second).data()[0]);
        TextureSourceRegistry.release(first);
        TextureSourceRegistry.release(second);
    }

    @Test
    void spriteOwnedAtlasesDiscardUploadMirrorsAndDoNotRecreateThemOnAnimation() {
        FakeTexture gpu = new FakeTexture(2, 2, GpuFormat.RGBA8_UNORM);
        Identifier id = Identifier.withDefaultNamespace("test/sprite-atlas");
        TextureOwner owner = new TextureOwner(gpu);
        TextureSourceRegistry.register(id, owner, gpu);
        TextureSourceRegistry.upload(gpu, ByteBuffer.wrap(new byte[] {1, 2, 3, 4}), 0, 0, 0, 0, 1, 1);
        assertNotNull(DynamicTextureCache.get(id));
        TextureSourceRegistry.registerSpriteAtlas(gpu);
        assertEquals(id, TextureSourceRegistry.identifier(gpu));
        assertNull(TextureSourceRegistry.pixels(gpu));
        assertNull(DynamicTextureCache.get(id));
        TextureSourceRegistry.upload(gpu, ByteBuffer.wrap(new byte[] {5, 6, 7, 8}), 0, 0, 0, 0, 1, 1);
        assertNull(TextureSourceRegistry.pixels(gpu));
        TextureSourceRegistry.release(gpu);
        TextureSourceRegistry.upload(gpu, ByteBuffer.wrap(new byte[] {5, 6, 7, 8}), 0, 0, 0, 0, 1, 1);
        assertEquals(5, TextureSourceRegistry.pixels(gpu).data()[0]);
        TextureSourceRegistry.release(gpu);
        java.lang.ref.Reference.reachabilityFence(owner);
    }

    @Test
    void glyphCoverageIsMirroredByDeviceTextureWithoutAResourceIdentifier() {
        FakeTexture gpu = new FakeTexture(256, 256, GpuFormat.R8_UNORM);
        GlyphPageMirror mirror = new GlyphPageMirror(true);
        TextureSourceRegistry.registerGlyph(gpu, mirror);
        Identifier id = Identifier.withDefaultNamespace("test/glyph-coverage");
        TextureOwner owner = new TextureOwner(gpu);
        TextureSourceRegistry.register(id, owner, owner.getTexture());
        DynamicTextureCache.onRegisterGlyphPage(id, mirror);
        TextureSourceRegistry.upload(gpu, ByteBuffer.wrap(new byte[] {42}), 0, 0, 2, 3, 1, 1);
        DynamicTextureCache.Pixels pixels = TextureSourceRegistry.pixels(gpu);
        int start = (3 * 256 + 2) * 4;
        assertArrayEquals(new byte[] {42, 42, 42, 42}, java.util.Arrays.copyOfRange(pixels.data(), start, start + 4));
        assertArrayEquals(new byte[] {42, 42, 42, 42}, java.util.Arrays.copyOfRange(
                DynamicTextureCache.get(id).data(), start, start + 4));
        TextureSourceRegistry.release(gpu);
    }

    @Test
    void recreatingTheDeviceTextureRetainsTheResourceIdentity() {
        FakeTexture oldGpu = new FakeTexture(1, 1, GpuFormat.RGBA8_UNORM);
        FakeTexture newGpu = new FakeTexture(1, 1, GpuFormat.RGBA8_UNORM);
        TextureOwner owner = new TextureOwner(oldGpu);
        Identifier id = Identifier.withDefaultNamespace("test/reload-identity");
        TextureSourceRegistry.register(id, owner, owner.getTexture());
        TextureSourceRegistry.release(oldGpu);
        TextureSourceRegistry.upload(newGpu, ByteBuffer.wrap(new byte[] {8, 7, 6, 5}), 0, 0, 0, 0, 1, 1);
        owner.texture(newGpu);
        TextureSourceRegistry.bind(owner, newGpu);
        assertEquals(id, TextureSourceRegistry.identifier(newGpu));
        assertArrayEquals(new byte[] {8, 7, 6, 5}, DynamicTextureCache.get(id).data());
        java.lang.ref.Reference.reachabilityFence(owner);
        TextureSourceRegistry.release(newGpu);
    }

    private static final class TextureOwner extends AbstractTexture {
        TextureOwner(GpuTexture gpu) { texture = gpu; }
        void texture(GpuTexture gpu) { texture = gpu; }
    }

    private record FakeTexture(int width, int height, GpuFormat format) implements GpuTexture {
        @Override public int getWidth(int mip) { return width; }
        @Override public int getHeight(int mip) { return height; }
        @Override public int getDepthOrLayers() { return 1; }
        @Override public int getMipLevels() { return 1; }
        @Override public GpuFormat getFormat() { return format; }
        @Override public int usage() { return USAGE_COPY_DST; }
        @Override public String getLabel() { return "test"; }
        @Override public boolean isClosed() { return false; }
        @Override public void close() {}
    }
}
