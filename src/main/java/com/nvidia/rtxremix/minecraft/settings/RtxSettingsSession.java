/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/** Pending shader options, shared by every folder in one editing session. */
final class RtxSettingsSession {
    private final RtxSettings settings;
    private final Map<RtxSetting<?>, Object> pending = new LinkedHashMap<>();

    RtxSettingsSession(RtxSettings settings) {
        this.settings = settings;
        discard();
    }

    @SuppressWarnings("unchecked")
    <T> T value(RtxSetting<T> setting) { return (T) pending.get(setting); }

    <T> void stage(RtxSetting<T> setting, T value) { pending.put(setting, value); }

    boolean changed(RtxSetting<?> setting) {
        Object value = pending.get(setting);
        return setting.kind() == RtxSetting.Kind.FLOAT
                ? Float.compare(((Number) value).floatValue(), ((Number) setting.get()).floatValue()) != 0
                : !java.util.Objects.equals(value, setting.get());
    }

    boolean hasChanges() { return pending.keySet().stream().anyMatch(this::changed); }

    void reset(Predicate<RtxSetting<?>> scope) {
        for (var setting : pending.keySet()) {
            if (scope.test(setting)) pending.put(setting, setting.defaultValue());
        }
    }

    EnumSet<SettingGroup> apply() {
        EnumSet<SettingGroup> changed = EnumSet.noneOf(SettingGroup.class);
        for (var setting : pending.keySet()) {
            if (!changed(setting)) continue;
            applyOne(setting);
            changed.add(setting.group());
        }
        discard();
        return changed;
    }

    private <T> void applyOne(RtxSetting<T> setting) { setting.apply(value(setting)); }

    void discard() {
        for (var setting : settings.all()) {
            if (setting.group().category() != SettingCategory.PERFORMANCE) pending.put(setting, setting.get());
        }
    }
}
