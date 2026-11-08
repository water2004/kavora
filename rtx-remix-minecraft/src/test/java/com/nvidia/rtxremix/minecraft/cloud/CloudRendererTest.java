/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.cloud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudRendererTest {
    @Test
    void defaultsToVanillaClouds() {
        assertFalse(new CloudRenderer(null).isProceduralClouds());
    }

    @Test
    void cloudColorKeepsVanillaTintSubtleAndQuantized() {
        assertEquals(0x00cc_cccc, CloudRenderer.packCloudColor(0.0, 0.0, 0.0));
        assertEquals(0x00ff_e6cc, CloudRenderer.packCloudColor(2.0, 0.5, -1.0));
        assertEquals(0x00ff_ccff, CloudRenderer.packCloudColor(
                Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY));
    }

    @Test
    void vanillaFailureFallsBackUntilChunkInvalidationResetsIt() {
        CloudRenderer renderer = new CloudRenderer(null);
        renderer.setProceduralClouds(false);
        long failedEpoch = renderer.vanillaGenerationEpoch();

        assertThrows(IllegalStateException.class, () -> renderer.publishVanillaResult(
                new CloudRenderer.FailedVanillaChunk(
                        failedEpoch, 7L, new OutOfMemoryError("generation failed"))));
        assertFalse(renderer.shouldSuppressVanillaClouds());

        renderer.setVanillaEdgeSmoothness(renderer.getVanillaEdgeSmoothness() + 0.25f);

        assertTrue(renderer.shouldSuppressVanillaClouds());
        renderer.publishVanillaResult(new CloudRenderer.FailedVanillaChunk(
                failedEpoch, 7L, new AssertionError("stale result was consumed")));
        assertTrue(renderer.shouldSuppressVanillaClouds());
    }

    @Test
    void classicMeshPublicationFailureLatchesTheMode() {
        RuntimeException creationFailure = new IllegalStateException("mesh creation failed");

        CloudRenderer vanilla = new CloudRenderer(
                null, (vertices, indices) -> {
                    throw creationFailure;
                });
        vanilla.setProceduralClouds(false);
        long vanillaEpoch = vanilla.vanillaGenerationEpoch();
        IllegalStateException vanillaFailure = assertThrows(
                IllegalStateException.class,
                () -> vanilla.publishVanillaResult(new CloudRenderer.GeneratedVanillaChunk(
                        vanillaEpoch,
                        11L,
                        new VanillaCloudMeshGenerator.GeneratedMesh(
                                new float[0], new int[]{0, 0, 0}, 0L))));
        assertEquals(creationFailure, vanillaFailure.getCause());
        assertFalse(vanilla.shouldSuppressVanillaClouds());
    }
}
