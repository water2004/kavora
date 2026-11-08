/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.settings;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KavoraVideoSettingsTest {
    @Test
    void disablingUpscalingKeepsNativeDlaaAndRayReconstruction() {
        var values = new java.util.LinkedHashMap<String, String>();
        values.put(KavoraVideoSettings.UPSCALER, "0");
        values.put(KavoraVideoSettings.DLSS, "3");
        values.put(KavoraVideoSettings.RECONSTRUCTION, "true");
        values.put(KavoraVideoSettings.FRAME_GENERATION, "1");
        KavoraVideoSettings.normalizeNativeMode(values);
        assertEquals("1", values.get(KavoraVideoSettings.UPSCALER));
        assertEquals("5", values.get(KavoraVideoSettings.DLSS));
        assertEquals("true", values.get(KavoraVideoSettings.RECONSTRUCTION));
        assertEquals("1", values.get(KavoraVideoSettings.FRAME_GENERATION));
        KavoraVideoSettings.normalizeNativeMode(values);
        assertEquals("5", values.get(KavoraVideoSettings.DLSS));
    }

    @Test
    void rejectsInvalidPersistedNativeValues() {
        assertFalse(KavoraVideoSettings.valid(KavoraVideoSettings.DLSS, "6"));
        assertFalse(KavoraVideoSettings.valid(KavoraVideoSettings.DLSS, "NaN"));
        assertFalse(KavoraVideoSettings.valid(KavoraVideoSettings.RECONSTRUCTION, "1"));
        assertFalse(KavoraVideoSettings.valid(KavoraVideoSettings.FRAME_GENERATION, "0"));
        assertFalse(KavoraVideoSettings.valid(KavoraVideoSettings.FRAME_GENERATION, "8"));
        assertFalse(KavoraVideoSettings.valid("rtx.unrelated", "true"));
        assertFalse(KavoraVideoSettings.valid(KavoraVideoSettings.UPSCALER, "-1"));
        assertFalse(KavoraVideoSettings.valid(KavoraVideoSettings.UPSCALER, "2"));
        assertTrue(KavoraVideoSettings.valid(KavoraVideoSettings.UPSCALER, "0"));
        assertTrue(KavoraVideoSettings.valid(KavoraVideoSettings.UPSCALER, "1"));
        assertTrue(KavoraVideoSettings.valid(KavoraVideoSettings.DLSS, "5"));
        assertTrue(KavoraVideoSettings.valid(KavoraVideoSettings.RECONSTRUCTION, "True"));
        assertTrue(KavoraVideoSettings.valid(KavoraVideoSettings.FRAME_GENERATION, "7"));
    }

    @Test
    void rejectsVisualSettingsInVideoPersistence() {
        assertFalse(KavoraVideoSettings.valid("rtx.graphicsPreset", "4"));
        assertFalse(KavoraVideoSettings.valid("rtx.pathMaxBounces", "3"));
        assertFalse(KavoraVideoSettings.valid("rtx.bloom.enable", "false"));
        assertFalse(KavoraVideoSettings.valid("rtx.clouds.transportMode", "1"));
    }
}
