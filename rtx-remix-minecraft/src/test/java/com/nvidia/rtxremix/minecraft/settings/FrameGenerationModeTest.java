/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import com.nvidia.rtxremix.FrameGenerationCapabilities;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.nvidia.rtxremix.minecraft.settings.FrameGenerationMode.*;

class FrameGenerationModeTest {
    @Test
    void offersOnlyMultipliersConfirmedByNgx() {
        var capabilities = new FrameGenerationCapabilities();
        capabilities.maxGeneratedFrames = 5;
        assertEquals(Set.of(OFF), FrameGenerationMode.allowed(capabilities));
        capabilities.initializationComplete = true;
        assertEquals(Set.of(OFF), FrameGenerationMode.allowed(capabilities));
        capabilities.supported = true;
        capabilities.maxGeneratedFrames = 1;
        assertEquals(Set.of(OFF, X2), FrameGenerationMode.allowed(capabilities));
        capabilities.maxGeneratedFrames = 2;
        assertEquals(Set.of(OFF, X2, X3), FrameGenerationMode.allowed(capabilities));
        capabilities.maxGeneratedFrames = 5;
        assertEquals(Set.of(OFF, X2, X3, X4, X5, X6), FrameGenerationMode.allowed(capabilities));
        capabilities.maxGeneratedFrames = 100;
        assertEquals(Set.of(values()), FrameGenerationMode.allowed(capabilities));
    }

    @Test
    void validatesStoredMultiplierIndependentlyOfTheCurrentMachine() {
        for (String value : new String[] { "0", "-1", "8", "NaN" }) {
            assertFalse(RtxVideoSettings.valid(RtxVideoSettings.FRAME_GENERATION, value));
        }
        assertTrue(RtxVideoSettings.valid(RtxVideoSettings.FRAME_GENERATION, "1"));
        assertTrue(RtxVideoSettings.valid(RtxVideoSettings.FRAME_GENERATION, "7"));
    }
}
