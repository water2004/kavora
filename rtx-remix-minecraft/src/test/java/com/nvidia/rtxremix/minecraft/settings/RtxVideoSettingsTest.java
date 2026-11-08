/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RtxVideoSettingsTest {
    @Test
    void disablingUpscalingKeepsNativeDlaaAndRayReconstruction() {
        var values = new java.util.LinkedHashMap<String, String>();
        values.put(RtxVideoSettings.UPSCALER, "0");
        values.put(RtxVideoSettings.DLSS, "3");
        values.put(RtxVideoSettings.RECONSTRUCTION, "true");
        values.put(RtxVideoSettings.FRAME_GENERATION, "1");
        RtxVideoSettings.normalizeNativeMode(values);
        assertEquals("1", values.get(RtxVideoSettings.UPSCALER));
        assertEquals("5", values.get(RtxVideoSettings.DLSS));
        assertEquals("true", values.get(RtxVideoSettings.RECONSTRUCTION));
        assertEquals("1", values.get(RtxVideoSettings.FRAME_GENERATION));
        RtxVideoSettings.normalizeNativeMode(values);
        assertEquals("5", values.get(RtxVideoSettings.DLSS));
    }

    @Test
    void rejectsInvalidPersistedNativeValues() {
        assertFalse(RtxVideoSettings.valid(RtxVideoSettings.DLSS, "6"));
        assertFalse(RtxVideoSettings.valid(RtxVideoSettings.DLSS, "NaN"));
        assertFalse(RtxVideoSettings.valid(RtxVideoSettings.RECONSTRUCTION, "1"));
        assertFalse(RtxVideoSettings.valid(RtxVideoSettings.FRAME_GENERATION, "0"));
        assertFalse(RtxVideoSettings.valid(RtxVideoSettings.FRAME_GENERATION, "8"));
        assertFalse(RtxVideoSettings.valid("rtx.unrelated", "true"));
        assertFalse(RtxVideoSettings.valid(RtxVideoSettings.UPSCALER, "-1"));
        assertFalse(RtxVideoSettings.valid(RtxVideoSettings.UPSCALER, "2"));
        assertTrue(RtxVideoSettings.valid(RtxVideoSettings.UPSCALER, "0"));
        assertTrue(RtxVideoSettings.valid(RtxVideoSettings.UPSCALER, "1"));
        assertTrue(RtxVideoSettings.valid(RtxVideoSettings.DLSS, "5"));
        assertTrue(RtxVideoSettings.valid(RtxVideoSettings.RECONSTRUCTION, "True"));
        assertTrue(RtxVideoSettings.valid(RtxVideoSettings.FRAME_GENERATION, "7"));
    }

    @Test
    void rejectsVisualSettingsInVideoPersistence() {
        assertFalse(RtxVideoSettings.valid("rtx.graphicsPreset", "4"));
        assertFalse(RtxVideoSettings.valid("rtx.pathMaxBounces", "3"));
        assertFalse(RtxVideoSettings.valid("rtx.bloom.enable", "false"));
        assertFalse(RtxVideoSettings.valid("rtx.clouds.transportMode", "1"));
    }
}
