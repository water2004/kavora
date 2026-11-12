/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.settings;

import org.edtp.kavora.QualityPreset;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/** Pending shader options, shared by every folder in one editing session. */
final class KavoraSettingsSession {
    private final KavoraSettings settings;
    private final Map<KavoraSetting<?>, Object> pending = new LinkedHashMap<>();
    private final Set<KavoraSetting<?>> presetWrites = new HashSet<>();
    private QualityPreset basePreset;

    KavoraSettingsSession(KavoraSettings settings) {
        this.settings = settings;
        discard();
    }

    @SuppressWarnings("unchecked")
    <T> T value(KavoraSetting<T> setting) { return (T) pending.get(setting); }

    <T> void stage(KavoraSetting<T> setting, T value) { pending.put(setting, value); }

    void stagePreset(QualityPreset preset, Map<String, ?> values) {
        Map<KavoraSetting<?>, Object> staged = new LinkedHashMap<>();
        values.forEach((id, value) -> {
            var setting = settings.byId(id);
            if (setting == null || !pending.containsKey(setting) || !KavoraQualityPresets.owns(id)) {
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
    }

    KavoraQualitySelection quality() {
        return KavoraQualityPresets.selection(settings, basePreset, pending::get);
    }

    boolean changed(KavoraSetting<?> setting) {
        return different(setting, pending.get(setting), setting.get());
    }

    private static boolean different(KavoraSetting<?> setting, Object value, Object reference) {
        return setting.kind() == KavoraSetting.Kind.FLOAT
                ? Float.compare(((Number) value).floatValue(), ((Number) reference).floatValue()) != 0
                : !java.util.Objects.equals(value, reference);
    }

    boolean hasChanges() {
        return !presetWrites.isEmpty() || !quality().equals(settings.quality())
                || pending.keySet().stream().anyMatch(this::changed);
    }

    void reset(Predicate<KavoraSetting<?>> scope) {
        for (var setting : pending.keySet()) {
            if (scope.test(setting)) pending.put(setting, setting.defaultValue());
        }
    }

    EnumSet<SettingGroup> apply() {
        EnumSet<SettingGroup> changed = EnumSet.noneOf(SettingGroup.class);
        if (!hasChanges()) return changed;
        KavoraQualitySelection selected = quality();
        settings.beforeVisualApply();
        for (var setting : pending.keySet()) {
            boolean valueChanged = changed(setting);
            if (!valueChanged && !presetWrites.contains(setting)) continue;
            applyOne(setting);
            if (valueChanged) changed.add(setting.group());
        }
        settings.afterVisualApply();
        settings.setQuality(selected);
        discard();
        return changed;
    }

    private <T> void applyOne(KavoraSetting<T> setting) { setting.apply(value(setting)); }

    void discard() {
        presetWrites.clear();
        pending.clear();
        for (var setting : settings.all()) {
            if (setting.group().category() != SettingCategory.PERFORMANCE) pending.put(setting, setting.get());
        }
        basePreset = settings.quality().base();
    }
}
