/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.asset;

import org.edtp.kavora.sdk.RemixAPI;

/**
 * Singleton store for {@link KavoraMesh} handles. Worker threads build the
 * vertex data, but every mesh here is created and destroyed on the render
 * thread, which is the only thread allowed to touch Remix scene assets.
 */
public class KavoraMeshStore extends KavoraAssetStore<KavoraMesh> {

    private static KavoraMeshStore INSTANCE;

    private KavoraMeshStore(RemixAPI api) { super(api); }

    public static void init(RemixAPI api) { INSTANCE = new KavoraMeshStore(api); }

    public static KavoraMeshStore get() { return INSTANCE; }

    public static void shutdown() {
        if (INSTANCE == null) return;
        INSTANCE.destroyAll();
        INSTANCE = null;
    }

    @Override
    protected void destroyLeakedHandle(RemixAPI api, long handle) {
        api.destroyMesh(handle);
    }
}
