/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.asset;

import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.TextureFromMemoryInfo;

/**
 * Singleton store for {@link RemixTexture} handles. Textures are uploaded from
 * atlas and dynamic-texture captures and are created and destroyed on the
 * render thread.
 */
public class RemixTextureStore extends RemixAssetStore<RemixTexture> {

    private static RemixTextureStore INSTANCE;
    private final ImmutableTextureCache immutableTextures = new ImmutableTextureCache();

    private RemixTextureStore(RemixAPI api) { super(api); }

    public static void init(RemixAPI api) { INSTANCE = new RemixTextureStore(api); }

    public static RemixTextureStore get() { return INSTANCE; }

    /** Live GPU image bytes avoided by sharing, without allocator alignment or residency estimates. */
    public long getSharedImageSavingsBytes() { return immutableTextures.savedImageBytes(); }

    @Override
    public RemixTexture create(RemixTexture texture, boolean disposeData) {
        texture.attachStore(this);
        return super.create(texture, disposeData);
    }

    long createAllocation(RemixAPI api, TextureFromMemoryInfo info, String sharingIdentity) {
        if (!info.immutable || sharingIdentity == null) return api.createTextureFromMemory(info);
        var key = ImmutableTextureCache.Key.of(sharingIdentity, info.width, info.height, info.format, info.purpose, info.mipLevels, info.pixelData);
        return immutableTextures.acquire(key, () -> api.createTextureFromMemory(info));
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
