/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nvidia.rtxremix.minecraft.QualityPreset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RtxSettingsStoreTest {

    private RtxSettings registryWith(double[] f, int[] i, boolean[] b) {
        int[] cloudSamples = {160};
        return new RtxSettings(List.of(
                RtxSetting.ofFloat("f", SettingGroup.MATERIALS, "l", "t", 0.0, 10.0, 1.0,
                        () -> f[0], v -> f[0] = v),
                RtxSetting.ofInt("i", SettingGroup.RENDER_DISTANCE, "l", "t", 0, 100, 0,
                        () -> i[0], v -> i[0] = v),
                RtxSetting.ofBool("b", SettingGroup.SKY, "l", "t", true,
                        () -> b[0], v -> b[0] = v),
                RtxSetting.ofInt("clouds.samples", SettingGroup.CLOUDS, "l", "t", 8, 256, 160,
                        () -> cloudSamples[0], v -> cloudSamples[0] = v)));
    }

    @Test
    void roundTripsAppliedVisualValuesAndTheirCustomizedSource(@TempDir Path dir) {
        Path file = dir.resolve("rtxremix.json");
        double[] f = {1.0}; int[] i = {42}; boolean[] b = {true};
        RtxSettings settings = registryWith(f, i, b);
        var edit = new RtxSettingsSession(settings);
        edit.stagePreset(QualityPreset.HIGH, Map.of("f", 3.5));
        @SuppressWarnings("unchecked")
        var artistic = (RtxSetting<Boolean>) settings.byId("b");
        edit.stage(artistic, false);
        edit.apply();
        RtxSettingsStore store = new RtxSettingsStore(file);
        store.save(settings);

        double[] f2 = {0}; int[] i2 = {0}; boolean[] b2 = {true};
        RtxSettings restored = registryWith(f2, i2, b2);
        store.load(restored);
        assertEquals(3.5, f2[0]);
        assertEquals(42, i2[0]);
        assertFalse(b2[0]);
        assertEquals(new RtxQualitySelection(QualityPreset.HIGH, true), restored.quality());
    }

    @Test
    void blockLightMultiplierPersistsAcrossTheFullRange(@TempDir Path dir) {
        for (double candidate : new double[]{0.0, 50.0}) {
            Path file = dir.resolve("rtxremix-" + (int) candidate + ".json");
            double[] source = {candidate};
            RtxSetting<Double> sourceSetting = RtxSetting.ofFloat(
                    "blockLights.torch",
                    SettingGroup.BLOCK_LIGHTS,
                    "label",
                    "tooltip",
                    0.0,
                    50.0,
                    1.0,
                    () -> source[0],
                    value -> source[0] = value);
            RtxSettingsStore store = new RtxSettingsStore(file);
            store.save(new RtxSettings(List.of(sourceSetting)));

            double[] restored = {1.0};
            RtxSetting<Double> restoredSetting = RtxSetting.ofFloat(
                    "blockLights.torch",
                    SettingGroup.BLOCK_LIGHTS,
                    "label",
                    "tooltip",
                    0.0,
                    50.0,
                    1.0,
                    () -> restored[0],
                    value -> restored[0] = value);
            store.load(new RtxSettings(List.of(restoredSetting)));

            assertEquals(candidate, restored[0]);
        }
    }

    @Test
    void saveWritesOwnedDefaultsAndOverriddenPerformanceValues(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("rtxremix.json");
        double[] f = {1.0}; int[] i = {42}; boolean[] b = {true};

        new RtxSettingsStore(file).save(registryWith(f, i, b));

        JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertEquals(3, root.get("version").getAsInt());
        JsonObject values = root.getAsJsonObject("settings");
        assertEquals(160, values.get("clouds.samples").getAsInt());
        assertEquals(42, values.get("i").getAsInt());
        assertFalse(values.has("f"));
        assertFalse(values.has("b"));
    }

    @Test
    void saveRetainsQualityWhenEveryValueMatchesItsDefault(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("rtxremix.json");
        double[] f = {1.0}; int[] i = {0}; boolean[] b = {true};
        RtxSettings settings = registryWith(f, i, b);
        settings.setQuality(new RtxQualitySelection(QualityPreset.MEDIUM, false));

        new RtxSettingsStore(file).save(settings);

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
    void loadSkipsUnknownIdsAndOutOfRange(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("rtxremix.json");
        Files.writeString(file,
                "{\"version\":3,\"settings\":{\"i\":9999,\"ghost\":5,\"f\":2.0}}");
        double[] f = {0}; int[] i = {7}; boolean[] b = {true};
        RtxSettings settings = registryWith(f, i, b);
        new RtxSettingsStore(file).load(settings);
        assertEquals(2.0, f[0]);
        assertEquals(7, i[0]);
        assertTrue(b[0]);
        assertEquals(new RtxQualitySelection(QualityPreset.ULTRA, true), settings.quality());
    }

    @Test
    void loadAppliesExplicitDefaultsOverDifferentLiveValues(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("rtxremix.json");
        Files.writeString(file, """
                {"version":3,"quality":{"base":"low","custom":false},"settings":{"f":1.0,"i":0,"b":true}}
                """);
        double[] f = {8.0}; int[] i = {9}; boolean[] b = {false};
        RtxSettings settings = registryWith(f, i, b);

        new RtxSettingsStore(file).load(settings);

        assertEquals(1.0, f[0]);
        assertEquals(0, i[0]);
        assertTrue(b[0]);
        assertEquals(new RtxQualitySelection(QualityPreset.LOW, false), settings.quality());
    }

    @Test
    void loadWritesMatchingOwnedValuesAfterOneVisualHook(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("rtxremix.json");
        Files.writeString(file, """
                {"version":3,"settings":{"clouds.samples":160,"reflection.psrr":true,"clouds.coverage":0.5}}
                """);
        var operations = new ArrayList<String>();
        var samples = RtxSetting.ofInt("clouds.samples", SettingGroup.CLOUDS, "k", "t", 8, 256, 160,
                () -> 160, next -> operations.add("samples=" + next));
        var psrr = RtxSetting.ofBool("reflection.psrr", SettingGroup.REFLECTIONS, "k", "t", true,
                () -> true, next -> operations.add("psrr=" + next));
        var coverage = RtxSetting.ofFloat("clouds.coverage", SettingGroup.CLOUDS, "k", "t", 0, 1, 0.5,
                () -> 0.5, next -> operations.add("coverage=" + next));
        var settings = new RtxSettings(List.of(samples, psrr, coverage), () -> operations.add("beforeVisualApply"));

        new RtxSettingsStore(file).load(settings);

        assertEquals(List.of("beforeVisualApply", "samples=160", "psrr=true"), operations);
    }

    @Test
    void loadSkipsFloatMatchingTheCurrentValueAtRuntimePrecision(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("rtxremix.json");
        Files.writeString(file, "{\"version\":3,\"settings\":{\"post.exposure\":0.45}}");
        int[] applies = {0};
        RtxSetting<Double> exposure = RtxSetting.ofFloat(
                "post.exposure", SettingGroup.TONEMAPPING, "l", "t",
                0.0, 2.0, 0.5, () -> (double) 0.45f,
                value -> applies[0]++);

        new RtxSettingsStore(file).load(new RtxSettings(List.of(exposure)));

        assertEquals(0, applies[0]);
    }

    @Test
    void loadWithoutQualityLeavesPerformanceOnlyOverridesUncustomized(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("rtxremix.json");
        Files.writeString(file, "{\"version\":3,\"settings\":{\"i\":42}}");
        double[] f = {1.0}; int[] i = {0}; boolean[] b = {true};
        RtxSettings settings = registryWith(f, i, b);

        new RtxSettingsStore(file).load(settings);

        assertEquals(42, i[0]);
        assertEquals(new RtxQualitySelection(QualityPreset.ULTRA, false), settings.quality());
    }

    @Test
    void loadMissingFileIsNoop(@TempDir Path dir) {
        double[] f = {1}; int[] i = {1}; boolean[] b = {true};
        new RtxSettingsStore(dir.resolve("nope.json")).load(registryWith(f, i, b));
        assertEquals(1.0, f[0]);
    }

    @Test
    void loadRejectsAnyVersionItDoesNotOwn(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("rtxremix.json");
        for (int version : new int[]{2, 4}) {
            Files.writeString(file, """
                    {"version":%d,"quality":{"base":"low","custom":true},"settings":{"f":9.0,"i":50,"b":false}}
                    """.formatted(version));
            double[] f = {1.0}; int[] i = {5}; boolean[] b = {true};
            RtxSettings settings = registryWith(f, i, b);

            new RtxSettingsStore(file).load(settings);

            assertEquals(1.0, f[0]);
            assertEquals(5, i[0]);
            assertTrue(b[0]);
            assertEquals(new RtxQualitySelection(QualityPreset.ULTRA, false), settings.quality());
        }
    }
}
