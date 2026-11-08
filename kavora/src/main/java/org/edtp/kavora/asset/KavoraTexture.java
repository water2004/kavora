/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.asset;

import org.edtp.kavora.sdk.RemixAPI;
import org.edtp.kavora.sdk.TextureFromMemoryInfo;
import org.edtp.kavora.util.DirectBufferPool;

import java.lang.ref.Reference;
import java.nio.ByteBuffer;

/**
 * Wraps a single Remix texture resource backed by pixel data in memory.
 *
 * <p>Create via {@link KavoraTextureStore#create(KavoraAssetBase)}.
 * Destroy via the public {@link #destroy()} method.
 */
public class KavoraTexture extends KavoraAssetBase {

    private int width;
    private int height;
    private int format;
    private int purpose;
    private ByteBuffer pixelData;
    private final boolean immutable;
    private KavoraTextureStore ownerStore;

    public KavoraTexture(int width, int height, int format, int purpose, ByteBuffer pixelData) {
        this(width, height, format, purpose, pixelData, false);
    }

    /** Immutable textures retain only GPU storage once their initial upload has completed. */
    public KavoraTexture(int width, int height, int format, int purpose, ByteBuffer pixelData, boolean immutable) {
        this.width = width;
        this.height = height;
        this.format = format;
        this.purpose = purpose;
        this.pixelData = pixelData;
        this.immutable = immutable;
    }

    void attachStore(KavoraTextureStore store) {
        if (ownerStore != null && ownerStore != store) throw new IllegalStateException("Texture already belongs to another store");
        ownerStore = store;
    }

    @Override
    protected long doCreate(RemixAPI api, long hash) {
        TextureFromMemoryInfo info = new TextureFromMemoryInfo();
        info.hash = hash;
        info.width = width;
        info.height = height;
        info.format = format;
        info.purpose = purpose;
        info.pixelData = pixelData;
        info.immutable = immutable;

        long result = ownerStore.createAllocation(api, info);
        Reference.reachabilityFence(pixelData);
        return result;
    }

    @Override
    protected void doDestroy(RemixAPI api, long handle) {
        ownerStore.releaseAllocation(api, handle);
    }

    @Override
    protected void doDisposeData() {
        DirectBufferPool.release(pixelData);
        pixelData = null;
        width = 0;
        height = 0;
    }

    @Override
    protected String typeName() {
        return "KavoraTexture";
    }
}
