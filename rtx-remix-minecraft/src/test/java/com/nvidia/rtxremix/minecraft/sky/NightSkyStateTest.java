/* SPDX-License-Identifier: Apache-2.0 */
package com.nvidia.rtxremix.minecraft.sky;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NightSkyStateTest {
    @Test
    void lunarPhasePublishesInDaylightWithoutEnablingStarsOrTheirAnimation() {
        List<String> writes = new ArrayList<>();
        var state = new NightSkyState((key, value) -> writes.add(key + "=" + value));
        state.update(0, 0, 100, 0);
        writes.clear();
        state.update(0, .1f, 101, 3);
        state.update(0, .2f, 102, 3);
        assertEquals(List.of("rtx.nightSky.moonPhase=3"), writes);
    }
    @Test
    void dayDoesNotPublishUnusedAnimation() {
        List<String> writes = new ArrayList<>();
        var state = new NightSkyState((key, value) -> writes.add(key + "=" + value));
        state.update(0, 0, 100, 0);
        writes.clear();
        state.update(0, .1f, 101, 0);
        assertTrue(writes.isEmpty());
        state.update(.5f, .7f, 102, 0);
        assertEquals(List.of("rtx.nightSky.visibility=0.5", "rtx.nightSky.starAngle=0.7",
                "rtx.nightSky.worldTimeSeconds=102.0"), writes);
    }

    @Test
    void dimensionTransitionDisablesAndRestoresTheSameSky() {
        List<String> writes = new ArrayList<>();
        var state = new NightSkyState((key, value) -> writes.add(key + "=" + value));
        state.update(.5f, .7f, 102, 0);
        writes.clear();
        state.disable();
        state.disable();
        state.update(.5f, .7f, 102, 0);
        assertEquals(List.of("rtx.nightSky.sceneType=0", "rtx.nightSky.sceneType=1"), writes);
    }

    @Test
    void endPublishesItsAnimationClockAndRestoresTheCelestialScene() {
        List<String> writes = new ArrayList<>();
        var state = new NightSkyState((key, value) -> writes.add(key + "=" + value));
        state.update(.5f, .7f, 102, 0);
        writes.clear();
        state.updateEnd(103);
        state.updateEnd(103);
        state.updateEnd(104);
        state.update(.5f, .7f, 104, 0);
        assertEquals(List.of("rtx.nightSky.worldTimeSeconds=103.0", "rtx.nightSky.sceneType=2",
                "rtx.nightSky.worldTimeSeconds=104.0", "rtx.nightSky.sceneType=1"), writes);
    }
}
