/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora;

import org.edtp.kavora.settings.KavoraQualityPresets;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualityPresetTest {

    @Test
    void idsAreListedCheapestFirst() {
        assertEquals(List.of("low", "medium", "high", "ultra"), QualityPreset.ids());
    }

    @Test
    void everyTierOwnsTheSameVisualSettings() {
        var owned = KavoraQualityPresets.values(QualityPreset.ULTRA).keySet();
        assertTrue(owned.containsAll(Set.of("clouds.transport", "lighting.bounces")));
        for (QualityPreset preset : QualityPreset.values()) {
            assertEquals(owned, KavoraQualityPresets.values(preset).keySet(), preset.id());
        }
        for (String id : owned) {
            String key = id.toLowerCase(Locale.ROOT);
            assertFalse(key.startsWith("video.") || key.contains("dlss") || key.contains("dlfg")
                    || key.contains("framegeneration") || key.contains("frame_generation")
                    || key.contains("reconstruction") || key.contains("upscaler"), id);
        }
    }

    @Test
    void lookupMatchesTheIdExactly() {
        assertEquals(Optional.of(QualityPreset.HIGH), QualityPreset.byId("high"));
        assertTrue(QualityPreset.byId("High").isEmpty());
        assertTrue(QualityPreset.byId("Custom").isEmpty());
    }

    @Test
    void stockProfilesRetainReliefCloudsAndRayTracedLighting() {
        for (QualityPreset preset : QualityPreset.values()) {
            var values = KavoraQualityPresets.values(preset);
            for (String effect : List.of("clouds.procedural", "lighting.direct", "lighting.secondary",
                    "lighting.rtxdi", "reflection.psrr", "reflection.pstr", "materials.pomDirect", "materials.pomIndirect")) {
                assertEquals(true, values.get(effect), preset.id() + ": " + effect);
            }
            for (String budget : List.of("clouds.samples", "clouds.shadowSamples", "lighting.bounces",
                    "materials.pom", "fog.integrationResolution", "fog.initialSamples", "fog.lightingDepthSlices", "lighting.risSamples")) {
                assertTrue(((Integer) values.get(budget)) > 0, preset.id() + ": " + budget);
            }
        }
    }
}
