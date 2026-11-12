/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.settings;

import org.edtp.kavora.QualityPreset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KavoraSettingsSessionTest {
    @Test
    void selectingEditingAndCancellingKeepLiveValuesAndQualityUntilApply() {
        int[] live = {160};
        int[] writes = {0};
        var value = KavoraSetting.ofInt("clouds.samples", SettingGroup.CLOUDS, "k", "t", 8, 256, 160,
                () -> live[0], next -> { live[0] = next; writes[0]++; });
        var settings = new KavoraSettings(List.of(value));
        var edit = new KavoraSettingsSession(settings);
        var ultra = new KavoraQualitySelection(QualityPreset.ULTRA, false);
        var high = new KavoraQualitySelection(QualityPreset.HIGH, false);
        var customizedHigh = new KavoraQualitySelection(QualityPreset.HIGH, true);

        edit.stagePreset(QualityPreset.HIGH, Map.of("clouds.samples", 128));
        assertEquals(high, edit.quality());
        assertEquals(128, edit.value(value));
        assertTrue(edit.hasChanges());
        edit.stage(value, 120);
        assertEquals(customizedHigh, edit.quality());
        edit.stage(value, 128);
        assertEquals(high, edit.quality());
        edit.stage(value, 144);
        assertEquals(ultra, settings.quality());
        assertEquals(160, live[0]);

        edit.discard();
        assertFalse(edit.hasChanges());
        assertEquals(ultra, edit.quality());
        assertEquals(160, edit.value(value));
        assertEquals(0, writes[0]);

        edit.stagePreset(QualityPreset.HIGH, Map.of("clouds.samples", 128));
        edit.stage(value, 120);
        assertEquals(EnumSet.of(SettingGroup.CLOUDS), edit.apply());
        assertEquals(120, live[0]);
        assertEquals(1, writes[0]);
        assertEquals(customizedHigh, settings.quality());
        assertFalse(edit.hasChanges());
        assertTrue(edit.apply().isEmpty());
        assertEquals(1, writes[0]);

        edit.stagePreset(QualityPreset.MEDIUM, Map.of("clouds.samples", 96));
        edit.discard();
        assertEquals(120, edit.value(value));
        assertEquals(customizedHigh, edit.quality());
        edit.stage(value, 128);
        assertEquals(high, edit.quality());
    }

    @Test
    void reselectingCurrentPresetWritesItsValuesOnlyOnApply() {
        var operations = new ArrayList<String>();
        var samples = KavoraSetting.ofInt("clouds.samples", SettingGroup.CLOUDS, "k", "t", 8, 256, 160,
                () -> 128, next -> operations.add("samples=" + next));
        var bounces = KavoraSetting.ofInt("reflection.bounces", SettingGroup.REFLECTIONS, "k", "t", 1, 16, 10,
                () -> 8, next -> operations.add("bounces=" + next));
        var settings = new KavoraSettings(List.of(samples, bounces), () -> operations.add("beforeVisualApply"));
        var high = new KavoraQualitySelection(QualityPreset.HIGH, false);
        settings.setQuality(high);
        var edit = new KavoraSettingsSession(settings);
        var values = new LinkedHashMap<String, Integer>();
        values.put(samples.id(), 128);
        values.put(bounces.id(), 8);

        edit.stagePreset(QualityPreset.HIGH, values);
        assertEquals(high, edit.quality());
        assertTrue(edit.hasChanges());
        assertTrue(operations.isEmpty());
        edit.discard();
        assertFalse(edit.hasChanges());
        assertTrue(operations.isEmpty());

        edit.stagePreset(QualityPreset.HIGH, values);
        assertTrue(edit.hasChanges());
        edit.apply();
        assertEquals(List.of("beforeVisualApply", "samples=128", "bounces=8"), operations);
        assertEquals(high, settings.quality());
        assertFalse(edit.hasChanges());
    }

    @Test
    void rejectsInvalidPresetAtomicallyAndLeavesPerformanceUntouched() {
        double[] clouds = {0.5};
        int[] bounces = {2};
        int[] distance = {8};
        var coverage = KavoraSetting.ofFloat("clouds.coverage", SettingGroup.CLOUDS, "k", "t", 0, 1, 0.5,
                () -> clouds[0], next -> clouds[0] = next);
        var reflections = KavoraSetting.ofInt("reflection.bounces", SettingGroup.REFLECTIONS, "k", "t", 0, 16, 10,
                () -> bounces[0], next -> bounces[0] = next).withStep(2);
        var capture = KavoraSetting.ofInt("dist", SettingGroup.RENDER_DISTANCE, "k", "t", 0, 32, 16,
                () -> distance[0], next -> distance[0] = next);
        var settings = new KavoraSettings(List.of(coverage, reflections, capture));
        var edit = new KavoraSettingsSession(settings);
        edit.stage(coverage, 0.45);
        edit.stagePreset(QualityPreset.MEDIUM, Map.of(reflections.id(), 4));
        var before = edit.quality();
        var invalid = new LinkedHashMap<String, Object>();
        invalid.put(reflections.id(), 3);

        assertThrows(IllegalArgumentException.class, () -> edit.stagePreset(QualityPreset.LOW, invalid));
        assertEquals(0.45, edit.value(coverage));
        assertEquals(4, edit.value(reflections));
        assertEquals(before, edit.quality());

        invalid.remove(reflections.id());
        invalid.put("dist", 16);
        assertThrows(IllegalArgumentException.class, () -> edit.stagePreset(QualityPreset.LOW, invalid));
        assertEquals(0.45, edit.value(coverage));
        assertEquals(before, edit.quality());

        assertEquals(EnumSet.of(SettingGroup.CLOUDS, SettingGroup.REFLECTIONS), edit.apply());
        assertEquals(0.45, clouds[0]);
        assertEquals(4, bounces[0]);
        assertEquals(8, distance[0]);
        assertEquals(new KavoraQualitySelection(QualityPreset.MEDIUM, false), settings.quality());
        edit.stagePreset(QualityPreset.LOW, Map.of(reflections.id(), 2));
        edit.apply();
        assertEquals(0.45, clouds[0]);
        assertEquals(new KavoraQualitySelection(QualityPreset.LOW, false), settings.quality());
    }

    @Test
    void resettingVisualSettingsIncludesBlockLightsAndLeavesPerformanceAlone() {
        double[] light = {2};
        int[] distance = {8};
        var brightness = KavoraSetting.ofFloat("light", SettingGroup.BLOCK_LIGHTS, "k", "t", 0, 50, 1,
                () -> light[0], next -> light[0] = next);
        var capture = KavoraSetting.ofInt("dist", SettingGroup.RENDER_DISTANCE, "k", "t", 0, 32, 16,
                () -> distance[0], next -> distance[0] = next);
        var edit = new KavoraSettingsSession(new KavoraSettings(List.of(brightness, capture)));
        edit.reset(value -> true);
        assertEquals(2, light[0]);
        assertEquals(EnumSet.of(SettingGroup.BLOCK_LIGHTS), edit.apply());
        assertEquals(1, light[0]);
        assertEquals(8, distance[0]);
    }

    @Test
    void folderResetIncludesQualityChildrenAndKeepsOtherPendingEdits() {
        int[] samples = {64};
        double[] fog = {0.2};
        var cloudSamples = KavoraSetting.ofInt("clouds.samples", SettingGroup.CLOUD_QUALITY, "k", "t", 8, 256, 160,
                () -> samples[0], value -> samples[0] = value);
        var fogDensity = KavoraSetting.ofFloat("fog.density", SettingGroup.FOG, "k", "t", 0, 1, 0.1,
                () -> fog[0], value -> fog[0] = value);
        var settings = new KavoraSettings(List.of(cloudSamples, fogDensity));
        var edit = new KavoraSettingsSession(settings);
        edit.stage(fogDensity, 0.3);
        edit.reset(setting -> SettingGroup.CLOUDS.contains(setting.group()));
        assertTrue(settings.hasGroup(SettingGroup.CLOUDS));
        assertEquals(160, edit.value(cloudSamples));
        assertEquals(0.3, edit.value(fogDensity));
        assertEquals(64, samples[0]);
        assertEquals(EnumSet.of(SettingGroup.CLOUD_QUALITY, SettingGroup.FOG), edit.apply());
        assertEquals(160, samples[0]);
        assertEquals(0.3, fog[0]);
    }
}
