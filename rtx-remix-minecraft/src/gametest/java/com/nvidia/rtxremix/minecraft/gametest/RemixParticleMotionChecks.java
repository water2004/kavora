/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gametest;

import com.nvidia.rtxremix.minecraft.mixin.ParticleAccessor;
import java.io.IOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.particles.ParticleTypes;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.LoggerFactory;

/** Measures actual cherry-leaf screen displacement against its world-space projection. */
final class RemixParticleMotionChecks {
    static void run(ClientGameTestContext context, TestSingleplayerContext world) {
        Particle leaf = context.computeOnClient(client -> {
            Particle result = client.particleEngine.createParticle(ParticleTypes.CHERRY_LEAVES, .5, -56.3, 2.5, 0, 0, 0);
            if (result == null) throw new AssertionError("Cherry particle provider unavailable");
            result.scale(8);
            return result;
        });
        try {
            context.waitTicks(3);
            double[] first = capture(context, leaf, "cherry-world-centre");
            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                if (!player.teleportTo(server.overworld(), 1.25, -58, .5, java.util.Set.of(), 0, 0, true)) {
                    throw new AssertionError("Particle parallax camera move failed");
                }
            });
            context.waitFor(client -> Math.abs(client.player.getX() - 1.25) < .01, 100);
            context.waitTicks(3);
            double[] translated = capture(context, leaf, "cherry-camera-translated");
            context.runOnClient(client -> { client.player.setYRot(15); client.player.yRotO = 15; });
            context.waitTicks(3);
            double[] turned = capture(context, leaf, "cherry-camera-turned");
            checkDisplacement(first, translated, "camera translation");
            checkDisplacement(translated, turned, "camera rotation");
        } finally {
            context.runOnClient(client -> leaf.remove());
            world.getServer().runOnServer(server -> server.getPlayerList().getPlayers().getFirst()
                    .teleportTo(server.overworld(), .5, -58, .5, java.util.Set.of(), 0, 0, true));
        }
    }

    private static double[] capture(ClientGameTestContext context, Particle particle, String name) {
        long firstPresent = context.computeOnClient(client -> com.nvidia.rtxremix.minecraft.vulkan.NativeVulkanSurface.renderedFrames());
        context.waitFor(client -> com.nvidia.rtxremix.minecraft.vulkan.NativeVulkanSurface.renderedFrames() >= firstPresent + 8, 200);
        context.runOnClient(client -> {
            var p = (ParticleAccessor) particle;
            var state = (com.nvidia.rtxremix.minecraft.particle.RemixParticleStateAccess) particle;
            LoggerFactory.getLogger(RemixParticleMotionChecks.class).info("{}: particle={}, alive={}, slot={}, camera={}",
                    name, particle, !p.getDead(), state.rtxremix$getParticleSlot(), client.gameRenderer.mainCamera().position());
        });
        Path path = RemixWorldGameTest.captureCompletedFrame(context, name);
        double[] predicted = context.computeOnClient(client -> {
            var camera = client.gameRenderer.mainCamera();
            var p = (ParticleAccessor) particle;
            var eye = camera.position();
            Vector3f relative = new Vector3f((float)(p.getX() - eye.x), (float)(p.getY() - eye.y), (float)(p.getZ() - eye.z));
            camera.getViewRotationProjectionMatrix(new Matrix4f()).transformProject(relative);
            return new double[] {relative.x * .5 + .5, .5 - relative.y * .5};
        });
        try {
            var image = ImageIO.read(path.toFile());
            double sumX = 0, sumY = 0, count = 0;
            int centreX = (int)(predicted[0] * image.getWidth());
            int centreY = (int)(predicted[1] * image.getHeight());
            for (int y = Math.max(0, centreY - 210); y < Math.min(image.getHeight(), centreY + 210); y++) {
                for (int x = Math.max(0, centreX - 210); x < Math.min(image.getWidth(), centreX + 210); x++) {
                    int c = image.getRGB(x, y);
                    int r = c >>> 16 & 255, g = c >>> 8 & 255, b = c & 255;
                    if (r > 60 && r - g > -12 && b - g > -10) {
                        sumX += x; sumY += y; count++;
                    }
                }
            }
            RemixWorldGameTest.require(count > 40, "Cherry leaf absent from " + path + ": " + count + " leaf pixels");
            double x = sumX / count, y = sumY / count;
            double expectedX = predicted[0] * image.getWidth(), expectedY = predicted[1] * image.getHeight();
            LoggerFactory.getLogger(RemixParticleMotionChecks.class).info(
                    "{}: screen=({}, {}), projected=({}, {}), leafPixels={}", name, x, y, expectedX, expectedY, count);
            return new double[] { x, y, expectedX, expectedY };
        } catch (IOException e) {
            throw new AssertionError("Cannot inspect cherry particle capture", e);
        }
    }

    private static void checkDisplacement(double[] before, double[] after, String movement) {
        double actual = after[0] - before[0];
        double expected = after[2] - before[2];
        RemixWorldGameTest.require(Math.abs(expected) > 50, "Insufficient parallax for " + movement);
        RemixWorldGameTest.require(Math.abs(actual - expected) < 35,
                "Cherry leaf did not follow world projection under " + movement + ": actual=" + actual + ", expected=" + expected);
    }
}
