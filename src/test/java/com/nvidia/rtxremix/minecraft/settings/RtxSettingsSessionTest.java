/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RtxSettingsSessionTest {
    @Test
    void navigatingAndCancellingDoNotMutateRendererButApplyCommitsOnce() {
        double[] live = {0.5};
        int[] writes = {0};
        var value = RtxSetting.ofFloat("clouds", SettingGroup.CLOUDS, "k", "t", 0, 1, 0.5,
                () -> live[0], next -> { live[0] = next; writes[0]++; });
        var edit = new RtxSettingsSession(new RtxSettings(List.of(value)));
        edit.stage(value, 0.7);
        assertTrue(edit.hasChanges());
        assertEquals(0.5, live[0]);
        edit.discard();
        assertFalse(edit.hasChanges());
        assertEquals(0, writes[0]);
        edit.stage(value, 0.8);
        assertEquals(java.util.EnumSet.of(SettingGroup.CLOUDS), edit.apply());
        assertEquals(0.8, live[0]);
        assertEquals(1, writes[0]);
        assertFalse(edit.hasChanges());
        assertTrue(edit.apply().isEmpty());
        assertEquals(1, writes[0]);
        edit.stage(value, 0.1);
        edit.discard();
        assertEquals(0.8, edit.value(value));
    }

    @Test
    void resettingVisualSettingsIncludesBlockLightsAndLeavesPerformanceAlone() {
        double[] light = {2};
        int[] distance = {8};
        var brightness = RtxSetting.ofFloat("light", SettingGroup.BLOCK_LIGHTS, "k", "t", 0, 50, 1,
                () -> light[0], next -> light[0] = next);
        var capture = RtxSetting.ofInt("dist", SettingGroup.RENDER_DISTANCE, "k", "t", 0, 32, 16,
                () -> distance[0], next -> distance[0] = next);
        var edit = new RtxSettingsSession(new RtxSettings(List.of(brightness, capture)));
        edit.reset(value -> true);
        assertEquals(2, light[0]);
        assertEquals(java.util.EnumSet.of(SettingGroup.BLOCK_LIGHTS), edit.apply());
        assertEquals(1, light[0]);
        assertEquals(8, distance[0]);
    }
}
