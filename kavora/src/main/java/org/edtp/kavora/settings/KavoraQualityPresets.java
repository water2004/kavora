/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.settings;

import org.edtp.kavora.QualityPreset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** Editable visual profiles. Display resolution, reconstruction and frame generation have separate owners. */
public final class KavoraQualityPresets {
    private static final Map<QualityPreset, Map<String, Object>> PROFILES = java.util.Arrays.stream(QualityPreset.values())
            .collect(java.util.stream.Collectors.toUnmodifiableMap(preset -> preset, KavoraQualityPresets::build));
    private KavoraQualityPresets() {}

    public static Map<String, Object> values(QualityPreset preset) { return PROFILES.get(preset); }

    public static boolean owns(String id) { return values(QualityPreset.ULTRA).containsKey(id); }

    static KavoraQualitySelection selection(KavoraSettings settings, QualityPreset base,
                                            Function<KavoraSetting<?>, Object> currentValue) {
        for (var entry : values(base).entrySet()) {
            var setting = settings.byId(entry.getKey());
            if (setting == null) continue;
            var current = currentValue.apply(setting);
            var expected = entry.getValue();
            boolean matches = setting.kind() == KavoraSetting.Kind.FLOAT
                    ? Float.compare(((Number) current).floatValue(), ((Number) expected).floatValue()) == 0
                    : expected.equals(current);
            if (!matches) return new KavoraQualitySelection(base, true);
        }
        return new KavoraQualitySelection(base, false);
    }

    private static Map<String, Object> build(QualityPreset preset) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("clouds.transport", preset == QualityPreset.ULTRA ? 0 : 1);
        values.put("clouds.skyResolution", tier(preset, 768, 768, 512, 256));
        values.put("clouds.samples", tier(preset, 160, 128, 96, 64));
        values.put("clouds.shadowSamples", tier(preset, 6, 6, 4, 3));
        // The runtime clamps the maximum to the current minimum.
        values.put("lighting.minBounces", 0);
        values.put("lighting.bounces", tier(preset, 2, 2, 1, 1));
        values.put("lighting.initialSamples", tier(preset, 4, 3, 2, 1));
        values.put("lighting.spatialSamples", tier(preset, 2, 2, 1, 1));
        values.put("lighting.disocclusionSamples", tier(preset, 4, 3, 2, 2));
        values.put("lighting.risSamples", tier(preset, 7, 5, 3, 2));
        values.put("reflection.bounces", tier(preset, 10, 8, 4, 2));
        values.put("refraction.bounces", tier(preset, 10, 8, 4, 2));
        values.put("cache.records", tier(preset, 32768, 32768, 16384, 8192));
        values.put("fog.grid", tier(preset, 16, 16, 24, 32));
        values.put("fog.integration", preset == QualityPreset.ULTRA ? 0 : 1);
        values.put("fog.integrationResolution", tier(preset, 2, 8, 12, 16));
        values.put("fog.initialSamples", tier(preset, 32, 24, 16, 8));
        values.put("fog.spatialSamples", tier(preset, 8, 6, 4, 2));
        values.put("fog.lightingDepthSlices", tier(preset, 128, 96, 64, 48));
        values.put("materials.pom", tier(preset, 2, 2, 1, 3));
        values.put("materials.pomIterations", tier(preset, 64, 48, 24, 8));
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
