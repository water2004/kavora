/* SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package org.edtp.kavora.horizon;

import org.edtp.kavora.terrain.lod.FarTerrainExtension;

/** Fabric entrypoint connecting the independent addon to the base renderer. */
public final class KavoraHorizonFactory implements FarTerrainExtension.Factory {
    @Override
    public FarTerrainExtension create() {
        return KavoraHorizon.scene();
    }
}
