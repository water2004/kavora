/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.gametest;

import org.edtp.kavora.sdk.FrameGenerationCapabilities;
import org.edtp.kavora.KavoraMod;
import org.edtp.kavora.mixin.SpriteBillboardParticleAccessor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.particles.ParticleTypes;

/** Normal-sized foreground motion, including stops, reversals, and camera acceleration. */
final class KavoraParticleTemporalChecks {
    static void run(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runOnServer(server -> {
            KavoraSceneFixtures.commands(server, "fill -3 -61 5 3 -54 5 minecraft:air",
                    "fill -12 -61 12 12 -50 12 minecraft:blue_concrete");
            for (int x = -12; x <= 12; x += 2) {
                KavoraSceneFixtures.commands(server, "fill " + x + " -61 12 " + x + " -50 12 minecraft:cyan_concrete");
            }
        });
        var capabilities = new FrameGenerationCapabilities();
        context.runOnClient(client -> KavoraMod.renderer.getRemixApi().getFrameGenerationCapabilities(capabilities));
        KavoraWorldGameTest.require(capabilities.initializationComplete && capabilities.supported, "FG capability unavailable");
        String captureRoot = System.getenv("RTX_DLFG_CAPTURE_PATH");
        KavoraWorldGameTest.require(captureRoot != null && !captureRoot.isBlank(), "Particle temporal checks require native WSI/guide capture");
        Probe probe = context.computeOnClient(client -> {
            var original = (SingleQuadParticle) client.particleEngine.createParticle(ParticleTypes.CHERRY_LEAVES, .5, -56.3, 3.5, 0, 0, 0);
            var sprite = ((SpriteBillboardParticleAccessor) original).getSprite();
            Probe result = new Probe(client.level, sprite, original.getQuadSize(1));
            original.remove();
            client.particleEngine.add(result);
            return result;
        });
        var multipliers = new LinkedHashSet<Integer>();
        multipliers.add(1);
        multipliers.add(Math.min(2, capabilities.maxGeneratedFrames));
        try (var disabled = RuntimeOptionScope.acquire(context, "rtx.dlfg.enable", "false");
             var upscaler = RuntimeOptionScope.acquire(context, "rtx.upscalerType", "1");
             var quality = RuntimeOptionScope.acquire(context, "rtx.qualityDLSS", "3");
             var reconstruction = RuntimeOptionScope.acquire(context, "rtx.enableRayReconstruction", "true")) {
            for (int count : multipliers) {
                StringBuilder poses = new StringBuilder(24000);
                poses.append("hostFrame,sequence,x,y,z,roll,yaw\n");
                long first = context.computeOnClient(client -> KavoraFrameProbe.presents);
                context.runOnClient(client -> KavoraFrameProbe.frameAction = frame -> {
                    int sequence = (int)(frame - first);
                    int t = Math.max(0, sequence - 20);
                    double x;
                    if (t < 24) x = .5 + .12 * Math.sin(t * .18);
                    else if (t < 36) x = .5 + .12 * Math.sin(23 * .18);
                    else if (t < 60) x = .5 - .12 + .01 * (t - 36);
                    else x = .5;
                    float yaw = t >= 60 && t < 84 ? (float)(Math.sin((t - 60) * .32)) : 0;
                    float roll = t * .035f;
                    probe.pose(x, -56.3, 3.5, roll);
                    client.player.setYRot(yaw);
                    client.player.yRotO = yaw;
                    poses.append(frame).append(',').append(sequence).append(',').append(x)
                            .append(",-56.3,3.5,").append(roll).append(',').append(yaw).append('\n');
                });
                try (var multiplier = RuntimeOptionScope.acquire(context, "rtx.dlfg.maxInterpolatedFrames", Integer.toString(count));
                     var enabled = RuntimeOptionScope.acquire(context, "rtx.dlfg.enable", "true")) {
                    context.waitFor(client -> KavoraFrameProbe.presents >= first + 130, 20 * 60);
                    KavoraWorldGameTest.captureCompletedFrame(context, "particle-temporal-" + (count + 1) + "x");
                } finally {
                    context.runOnClient(client -> KavoraFrameProbe.frameAction = null);
                }
                try {
                    Files.writeString(Path.of(captureRoot, "host-poses-x" + (count + 1) + ".csv"), poses);
                } catch (IOException e) {
                    throw new AssertionError("Cannot record motion fixture", e);
                }
            }
        } finally {
            context.runOnClient(client -> {
                KavoraFrameProbe.frameAction = null;
                probe.remove();
                client.player.setYRot(0);
                client.player.yRotO = 0;
            });
        }
    }

    private static final class Probe extends SingleQuadParticle {
        Probe(ClientLevel level, TextureAtlasSprite sprite, float size) {
            super(level, .5, -56.3, 3.5, sprite);
            quadSize = size;
        }

        void pose(double x, double y, double z, float angle) {
            setPos(x, y, z);
            xo = x; yo = y; zo = z;
            roll = angle; oRoll = angle;
        }

        @Override public void tick() { }
        @Override protected Layer getLayer() { return Layer.OPAQUE; }
    }
}
