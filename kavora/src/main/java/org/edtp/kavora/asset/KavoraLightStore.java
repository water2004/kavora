/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.asset;

import org.edtp.kavora.sdk.LightInfo;
import org.edtp.kavora.sdk.RemixAPI;

/**
 * Singleton store for {@link KavoraLight} handles. Created and destroyed on the
 * render thread by the light manager, which submits the scene's lights each
 * frame; {@link #update} rewrites a light in place so the runtime's ReSTIR
 * reservoirs keep tracking it as the same source.
 */
public class KavoraLightStore extends KavoraAssetStore<KavoraLight> {

    private static KavoraLightStore INSTANCE;

    private KavoraLightStore(RemixAPI api) { super(api); }

    public static void init(RemixAPI api) { INSTANCE = new KavoraLightStore(api); }

    public static KavoraLightStore get() { return INSTANCE; }

    /**
     * Updates an existing light's properties in-place (same hash), so Remix's
     * ReSTIR keeps tracking it as the same light source.
     */
    public void update(KavoraLight light, LightInfo newInfo) {
        light.update(api, newInfo);
    }

    public static void shutdown() {
        if (INSTANCE == null) return;
        INSTANCE.destroyAll();
        INSTANCE = null;
    }

    @Override
    protected void destroyLeakedHandle(RemixAPI api, long handle) {
        api.destroyLight(handle);
    }
}
