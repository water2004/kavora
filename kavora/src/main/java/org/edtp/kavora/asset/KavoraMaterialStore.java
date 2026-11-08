/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.asset;

import org.edtp.kavora.sdk.MaterialInfo;
import org.edtp.kavora.sdk.RemixAPI;

/**
 * Singleton store for {@link KavoraMaterial} handles. Materials are long lived,
 * built once per atlas, sprite or texture set as the texture managers come up,
 * and created and destroyed on the render thread.
 */
public class KavoraMaterialStore extends KavoraAssetStore<KavoraMaterial> {

    private static KavoraMaterialStore INSTANCE;

    private KavoraMaterialStore(RemixAPI api) { super(api); }

    public static void init(RemixAPI api) { INSTANCE = new KavoraMaterialStore(api); }

    public static KavoraMaterialStore get() { return INSTANCE; }

    public void update(KavoraMaterial material, MaterialInfo newInfo) {
        material.update(api, newInfo);
    }

    public static void shutdown() {
        if (INSTANCE == null) return;
        INSTANCE.destroyAll();
        INSTANCE = null;
    }

    @Override
    protected void destroyLeakedHandle(RemixAPI api, long handle) {
        api.destroyMaterial(handle);
    }
}
