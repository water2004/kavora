/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import com.nvidia.rtxremix.minecraft.QualityPreset;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/** Pending shader options, shared by every folder in one editing session. */
final class RtxSettingsSession {
    private final RtxSettings settings;
    private final Map<RtxSetting<?>, Object> pending = new LinkedHashMap<>();
    private final Map<RtxSetting<?>, Object> baseline = new LinkedHashMap<>();
    private final Set<RtxSetting<?>> presetWrites = new HashSet<>();
    private QualityPreset basePreset;
    private boolean inheritedCustomization;

    RtxSettingsSession(RtxSettings settings) {
        this.settings = settings;
        discard();
    }

    @SuppressWarnings("unchecked")
    <T> T value(RtxSetting<T> setting) { return (T) pending.get(setting); }

    <T> void stage(RtxSetting<T> setting, T value) { pending.put(setting, value); }

    void stagePreset(QualityPreset preset, Map<String, ?> values) {
        Map<RtxSetting<?>, Object> staged = new LinkedHashMap<>();
        values.forEach((id, value) -> {
            var setting = settings.byId(id);
            if (setting == null || !pending.containsKey(setting)) {
                throw new IllegalArgumentException("Preset contains an unknown visual setting: " + id);
            }
            boolean valid = switch (setting.kind()) {
                case BOOL -> value instanceof Boolean;
                case INT -> value instanceof Integer number && setting.inRange(number)
                        && number % setting.step() == 0;
                case FLOAT -> value instanceof Double number && setting.inRange(number);
            };
            if (!valid) throw new IllegalArgumentException("Invalid preset value for " + id + ": " + value);
            staged.put(setting, value);
        });
        pending.putAll(staged);
        presetWrites.clear();
        presetWrites.addAll(staged.keySet());
        basePreset = preset;
        inheritedCustomization = false;
        baseline.clear();
        baseline.putAll(pending);
    }

    RtxQualitySelection quality() {
        boolean custom = inheritedCustomization || pending.entrySet().stream()
                .anyMatch(entry -> different(entry.getKey(), entry.getValue(), baseline.get(entry.getKey())));
        return new RtxQualitySelection(basePreset, custom);
    }

    boolean changed(RtxSetting<?> setting) {
        return different(setting, pending.get(setting), setting.get());
    }

    private static boolean different(RtxSetting<?> setting, Object value, Object reference) {
        return setting.kind() == RtxSetting.Kind.FLOAT
                ? Float.compare(((Number) value).floatValue(), ((Number) reference).floatValue()) != 0
                : !java.util.Objects.equals(value, reference);
    }

    boolean hasChanges() {
        return !presetWrites.isEmpty() || !quality().equals(settings.quality())
                || pending.keySet().stream().anyMatch(this::changed);
    }

    void reset(Predicate<RtxSetting<?>> scope) {
        for (var setting : pending.keySet()) {
            if (scope.test(setting)) pending.put(setting, setting.defaultValue());
        }
    }

    EnumSet<SettingGroup> apply() {
        EnumSet<SettingGroup> changed = EnumSet.noneOf(SettingGroup.class);
        if (!hasChanges()) return changed;
        RtxQualitySelection selected = quality();
        settings.beforeVisualApply();
        for (var setting : pending.keySet()) {
            boolean valueChanged = changed(setting);
            // Owned values must be explicit user writes even when equal to the cached value.
            if (!valueChanged && !presetWrites.contains(setting) && !RtxQualityPresets.owns(setting.id())) continue;
            applyOne(setting);
            if (valueChanged) changed.add(setting.group());
        }
        settings.setQuality(selected);
        discard();
        return changed;
    }

    private <T> void applyOne(RtxSetting<T> setting) { setting.apply(value(setting)); }

    void discard() {
        presetWrites.clear();
        pending.clear();
        for (var setting : settings.all()) {
            if (setting.group().category() != SettingCategory.PERFORMANCE) pending.put(setting, setting.get());
        }
        baseline.clear();
        baseline.putAll(pending);
        basePreset = settings.quality().base();
        inheritedCustomization = settings.quality().customized();
    }
}
