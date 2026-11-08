/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.edtp.kavora.settings.KavoraSetting;
import java.util.List;
import org.junit.jupiter.api.Test;

class KavoraSettingsTest {

    private KavoraSetting<Integer> intSetting(String id, SettingGroup g, int def) {
        int[] h = {def};
        return KavoraSetting.ofInt(id, g, "l." + id, "t." + id, 0, 100, def, () -> h[0], v -> h[0] = v);
    }

    @Test
    void rejectsDuplicateIds() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new KavoraSettings(List.of(
                        intSetting("a", SettingGroup.SKY, 1),
                        intSetting("a", SettingGroup.MATERIALS, 2))));
        assertTrue(ex.getMessage().contains("a"));
    }

    @Test
    void lookupGroupingAndResetAll() {
        KavoraSetting<Integer> a = intSetting("a", SettingGroup.SKY, 5);
        KavoraSetting<Integer> b = intSetting("b", SettingGroup.MATERIALS, 7);
        KavoraSettings reg = new KavoraSettings(List.of(a, b));

        assertSame(a, reg.byId("a"));
        assertNull(reg.byId("missing"));
        assertEquals(2, reg.all().size());
        assertEquals(List.of(a), reg.group(SettingGroup.SKY));

        a.apply(99);
        b.apply(99);
        reg.resetAll();
        assertEquals(5, a.get());
        assertEquals(7, b.get());
    }

    @Test
    void categoriesContainTheirExpectedGroups() {
        KavoraSetting<Integer> distance = intSetting("distance", SettingGroup.RENDER_DISTANCE, 1);
        KavoraSetting<Integer> framerate = intSetting("framerate", SettingGroup.FRAMERATE, 1);
        KavoraSetting<Integer> sky = intSetting("sky", SettingGroup.SKY, 1);
        KavoraSetting<Integer> clouds = intSetting("clouds", SettingGroup.CLOUDS, 1);
        KavoraSetting<Integer> blockLights = intSetting("blockLights", SettingGroup.BLOCK_LIGHTS, 1);
        KavoraSetting<Integer> tone = intSetting("tone", SettingGroup.TONEMAPPING, 1);
        KavoraSetting<Integer> adaptation = intSetting("adaptation", SettingGroup.EYE_ADAPTATION, 1);
        KavoraSetting<Integer> materials = intSetting("materials", SettingGroup.MATERIALS, 1);
        KavoraSettings settings = new KavoraSettings(
                List.of(
                        distance,
                        framerate,
                        sky,
                        clouds,
                        blockLights,
                        tone,
                        adaptation,
                        materials));

        assertEquals(List.of(distance, framerate), settings.category(SettingCategory.PERFORMANCE));
        assertEquals(
                List.of(blockLights),
                settings.category(SettingCategory.LIGHTING));
        assertEquals(List.of(sky), settings.category(SettingCategory.SKY));
        assertEquals(List.of(clouds), settings.category(SettingCategory.WEATHER));
        assertEquals(List.of(tone, adaptation), settings.category(SettingCategory.IMAGE));
        assertEquals(List.of(materials), settings.category(SettingCategory.MATERIALS));
    }

    @Test
    void groupResetAndOverrideDetectionStayInsideBlockLights() {
        KavoraSetting<Integer> sky = intSetting("sky", SettingGroup.SKY, 1);
        KavoraSetting<Integer> torch = intSetting("torch", SettingGroup.BLOCK_LIGHTS, 2);
        KavoraSetting<Integer> lantern = intSetting("lantern", SettingGroup.BLOCK_LIGHTS, 3);
        KavoraSettings settings = new KavoraSettings(List.of(sky, torch, lantern));

        sky.apply(10);
        torch.apply(20);
        lantern.apply(30);
        assertTrue(settings.hasOverrides(SettingGroup.BLOCK_LIGHTS));

        settings.resetGroup(SettingGroup.BLOCK_LIGHTS);

        assertEquals(10, sky.get());
        assertEquals(2, torch.get());
        assertEquals(3, lantern.get());
        assertFalse(settings.hasOverrides(SettingGroup.BLOCK_LIGHTS));
        assertTrue(settings.hasOverrides(SettingGroup.SKY));
    }

    @Test
    void lightingCategoryResetDoesNotReachAtmosphere() {
        var torch = intSetting("torch", SettingGroup.BLOCK_LIGHTS, 1);
        var cloud = intSetting("cloud", SettingGroup.CLOUDS, 2);
        torch.apply(5);
        cloud.apply(6);
        var edit = new KavoraSettingsSession(new KavoraSettings(List.of(torch, cloud)));
        edit.reset(value -> value.group().category() == SettingCategory.LIGHTING);
        edit.apply();
        assertEquals(1, torch.get());
        assertEquals(6, cloud.get());
    }
}
