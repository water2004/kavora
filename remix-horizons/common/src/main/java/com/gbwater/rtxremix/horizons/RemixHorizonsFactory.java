/* SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.gbwater.rtxremix.horizons;

import com.nvidia.rtxremix.minecraft.terrain.lod.FarTerrainExtension;

/** Fabric entrypoint connecting the independent addon to the base renderer. */
public final class RemixHorizonsFactory implements FarTerrainExtension.Factory {
    @Override
    public FarTerrainExtension create() {
        return RemixHorizons.scene();
    }
}
