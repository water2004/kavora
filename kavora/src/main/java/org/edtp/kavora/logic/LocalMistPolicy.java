/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.logic;

/** Daily condensation episodes. The renderer owns world-space patch placement. */
final class LocalMistPolicy {
    private LocalMistPolicy() {}

    static float strength(long worldTime, float humidity, float rain) {
        long time = Math.floorMod(worldTime, 24000L);
        float morning = time >= 21000
                ? smooth((time - 21000.0f) / 2500.0f)
                : 1.0f - smooth((time - 1000.0f) / 3500.0f);
        float episode = smooth((sample(Math.floorDiv(worldTime + 3000L, 24000L)) - 0.35f) / 0.4f);
        return Math.clamp(Math.max(morning * episode * humidity, rain * humidity * 0.8f), 0, 1);
    }

    private static float sample(long day) {
        long value = day * 0x9e3779b97f4a7c15L;
        value ^= value >>> 30;
        value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27;
        value *= 0x94d049bb133111ebL;
        value ^= value >>> 31;
        return (value >>> 40) * 0x1.0p-24f;
    }

    private static float smooth(float value) {
        float t = Math.clamp(value, 0, 1);
        return t * t * (3 - 2 * t);
    }

}
