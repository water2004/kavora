/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.entity;

import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

/** Exercises actual pipeline metadata after the client render system has initialized. */
public final class KavoraRenderTypeChecks {
    private KavoraRenderTypeChecks() { }

    public static void assertBeaconLayers() {
        Identifier texture = Identifier.withDefaultNamespace("textures/entity/beacon/beacon_beam.png");
        for (boolean translucent : new boolean[] {false, true}) {
            var layer = RenderTypes.beaconBeam(texture, translucent);
            var expected = translucent ? RenderLayerUtils.SurfaceMode.EMISSIVE : RenderLayerUtils.SurfaceMode.EMISSIVE_CUTOUT;
            if (RenderLayerUtils.surfaceMode(layer) != expected
                    || !RenderLayerUtils.isStreamedPerFrame(layer)
                    || !RenderLayerUtils.capturableTexture(layer).orElseThrow().equals(texture)) {
                throw new AssertionError("Beacon beam layer lost its full-bright animated material: " + translucent);
            }
        }
    }
}
