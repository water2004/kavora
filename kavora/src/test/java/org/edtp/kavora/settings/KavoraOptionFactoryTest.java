/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import net.minecraft.network.chat.contents.TranslatableContents;

import static org.junit.jupiter.api.Assertions.assertEquals;


class KavoraOptionFactoryTest {

    private static final String LABEL_KEY = "kavora.test.label";

    private static KavoraSetting<Integer> intSetting(String id, int min, int max) {
        return KavoraSetting.ofInt(id, SettingGroup.RENDER_DISTANCE, LABEL_KEY, "k.tip",
                min, max, min, () -> min, v -> { });
    }

    @Test
    void proceduralCloudToggleUsesCloudStyleNames() {
        assertEquals(
                "kavora.settings.value.procedural",
                ((TranslatableContents) KavoraOptionFactory.booleanValueText(
                        KavoraOptionFactory.PROCEDURAL_CLOUDS_ID, true).getContents()).getKey());
        assertEquals(
                "kavora.settings.value.classic",
                ((TranslatableContents) KavoraOptionFactory.booleanValueText(
                        KavoraOptionFactory.PROCEDURAL_CLOUDS_ID, false).getContents()).getKey());
    }

    @Test
    void bothDistanceSlidersShareOneSentinelAboveThirtyTwoChunks() {
        // Equal ranges are the point: one sentinel position serves both, where
        // the per-setting constants existed only because the maxima differed.
        for (String id : List.of("dist.entity", "dist.light")) {
            KavoraSetting<Integer> distance = intSetting(id, 0, 32);
            assertEquals(32, KavoraOptionFactory.intValueFromSlider(distance, 32), id);
            assertEquals(0, KavoraOptionFactory.intValueFromSlider(distance, 33), id);

            assertEquals(33, KavoraOptionFactory.sliderValueFromInt(distance, 0), id);
            assertEquals(32, KavoraOptionFactory.sliderValueFromInt(distance, 32), id);
            // Out of range clamps onto the slider rather than onto the sentinel,
            // so a stale value cannot read as Auto.
            assertEquals(32, KavoraOptionFactory.sliderValueFromInt(distance, 104), id);
        }
    }

    @Test
    void unfocusedFramerateSliderMirrorsVanillaMaxFramerate() {
        KavoraSetting<Integer> framerate = KavoraSettingsFactory.unfocusedFramerate();
        String id = framerate.id();

        assertEquals(10, KavoraOptionFactory.intValueFromSlider(framerate, 1));
        assertEquals(250, KavoraOptionFactory.intValueFromSlider(framerate, 25));
        assertEquals(260, KavoraOptionFactory.intValueFromSlider(framerate, 26));
        assertEquals(1, KavoraOptionFactory.sliderValueFromInt(framerate, 10));
        assertEquals(26, KavoraOptionFactory.sliderValueFromInt(framerate, 260));

        assertEquals("options.framerateLimit.max",
                ((TranslatableContents) KavoraOptionFactory.intValueText(id, 260)
                        .getContents()).getKey());
        TranslatableContents capped = (TranslatableContents)
                KavoraOptionFactory.intValueText(id, 120).getContents();
        assertEquals("options.framerate", capped.getKey());
        assertEquals(120, capped.getArgs()[0]);
    }

    @Test
    void ordinaryIntegerSlidersKeepIdentityMapping() {
        KavoraSetting<Integer> ordinary = intSetting("debug.example", 0, 128);
        assertEquals(0, KavoraOptionFactory.intValueFromSlider(ordinary, 0));
        assertEquals(128, KavoraOptionFactory.intValueFromSlider(ordinary, 128));
        assertEquals(0, KavoraOptionFactory.sliderValueFromInt(ordinary, 0));
        assertEquals(128, KavoraOptionFactory.sliderValueFromInt(ordinary, 128));
    }

    @ParameterizedTest
    @CsvSource({
            KavoraOptionFactory.SUN_INTENSITY_ID + ", 100.0, 0.51",
            KavoraOptionFactory.TERRAIN_EMISSION_MULTIPLIER_ID + ", 50.0, 0.52"
    })
    void exponentialSlidersBalanceRangesAroundOne(String id, double max, double expectedOnePosition) {
        double onePosition = KavoraOptionFactory.sliderValueFromFloat(
                id, 1.0, 0.0, max);

        assertEquals(expectedOnePosition, onePosition, 1.0e-9);
        assertEquals(
                1.0,
                KavoraOptionFactory.floatValueFromSlider(id, onePosition, 0.0, max),
                1.0e-9);
        assertEquals(
                0.0,
                KavoraOptionFactory.floatValueFromSlider(id, 0.0, 0.0, max));
        assertEquals(
                max,
                KavoraOptionFactory.floatValueFromSlider(id, 1.0, 0.0, max),
                1.0e-9);
    }

    @Test
    void ordinaryFloatSlidersRemainLinear() {
        assertEquals(
                25.0,
                KavoraOptionFactory.floatValueFromSlider("debug.example", 0.25, 0.0, 100.0));
        assertEquals(
                0.25,
                KavoraOptionFactory.sliderValueFromFloat(
                        "debug.example", 25.0, 0.0, 100.0));
    }

    @Test
    void zeroRendersAsAutoForBothDistanceSliders() {
        for (String id : List.of("dist.entity", "dist.light")) {
            assertEquals("kavora.settings.value.auto",
                    ((TranslatableContents) KavoraOptionFactory.intValueText(id, 0).getContents()).getKey(), id);
        }
        assertEquals("7", KavoraOptionFactory.intValueText("dist.entity", 7).getString());
        assertEquals("0", KavoraOptionFactory.intValueText("light.reach", 0).getString(),
                "only sentinel-backed sliders render Auto");
    }
}
