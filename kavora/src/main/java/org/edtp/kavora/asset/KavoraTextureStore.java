/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.asset;

import org.edtp.kavora.sdk.RemixAPI;
import org.edtp.kavora.sdk.TextureFromMemoryInfo;

/**
 * Singleton store for {@link KavoraTexture} handles. Textures are uploaded from
 * atlas and dynamic-texture captures and are created and destroyed on the
 * render thread.
 */
public class KavoraTextureStore extends KavoraAssetStore<KavoraTexture> {

    private static KavoraTextureStore INSTANCE;
    private final ImmutableTextureCache immutableTextures = new ImmutableTextureCache();

    private KavoraTextureStore(RemixAPI api) { super(api); }

    public static void init(RemixAPI api) { INSTANCE = new KavoraTextureStore(api); }

    public static KavoraTextureStore get() { return INSTANCE; }

    /** Live GPU image bytes avoided by sharing, without allocator alignment or residency estimates. */
    public long getSharedImageSavingsBytes() { return immutableTextures.savedImageBytes(); }

    @Override
    public KavoraTexture create(KavoraTexture texture, boolean disposeData) {
        texture.attachStore(this);
        return super.create(texture, disposeData);
    }

    long createAllocation(RemixAPI api, TextureFromMemoryInfo info) {
        if (!info.immutable) return api.createTextureFromMemory(info);
        var upload = ImmutableTextureCache.compactScalar(info);
        var key = ImmutableTextureCache.Key.of(upload.width, upload.height, upload.format, upload.purpose, upload.mipLevels, upload.pixelData);
        return immutableTextures.acquire(key, () -> api.createTextureFromMemory(upload));
    }

    void releaseAllocation(RemixAPI api, long handle) {
        if (!immutableTextures.release(handle, api::destroyTexture)) api.destroyTexture(handle);
    }

    public static void shutdown() {
        if (INSTANCE == null) return;
        INSTANCE.destroyAll();
        INSTANCE = null;
    }

    @Override
    protected void destroyLeakedHandle(RemixAPI api, long handle) {
        releaseAllocation(api, handle);
    }
}
