/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.logic;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.edtp.kavora.asset.KavoraMaterial;
import org.edtp.kavora.sdk.RemixAPI;
import org.edtp.kavora.sdk.RemixException;
import org.edtp.kavora.texture.BlockTextureManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Render-thread owner of the camera's optical medium and gameplay fog inputs. */
public final class KavoraCameraEnvironment {
    private static final Logger LOGGER = LoggerFactory.getLogger(KavoraCameraEnvironment.class);

    private final RemixAPI api;
    private final BlockTextureManager textures;
    private final ConfLayerDriver confLayers;
    private final ZoneRegistry zones;

    private long lastCameraMediumHandle = Long.MIN_VALUE;
    private long lastWaterMediumHandle = Long.MIN_VALUE;
    private int lastWaterMaterialRevision = Integer.MIN_VALUE;

    public KavoraCameraEnvironment(
            RemixAPI api,
            BlockTextureManager textures,
            ConfLayerDriver confLayers,
            ZoneRegistry zones) {
        this.api = api;
        this.textures = textures;
        this.confLayers = confLayers;
        this.zones = zones;
    }

    /** Updates native medium materials and the active world's fog profile once per camera frame. */
    public void update(ClientLevel world, Camera camera, Vec3 cameraPosition,
                       float viewDistanceStrength, float farViewDistanceStrength, float tickDelta) {
        try {
            if (confLayers != null) {
                confLayers.setFarViewDistanceStrength(farViewDistanceStrength);
            }

            // Submersion is read before material readiness so the driver sees
            // it on early frames.
            boolean underwater = camera.getFluidInCamera() == FogType.WATER;
            KavoraMaterial waterMedium = textures == null ? null : underwater
                    ? textures.getWaterCameraMediumMaterial() : textures.getWaterSurfaceMaterial();
            long waterHandle = waterMedium != null && !waterMedium.isDeleted() ? waterMedium.getHandle() : 0;
            long materialHandle = underwater ? waterHandle : 0;
            int waterRevision = textures != null ? textures.getWaterMaterialRevision() : 0;
            if (materialHandle != lastCameraMediumHandle || waterHandle != lastWaterMediumHandle
                    || waterRevision != lastWaterMaterialRevision) {
                api.setCameraMediumMaterial(materialHandle, waterHandle);
                lastWaterMediumHandle = waterHandle;
                lastWaterMaterialRevision = waterRevision;
                if (materialHandle != lastCameraMediumHandle) {
                    LOGGER.info("Camera medium updated: {}", materialHandle != 0 ? "water" : "clear");
                    lastCameraMediumHandle = materialHandle;
                }
            }
            // Conf layers own the volumetric profile and the view-distance
            // fade. A driver left inactive by a failed reload drives nothing
            // until the next successful /kavora conf reload.
            if (confLayers != null && confLayers.isActive() && world != null) {
                confLayers.update(world, cameraPosition, underwater,
                        viewDistanceStrength, tickDelta, zones);
            }
        } catch (RemixException e) {
            LOGGER.error("Error updating camera medium: {} (code: {})", e.getMessage(), e.getErrorCode());
        } catch (Exception e) {
            LOGGER.error("Error updating camera medium", e);
        }
    }

    /** Republishes the camera medium when the next scene is captured. */
    public void invalidate() {
        lastCameraMediumHandle = Long.MIN_VALUE;
        lastWaterMediumHandle = Long.MIN_VALUE;
        lastWaterMaterialRevision = Integer.MIN_VALUE;
    }
}
