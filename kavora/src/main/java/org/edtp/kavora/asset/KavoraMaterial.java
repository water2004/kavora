/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.asset;

import org.edtp.kavora.sdk.MaterialInfo;
import org.edtp.kavora.sdk.RemixAPI;

import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Wraps a single Remix material resource. Holds references to its constituent
 * {@link KavoraTexture}s so the system can detect use-after-delete of textures
 * through a material.
 *
 * <p>Create via {@link KavoraMaterialStore#create(KavoraAssetBase)}.
 * Destroy via the public {@link #destroy()} method.
 */
public class KavoraMaterial extends KavoraAssetBase {

    private MaterialInfo materialInfo;
    private final List<KavoraTexture> textures = new ArrayList<>();
    private final List<ByteBuffer> nativeBuffers = new ArrayList<>();

    public KavoraMaterial(MaterialInfo materialInfo, List<KavoraTexture> textures, List<ByteBuffer> nativeBuffers) {
        this.materialInfo = materialInfo;
        if (textures != null) this.textures.addAll(textures);
        if (nativeBuffers != null) this.nativeBuffers.addAll(nativeBuffers);
    }

    public KavoraMaterial(MaterialInfo materialInfo) {
        this.materialInfo = materialInfo;
    }

    public List<KavoraTexture> getTextures() {
        return Collections.unmodifiableList(textures);
    }

    void update(RemixAPI api, MaterialInfo newInfo) {
        rwLock.writeLock().lock();
        try {
            if (handle == 0 || deletionFlag != DeletionReason.NOT_DELETED) {
                throw new IllegalStateException("Cannot update a material that is not alive");
            }
            newInfo.hash = hash;
            api.updateMaterial(handle, newInfo);
            materialInfo = newInfo;
            Reference.reachabilityFence(newInfo);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    protected long doCreate(RemixAPI api, long hash) {
        for (KavoraTexture tex : textures) {
            if (tex.isDeleted()) {
                throw new IllegalStateException("Cannot create material: referenced "
                        + "KavoraTexture(hash=0x" + Long.toHexString(tex.getHash())
                        + ") is deleted (" + tex.getDeletionFlag() + ")");
            }
        }
        materialInfo.hash = hash;
        long result = api.createMaterial(materialInfo);
        Reference.reachabilityFence(nativeBuffers);
        Reference.reachabilityFence(materialInfo);
        return result;
    }

    @Override
    protected void doDestroy(RemixAPI api, long handle) {
        api.destroyMaterial(handle);
    }

    @Override
    protected void doDisposeData() {
        nativeBuffers.clear();
        textures.clear();
        materialInfo = null;
    }

    @Override
    protected String typeName() {
        return "KavoraMaterial";
    }
}
