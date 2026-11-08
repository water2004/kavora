/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.asset;

import org.edtp.kavora.sdk.RemixAPI;

/**
 * Singleton store for retained {@link KavoraInstance} scene handles.
 */
public class KavoraInstanceStore extends KavoraAssetStore<KavoraInstance> {

    private static KavoraInstanceStore INSTANCE;

    private KavoraInstanceStore(RemixAPI api) { super(api); }

    public static void init(RemixAPI api) { INSTANCE = new KavoraInstanceStore(api); }

    public static KavoraInstanceStore get() { return INSTANCE; }

    public static void shutdown() {
        if (INSTANCE == null) return;
        INSTANCE.destroyAll();
        INSTANCE = null;
    }

    @Override
    protected void destroyLeakedHandle(RemixAPI api, long handle) {
        if (handle != KavoraInstance.INACTIVE_HANDLE) {
            api.sceneInstanceRemove(handle);
        }
    }
}
