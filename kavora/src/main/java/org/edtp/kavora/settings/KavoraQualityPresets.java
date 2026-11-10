/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.settings;

import org.edtp.kavora.QualityPreset;
import java.util.LinkedHashMap;
import java.util.Map;

/** Editable visual profiles. Display resolution, reconstruction and frame generation have separate owners. */
public final class KavoraQualityPresets {
    private static final Map<QualityPreset, Map<String, Object>> PROFILES = java.util.Arrays.stream(QualityPreset.values())
            .collect(java.util.stream.Collectors.toUnmodifiableMap(preset -> preset, KavoraQualityPresets::build));
    private KavoraQualityPresets() {}

    public static Map<String, Object> values(QualityPreset preset) { return PROFILES.get(preset); }

    public static boolean owns(String id) { return values(QualityPreset.ULTRA).containsKey(id); }

    private static Map<String, Object> build(QualityPreset preset) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("clouds.procedural", true);
        values.put("clouds.transport", preset == QualityPreset.ULTRA ? 0 : 1);
        values.put("clouds.skyResolution", tier(preset, 768, 768, 512, 256));
        values.put("clouds.samples", tier(preset, 160, 128, 96, 64));
        values.put("clouds.shadowSamples", tier(preset, 6, 6, 4, 3));
        values.put("lighting.indirect", 2);
        values.put("lighting.minBounces", 0);
        values.put("lighting.bounces", tier(preset, 2, 2, 1, 1));
        values.put("lighting.rtxdi", true);
        values.put("lighting.initialSamples", tier(preset, 4, 3, 2, 1));
        values.put("lighting.spatialSamples", tier(preset, 2, 2, 1, 1));
        values.put("lighting.disocclusionSamples", tier(preset, 4, 3, 2, 2));
        values.put("lighting.risSamples", tier(preset, 7, 5, 3, 2));
        values.put("lighting.sparse", true);
        values.put("lighting.adaptiveSampling", true);
        values.put("lighting.samplingQuality", 0.3);
        values.put("lighting.samplingRate", 0.125);
        values.put("lighting.samplingExponent", 0.83);
        values.put("lighting.sparseTransparency", false);
        values.put("lighting.direct", true);
        values.put("lighting.secondary", true);
        values.put("reflection.psrr", true);
        values.put("reflection.pstr", true);
        values.put("reflection.bounces", tier(preset, 10, 8, 4, 2));
        values.put("refraction.bounces", tier(preset, 10, 8, 4, 2));
        values.put("cache.records", tier(preset, 32768, 32768, 16384, 8192));
        values.put("cache.combine", true);
        values.put("cache.primary", true);
        values.put("cache.filter", 2);
        values.put("cache.spatialJitter", true);
        values.put("cache.lodJitter", true);
        values.put("fog.grid", tier(preset, 16, 16, 24, 32));
        values.put("fog.integration", preset == QualityPreset.ULTRA ? 0 : 1);
        values.put("fog.integrationResolution", tier(preset, 2, 8, 12, 16));
        values.put("fog.initialSamples", tier(preset, 32, 24, 16, 8));
        values.put("fog.spatialSamples", tier(preset, 8, 6, 4, 2));
        values.put("fog.lightingGrid", 4);
        values.put("fog.lightingDepthSlices", tier(preset, 128, 96, 64, 48));
        values.put("fog.depthSlices", 48);
        values.put("fog.history", 128);
        values.put("materials.pom", tier(preset, 2, 2, 1, 3));
        values.put("materials.pomIterations", tier(preset, 64, 48, 24, 8));
        values.put("materials.pomDirect", true);
        values.put("materials.pomIndirect", true);
        values.put("materials.pomNee", true);
        values.put("materials.pomRestir", true);
        values.put("materials.pomIndirectHit", false);
        values.put("materials.pomPsr", false);
        values.put("materials.pomTransmissionSilhouettes", preset == QualityPreset.ULTRA);
        return Map.copyOf(values);
    }

    static Object defaultValue(String id) { return values(QualityPreset.ULTRA).get(id); }

    /** Applies through the same editable registry as the shader menu. Does not save the selection. */
    public static void apply(KavoraSettings settings, QualityPreset preset) {
        var session = new KavoraSettingsSession(settings);
        session.stagePreset(preset, values(preset));
        session.apply();
    }

    private static int tier(QualityPreset preset, int ultra, int high, int medium, int low) {
        return switch (preset) {
            case ULTRA -> ultra;
            case HIGH -> high;
            case MEDIUM -> medium;
            case LOW -> low;
        };
    }
}
