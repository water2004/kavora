/* SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors.
 * SPDX-License-Identifier: Apache-2.0 */
package org.edtp.kavora;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.edtp.kavora.cloud.CloudRenderer;
import org.edtp.kavora.entity.KavoraBlockDamagePass;
import org.edtp.kavora.entity.KavoraBlockOutlinePass;
import org.edtp.kavora.light.LightManager;
import org.edtp.kavora.logic.ConfLayerDriver;
import org.edtp.kavora.particle.ParticleFramePipeline;
import org.edtp.kavora.profiling.ProfileScope;
import org.edtp.kavora.sky.EnvironmentSnapshot;
import org.edtp.kavora.sky.SkyRenderer;
import org.edtp.kavora.texture.ParticleAtlasManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns the preparation and publication steps registered in the scene schedule. */
final class KavoraSceneEffects {
    private static final Logger LOGGER = LoggerFactory.getLogger(KavoraSceneEffects.class);

    private final SkyRenderer sky;
    private final CloudRenderer clouds;
    private final ConfLayerDriver layers;
    private final KavoraFrameMetrics metrics;
    private final LightManager lights;
    private final ParticleFramePipeline particles;
    private final ParticleAtlasManager particleAtlas;
    private final KavoraBlockDamagePass blockDamage;
    private final KavoraBlockOutlinePass blockOutline;
    private final EnvironmentSnapshot environment = new EnvironmentSnapshot();
    private boolean environmentCaptured;

    KavoraSceneEffects(SkyRenderer sky, CloudRenderer clouds, ConfLayerDriver layers, KavoraFrameMetrics metrics,
                       LightManager lights, ParticleFramePipeline particles, ParticleAtlasManager particleAtlas,
                       KavoraBlockDamagePass blockDamage, KavoraBlockOutlinePass blockOutline) {
        this.sky = sky;
        this.clouds = clouds;
        this.layers = layers;
        this.metrics = metrics;
        this.lights = lights;
        this.particles = particles;
        this.particleAtlas = particleAtlas;
        this.blockDamage = blockDamage;
        this.blockOutline = blockOutline;
    }

    void beginParticles(ClientLevel world, float partialTick) {
        particles.submitPendingFrame(world);
        particles.startPrepare(particleAtlas, partialTick, ProfileScope.currentFrameIndex());
    }

    void recordLights(ClientLevel world, Camera camera, float partialTick) {
        metrics.begin(metrics.perfLights);
        try (ProfileScope ignored = ProfileScope.begin("Lights")) {
            // updateLights publishes the analytic lights at their current entity positions.
            lights.updateEntityLights(world, camera, partialTick);
            lights.updateLights(world, camera, partialTick);
        } finally {
            metrics.end(metrics.perfLights);
        }
    }

    void retireBlockDamage() {
        blockDamage.finishFrame();
    }

    void recordBlockOutline() {
        if (blockOutline != null) {
            try (ProfileScope ignored = ProfileScope.begin("BlockOutline")) {
                blockOutline.submit();
            } catch (Exception e) {
                LOGGER.error("Error rendering targeted-block outline", e);
            }
        }
    }

    void recordSky(ClientLevel world, Camera camera, Vec3 position, float partialTick) {
        environmentCaptured = false;
        metrics.begin(metrics.perfSky);
        try (ProfileScope skyScope = ProfileScope.begin("Sky")) {
            if (SkyRenderer.isEnabled()) {
                environment.capture(world, camera, partialTick);
                environmentCaptured = true;
            }
            sky.render(world, position, environment, layers);
        } catch (Exception e) {
            LOGGER.error("Error rendering sky", e);
        } finally {
            metrics.end(metrics.perfSky);
        }
    }

    void recordClouds(Minecraft client, ClientLevel world, Camera camera, Vec3 position, float partialTick) {
        if (!KavoraMod.isWorldActive()) {
            try {
                clouds.disable();
            } catch (RuntimeException e) {
                LOGGER.error("Failed to disable cloud rendering", e);
            }
            return;
        }

        metrics.begin(metrics.perfClouds);
        try (ProfileScope cloudScope = ProfileScope.begin("Clouds")) {
            if (!environmentCaptured) {
                environment.capture(world, camera, partialTick);
            }
            clouds.render(world, position, environment, client.options.getCloudStatus());
            metrics.recordClouds(cloudScope, clouds);
        } catch (Exception e) {
            LOGGER.error("Error rendering clouds", e);
        } finally {
            metrics.end(metrics.perfClouds);
        }
    }

    void finishParticles(ClientLevel world) {
        particles.finishAfterSubmit(world);
    }
}
