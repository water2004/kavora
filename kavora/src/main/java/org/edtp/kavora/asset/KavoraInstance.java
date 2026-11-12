/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.asset;

import org.edtp.kavora.render.RenderOrigin;
import org.edtp.kavora.sdk.InstanceCategoryBit;
import org.edtp.kavora.sdk.InstanceInfoBlendEXT;
import org.edtp.kavora.sdk.RemixAPI;
import org.edtp.kavora.sdk.RemixBridge;
import org.edtp.kavora.sdk.Transform;

/**
 * Wraps a renderable mesh instance: a reference to a {@link KavoraMesh}, a
 * transform, category flags, and an optional blend pNext extension.
 *
 * <p>Visible instances own retained native scene handles. Culling removes the
 * handle while keeping the Java instance and mesh ready for later visibility.
 *
 * <p>Create via {@link KavoraInstanceStore#create(KavoraAssetBase)}.
 * Destroy via the public {@link #destroy()} method.
 */
public class KavoraInstance extends KavoraAssetBase {

    /** Non-null sentinel used while a live Java instance is culled from the scene. */
    static final long INACTIVE_HANDLE = -1L;

    // All retained submissions are made on the render thread, and the FFM call
    // copies the matrix before returning.
    private static final float[] REBASED_TRANSFORM = new float[12];

    private KavoraMesh mesh;
    private Transform transform = Transform.identity();
    private RenderOrigin transformOrigin = RenderOrigin.current();
    private int categoryFlags;
    private InstanceInfoBlendEXT blendExt;
    /**
     * C-heap copy of {@link #blendExt}, marshalled on the first submit after the
     * extension is set and passed as the pNext chain of every add and update until
     * the extension changes or the instance is destroyed. The runtime reads the
     * chain during the call, so one copy serves every submission. The copy follows
     * object identity: mutating an extension after setting it is not observed, so
     * callers swap extension objects to change blend state. This instance owns the
     * pointer it was handed; it never reads the extension's own {@code nativePtr}
     * cache, which shared extension objects overwrite for one another.
     */
    private long blendExtNative;
    private int voxelCoverageLevel;
    private long coverageExtNative;
    private boolean doubleSided;

    /** Whether the retained native description needs a complete replacement. */
    private boolean sceneInfoDirty = true;

    public KavoraInstance(KavoraMesh mesh) {
        this.mesh = mesh;
    }

    /** Replaces the mesh referenced by this retained scene instance. */
    public KavoraInstance setMesh(KavoraMesh mesh) {
        if (this.mesh == mesh) return this;
        this.mesh = mesh;
        this.sceneInfoDirty = true;
        return this;
    }

    public KavoraInstance setTransform(Transform transform) {
        this.transform = transform;
        this.transformOrigin = RenderOrigin.current();
        this.sceneInfoDirty = true;
        return this;
    }

    public boolean isTransformInCurrentOrigin() {
        return transformOrigin == RenderOrigin.current();
    }

    public KavoraInstance setCategoryFlags(int flags) {
        if (this.categoryFlags == flags) return this;
        this.categoryFlags = flags;
        this.sceneInfoDirty = true;
        return this;
    }

    public KavoraInstance setBlendExt(InstanceInfoBlendEXT ext) {
        if (this.blendExt == ext) return this;
        this.blendExt = ext;
        this.sceneInfoDirty = true;
        freeBlendExtNative();
        return this;
    }

    /** Clips far terrain against the shared published-near mask; zero disables clipping. */
    public KavoraInstance setVoxelCoverageLevel(int level) {
        if (level < 0 || level > 255) throw new IllegalArgumentException("Invalid coverage level");
        if (voxelCoverageLevel == level) return this;
        voxelCoverageLevel = level;
        sceneInfoDirty = true;
        freeBlendExtNative();
        return this;
    }

    public KavoraInstance setDoubleSided(boolean ds) {
        if (this.doubleSided == ds) return this;
        this.doubleSided = ds;
        this.sceneInfoDirty = true;
        return this;
    }

    public KavoraMesh getMesh() {
        return mesh;
    }

    /**
     * Retained-scene submission for render-thread-only hot paths. Unchanged visible
     * instances require no native call.
     */
    public void drawFast(RemixAPI api) {
        submitRetained(api);
    }

    /**
     * Removes this instance from the scene, keeping the Java object and its mesh so a later
     * submit can restore it. The handle is already gone when it is 0 (never created or
     * destroyed) or INACTIVE_HANDLE (already removed).
     */
    public void hideFromScene(RemixAPI api) {
        if (handle != 0 && handle != INACTIVE_HANDLE) {
            api.sceneInstanceRemove(handle);
            replaceNativeHandle(INACTIVE_HANDLE);
        }
    }

    private void submitRetained(RemixAPI api) {
        if (mesh == null || mesh.isDeletedFast()) {
            throw new IllegalStateException("Trying to submit retained KavoraInstance(hash=0x"
                    + Long.toHexString(hash) + ") with no live mesh");
        }

        if (handle == INACTIVE_HANDLE) {
            replaceNativeHandle(addSceneInstance(api));
            sceneInfoDirty = false;
        } else if (sceneInfoDirty) {
            updateSceneInstance(api, handle);
            sceneInfoDirty = false;
        }
    }

    private long addSceneInstance(RemixAPI api) {
        return api.sceneInstanceAdd(mesh.getHandleFast(), transformForSubmission(),
                categoryFlags, doubleSided ? 1 : 0, pNextChain());
    }

    private void updateSceneInstance(RemixAPI api, long sceneHandle) {
        api.sceneInstanceUpdate(sceneHandle, mesh.getHandleFast(), transformForSubmission(),
                categoryFlags, doubleSided ? 1 : 0, pNextChain());
    }

    private float[] transformForSubmission() {
        RenderOrigin origin = RenderOrigin.current();
        if (transformOrigin == origin || (categoryFlags & InstanceCategoryBit.CAMERA_RELATIVE_VIEW_MODEL) != 0) {
            return transform.matrix;
        }
        System.arraycopy(transform.matrix, 0, REBASED_TRANSFORM, 0, REBASED_TRANSFORM.length);
        REBASED_TRANSFORM[3] = origin.localX(transformOrigin.x() + (double) transform.matrix[3]);
        REBASED_TRANSFORM[11] = origin.localZ(transformOrigin.z() + (double) transform.matrix[11]);
        return REBASED_TRANSFORM;
    }

    private long pNextChain() {
        if (voxelCoverageLevel != 0 && coverageExtNative == 0)
            coverageExtNative = RemixBridge.allocVoxelCoverageExt(voxelCoverageLevel, 0);
        if (blendExt == null) return coverageExtNative;
        if (blendExtNative == 0) blendExtNative = blendExt.allocNative(coverageExtNative);
        return blendExtNative;
    }

    private void freeBlendExtNative() {
        if (blendExtNative != 0) RemixBridge.freeExt(blendExtNative);
        if (coverageExtNative != 0) RemixBridge.freeExt(coverageExtNative);
        blendExtNative = 0;
        coverageExtNative = 0;
    }

    @Override
    protected long doCreate(RemixAPI api, long hash) {
        return INACTIVE_HANDLE;
    }

    @Override
    protected void doDestroy(RemixAPI api, long handle) {
        if (handle != INACTIVE_HANDLE) {
            api.sceneInstanceRemove(handle);
        }
        freeBlendExtNative();
    }

    @Override
    protected void doDisposeData() { }

    @Override
    protected String typeName() {
        return "KavoraInstance";
    }
}
