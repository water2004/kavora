/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gametest;

import java.nio.file.Path;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import org.slf4j.LoggerFactory;

/** Exercises vanilla destruction-state extraction and measures the captured terrain decal. */
final class RemixBlockDamageChecks {
    static void run(ClientGameTestContext context) {
        BlockPos position = new BlockPos(0, -57, 5);
        settle(context);
        Path clean = RemixWorldGameTest.captureCompletedFrame(context, "damage-clean");
        try {
            context.runOnClient(client -> client.level.destroyBlockProgress(70001, position, 9));
            settle(context);
            Path cracked = RemixWorldGameTest.captureCompletedFrame(context, "damage-stage-nine");
            double before = luminance(clean), after = luminance(cracked);
            LoggerFactory.getLogger(RemixBlockDamageChecks.class).info("Block destruction luminance: clean={}, cracked={}", before, after);
            RemixWorldGameTest.require(before - after > 8, "Vanilla destroy overlay did not darken its terrain face: " + before + " -> " + after);
        } finally {
            context.runOnClient(client -> client.level.destroyBlockProgress(70001, position, -1));
        }
        settle(context);
        Path restored = RemixWorldGameTest.captureCompletedFrame(context, "damage-cleared");
        RemixWorldGameTest.require(Math.abs(luminance(restored) - luminance(clean)) < 8, "Destroy decal remained after progress was cleared");
    }

    private static void settle(ClientGameTestContext context) {
        long frame = context.computeOnClient(client -> RemixFrameProbe.presents);
        context.waitFor(client -> RemixFrameProbe.presents >= frame + 8, 200);
    }

    private static double luminance(Path path) {
        try {
            var image = ImageIO.read(path.toFile());
            double sum = 0;
            int count = 0;
            for (int y = image.getHeight() / 2; y < image.getHeight() * 56 / 100; y++) {
                for (int x = image.getWidth() * 48 / 100; x < image.getWidth() * 52 / 100; x++) {
                    int rgb = image.getRGB(x, y);
                    sum += .2126 * (rgb >>> 16 & 255) + .7152 * (rgb >>> 8 & 255) + .0722 * (rgb & 255);
                    count++;
                }
            }
            return sum / count;
        } catch (java.io.IOException e) {
            throw new AssertionError("Cannot inspect destruction overlay", e);
        }
    }
}
