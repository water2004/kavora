/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.sky;

import java.util.function.BiConsumer;

/** Publishes world metadata separately from the user's night-sky appearance settings. */
final class NightSkyState {
    private final BiConsumer<String, String> writer;
    private int scene;
    private float visibility = Float.NaN;
    private float angle = Float.NaN;
    private float seconds = Float.NaN;
    private int phase = -1;

    NightSkyState(BiConsumer<String, String> writer) {
        this.writer = writer;
    }

    void update(float starBrightness, float starAngle, double worldSeconds, int moonPhase) {
        float visible = Math.clamp(starBrightness, 0.0f, 1.0f);
        float time = (float) worldSeconds;
        if (Float.compare(visibility, visible) != 0) {
            writer.accept("rtx.nightSky.visibility", Float.toString(visible));
            visibility = visible;
        }
        if (visible > 0 && Float.compare(angle, starAngle) != 0) {
            writer.accept("rtx.nightSky.starAngle", Float.toString(starAngle));
            angle = starAngle;
        }
        if (visible > 0) {
            publishTime(time);
        }
        int nextPhase = moonPhase & 7;
        if (phase != nextPhase) {
            writer.accept("rtx.nightSky.moonPhase", Integer.toString(nextPhase));
            phase = nextPhase;
        }
        selectScene(1);
    }

    void updateEnd(double worldSeconds) {
        publishTime((float) worldSeconds);
        selectScene(2);
    }

    private void publishTime(float time) {
        if (Float.compare(seconds, time) != 0) {
            writer.accept("rtx.nightSky.worldTimeSeconds", Float.toString(time));
            seconds = time;
        }
    }

    void disable() {
        selectScene(0);
    }

    private void selectScene(int next) {
        if (scene != next) {
            writer.accept("rtx.nightSky.sceneType", Integer.toString(next));
            scene = next;
        }
    }
}
