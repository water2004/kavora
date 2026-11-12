/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.edtp.kavora.QualityPreset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KavoraSettingsStoreTest {

    private KavoraSettings registryWith(double[] f, int[] i, boolean[] b) {
        int[] cloudSamples = {160};
        return new KavoraSettings(List.of(
                KavoraSetting.ofFloat("f", SettingGroup.MATERIALS, "l", "t", 0.0, 10.0, 1.0,
                        () -> f[0], v -> f[0] = v),
                KavoraSetting.ofInt("i", SettingGroup.RENDER_DISTANCE, "l", "t", 0, 100, 0,
                        () -> i[0], v -> i[0] = v),
                KavoraSetting.ofBool("b", SettingGroup.SKY, "l", "t", true,
                        () -> b[0], v -> b[0] = v),
                KavoraSetting.ofInt("clouds.samples", SettingGroup.CLOUDS, "l", "t", 8, 256, 160,
                        () -> cloudSamples[0], v -> cloudSamples[0] = v)));
    }

    @Test
    void roundTripsAppliedVisualValuesAndTheirCustomizedSource(@TempDir Path dir) {
        Path file = dir.resolve("kavora.json");
        double[] f = {1.0}; int[] i = {42}; boolean[] b = {true};
        KavoraSettings settings = registryWith(f, i, b);
        var edit = new KavoraSettingsSession(settings);
        edit.stagePreset(QualityPreset.HIGH, Map.of("clouds.samples", 128));
        @SuppressWarnings("unchecked")
        var artistic = (KavoraSetting<Boolean>) settings.byId("b");
        @SuppressWarnings("unchecked")
        var material = (KavoraSetting<Double>) settings.byId("f");
        edit.stage(material, 3.5);
        edit.stage(artistic, false);
        edit.apply();
        KavoraSettingsStore store = new KavoraSettingsStore(file);
        store.save(settings);

        double[] f2 = {0}; int[] i2 = {0}; boolean[] b2 = {true};
        KavoraSettings restored = registryWith(f2, i2, b2);
        store.load(restored);
        assertEquals(3.5, f2[0]);
        assertEquals(42, i2[0]);
        assertFalse(b2[0]);
        assertEquals(new KavoraQualitySelection(QualityPreset.HIGH, false), restored.quality());
    }

    @Test
    void blockLightMultiplierPersistsAcrossTheFullRange(@TempDir Path dir) {
        for (double candidate : new double[]{0.0, 50.0}) {
            Path file = dir.resolve("kavora-" + (int) candidate + ".json");
            double[] source = {candidate};
            KavoraSetting<Double> sourceSetting = KavoraSetting.ofFloat(
                    "blockLights.torch",
                    SettingGroup.BLOCK_LIGHTS,
                    "label",
                    "tooltip",
                    0.0,
                    50.0,
                    1.0,
                    () -> source[0],
                    value -> source[0] = value);
            KavoraSettingsStore store = new KavoraSettingsStore(file);
            store.save(new KavoraSettings(List.of(sourceSetting)));

            double[] restored = {1.0};
            KavoraSetting<Double> restoredSetting = KavoraSetting.ofFloat(
                    "blockLights.torch",
                    SettingGroup.BLOCK_LIGHTS,
                    "label",
                    "tooltip",
                    0.0,
                    50.0,
                    1.0,
                    () -> restored[0],
                    value -> restored[0] = value);
            store.load(new KavoraSettings(List.of(restoredSetting)));

            assertEquals(candidate, restored[0]);
        }
    }

    @Test
    void saveWritesOwnedDefaultsAndOverriddenPerformanceValues(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("kavora.json");
        double[] f = {1.0}; int[] i = {42}; boolean[] b = {true};

        new KavoraSettingsStore(file).save(registryWith(f, i, b));

        JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertEquals(4, root.get("version").getAsInt());
        JsonObject values = root.getAsJsonObject("settings");
        assertEquals(160, values.get("clouds.samples").getAsInt());
        assertEquals(42, values.get("i").getAsInt());
        assertFalse(values.has("f"));
        assertFalse(values.has("b"));
    }

    @Test
    void saveRetainsQualityWhenEveryValueMatchesItsDefault(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("kavora.json");
        double[] f = {1.0}; int[] i = {0}; boolean[] b = {true};
        KavoraSettings settings = registryWith(f, i, b);
        settings.setQuality(new KavoraQualitySelection(QualityPreset.MEDIUM, false));

        new KavoraSettingsStore(file).save(settings);

        JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        JsonObject quality = root.getAsJsonObject("quality");
        assertEquals("medium", quality.get("base").getAsString());
        assertFalse(quality.get("custom").getAsBoolean());
        JsonObject values = root.getAsJsonObject("settings");
        assertEquals(160, values.get("clouds.samples").getAsInt());
        assertFalse(values.has("i"));
        assertFalse(values.has("f"));
        assertFalse(values.has("b"));
    }

    @Test
    void loadUsesTheSavedPresetForNewOptionsAndPreservesExplicitOverrides(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("kavora.json");
        Files.writeString(file, """
                {"version":3,"quality":{"base":"high","custom":true},"settings":{"clouds.samples":96}}
                """);
        boolean[] silhouettes = {true};
        int[] samples = {160};
        var settings = new KavoraSettings(List.of(
                KavoraSetting.ofBool("materials.pomTransmissionSilhouettes", SettingGroup.PARALLAX, "l", "t", true,
                        () -> silhouettes[0], value -> silhouettes[0] = value),
                KavoraSetting.ofInt("clouds.samples", SettingGroup.CLOUDS, "l", "t", 8, 256, 160,
                        () -> samples[0], value -> samples[0] = value)));

        new KavoraSettingsStore(file).load(settings);

        assertFalse(silhouettes[0]);
        assertEquals(96, samples[0]);
        assertEquals(new KavoraQualitySelection(QualityPreset.HIGH, true), settings.quality());
    }

    @Test
    void loadSkipsUnknownIdsAndOutOfRange(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("kavora.json");
        Files.writeString(file,
                "{\"version\":3,\"settings\":{\"i\":9999,\"ghost\":5,\"f\":2.0}}");
        double[] f = {0}; int[] i = {7}; boolean[] b = {true};
        KavoraSettings settings = registryWith(f, i, b);
        new KavoraSettingsStore(file).load(settings);
        assertEquals(2.0, f[0]);
        assertEquals(7, i[0]);
        assertTrue(b[0]);
        assertEquals(new KavoraQualitySelection(QualityPreset.ULTRA, false), settings.quality());
    }

    @Test
    void loadAppliesExplicitDefaultsOverDifferentLiveValues(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("kavora.json");
        Files.writeString(file, """
                {"version":3,"quality":{"base":"low","custom":false},"settings":{"f":1.0,"i":0,"b":true}}
                """);
        double[] f = {8.0}; int[] i = {9}; boolean[] b = {false};
        KavoraSettings settings = registryWith(f, i, b);

        new KavoraSettingsStore(file).load(settings);

        assertEquals(1.0, f[0]);
        assertEquals(0, i[0]);
        assertTrue(b[0]);
        assertEquals(new KavoraQualitySelection(QualityPreset.LOW, false), settings.quality());
    }

    @Test
    void loadWritesMatchingOwnedValuesBeforePublishingChanges(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("kavora.json");
        Files.writeString(file, """
                {"version":3,"settings":{"clouds.samples":160,"reflection.psrr":true,"clouds.coverage":0.5}}
                """);
        var operations = new ArrayList<String>();
        var samples = KavoraSetting.ofInt("clouds.samples", SettingGroup.CLOUDS, "k", "t", 8, 256, 160,
                () -> 160, next -> operations.add("samples=" + next));
        var psrr = KavoraSetting.ofBool("reflection.psrr", SettingGroup.REFLECTIONS, "k", "t", true,
                () -> true, next -> operations.add("psrr=" + next));
        var coverage = KavoraSetting.ofFloat("clouds.coverage", SettingGroup.CLOUDS, "k", "t", 0, 1, 0.5,
                () -> 0.5, next -> operations.add("coverage=" + next));
        var settings = new KavoraSettings(List.of(samples, psrr, coverage), () -> operations.add("publish"));

        new KavoraSettingsStore(file).load(settings);

        assertEquals(List.of("samples=160", "publish"), operations);
    }

    @Test
    void loadSkipsFloatMatchingTheCurrentValueAtRuntimePrecision(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("kavora.json");
        Files.writeString(file, "{\"version\":3,\"settings\":{\"post.exposure\":0.45}}");
        int[] applies = {0};
        KavoraSetting<Double> exposure = KavoraSetting.ofFloat(
                "post.exposure", SettingGroup.TONEMAPPING, "l", "t",
                0.0, 2.0, 0.5, () -> (double) 0.45f,
                value -> applies[0]++);

        new KavoraSettingsStore(file).load(new KavoraSettings(List.of(exposure)));

        assertEquals(0, applies[0]);
    }

    @Test
    void loadWithoutQualityLeavesPerformanceOnlyOverridesUncustomized(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("kavora.json");
        Files.writeString(file, "{\"version\":3,\"settings\":{\"i\":42}}");
        double[] f = {1.0}; int[] i = {0}; boolean[] b = {true};
        KavoraSettings settings = registryWith(f, i, b);

        new KavoraSettingsStore(file).load(settings);

        assertEquals(42, i[0]);
        assertEquals(new KavoraQualitySelection(QualityPreset.ULTRA, false), settings.quality());
    }

    @Test
    void loadMissingFileIsNoop(@TempDir Path dir) {
        double[] f = {1}; int[] i = {1}; boolean[] b = {true};
        new KavoraSettingsStore(dir.resolve("nope.json")).load(registryWith(f, i, b));
        assertEquals(1.0, f[0]);
    }

    @Test
    void loadRejectsAnyVersionItDoesNotOwn(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("kavora.json");
        for (int version : new int[]{2, 5}) {
            Files.writeString(file, """
                    {"version":%d,"quality":{"base":"low","custom":true},"settings":{"f":9.0,"i":50,"b":false}}
                    """.formatted(version));
            double[] f = {1.0}; int[] i = {5}; boolean[] b = {true};
            KavoraSettings settings = registryWith(f, i, b);

            var store = new KavoraSettingsStore(file);
            String original = Files.readString(file);
            store.load(settings);
            store.save(settings);

            assertEquals(1.0, f[0]);
            assertEquals(5, i[0]);
            assertTrue(b[0]);
            assertEquals(new KavoraQualitySelection(QualityPreset.ULTRA, false), settings.quality());
            assertEquals(original, Files.readString(file));
        }
    }

    @Test
    void migratesReleasedFogToEachDimensionAndRecomputesThePresetLabel(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("kavora.json");
        Files.writeString(file, """
                {"version":3,"quality":{"base":"high","custom":true},
                 "settings":{"fog.heightFalloff":22,"fog.transmittance.red":0.8,
                             "fog.end.heightFalloff":14,"image.saturation":0.85,"unknown":9}}
                """);
        double[] overworldFalloff = {0}, netherFalloff = {0}, endFalloff = {4}, red = {0.999};
        var settings = new KavoraSettings(List.of(
                fog("fog.overworld.heightFalloff", overworldFalloff, 0, 128),
                fog("fog.nether.heightFalloff", netherFalloff, 0, 128),
                fog("fog.end.heightFalloff", endFalloff, 4, 128),
                fog("fog.overworld.transmittance.red", red, 0.999, 1)));
        var store = new KavoraSettingsStore(file);
        store.load(settings);

        assertEquals(22, overworldFalloff[0]);
        assertEquals(22, netherFalloff[0]);
        assertEquals(14, endFalloff[0]);
        assertEquals(0.8, red[0]);
        assertEquals(new KavoraQualitySelection(QualityPreset.HIGH, false), settings.quality());
        String migrated = Files.readString(file);
        JsonObject root = JsonParser.parseString(migrated).getAsJsonObject();
        assertEquals(4, root.get("version").getAsInt());
        var values = root.getAsJsonObject("settings");
        assertFalse(values.has("fog.heightFalloff"));
        assertFalse(values.has("image.saturation"));
        assertEquals(22, values.get("fog.overworld.heightFalloff").getAsInt());
        assertEquals(22, values.get("fog.nether.heightFalloff").getAsInt());
        assertEquals(9, values.get("unknown").getAsInt());
        store.load(settings);
        assertEquals(migrated, Files.readString(file));
    }

    private static KavoraSetting<Double> fog(String id, double[] value, double baseline, double max) {
        return KavoraSetting.ofFloat(id, SettingGroup.FOG, "l", "t", 0, max, baseline,
                () -> value[0], next -> value[0] = next);
    }
}
