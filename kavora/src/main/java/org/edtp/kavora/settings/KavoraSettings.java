/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.settings;

import org.edtp.kavora.QualityPreset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Id-indexed registry of curated settings; the list/map structure is immutable after construction.
 * {@link #resetAll()} resets each overridden setting through its applier and mutates live renderer state.
 * Iteration order = insertion order.
 */
public final class KavoraSettings {

    private final List<KavoraSetting<?>> all;
    private final Map<String, KavoraSetting<?>> byId;
    private final Runnable afterVisualApply;
    private KavoraQualitySelection quality = new KavoraQualitySelection(QualityPreset.ULTRA, false);

    public KavoraSettings(List<KavoraSetting<?>> settings) {
        this(settings, () -> {});
    }

    KavoraSettings(List<KavoraSetting<?>> settings, Runnable afterVisualApply) {
        this.afterVisualApply = java.util.Objects.requireNonNull(afterVisualApply);
        Map<String, KavoraSetting<?>> map = new LinkedHashMap<>();
        for (KavoraSetting<?> s : settings) {
            if (map.put(s.id(), s) != null) {
                throw new IllegalArgumentException("Duplicate setting id: " + s.id());
            }
        }
        this.all = List.copyOf(settings);
        this.byId = Collections.unmodifiableMap(map);
    }

    public List<KavoraSetting<?>> all() {
        return all;
    }

    public List<KavoraSetting<?>> group(SettingGroup group) {
        List<KavoraSetting<?>> out = new ArrayList<>();
        for (KavoraSetting<?> s : all) {
            if (s.group() == group) {
                out.add(s);
            }
        }
        return out;
    }

    List<KavoraSetting<?>> category(SettingCategory category) {
        List<KavoraSetting<?>> out = new ArrayList<>();
        for (KavoraSetting<?> setting : all) {
            if (setting.group().category() == category) {
                out.add(setting);
            }
        }
        return out;
    }

    public KavoraSetting<?> byId(String id) {
        return byId.get(id);
    }

    boolean hasGroup(SettingGroup group) {
        return all.stream().anyMatch(setting -> group.contains(setting.group()));
    }

    public KavoraQualitySelection quality() { return quality; }

    void afterVisualApply() { afterVisualApply.run(); }

    public void setQuality(KavoraQualitySelection quality) {
        this.quality = java.util.Objects.requireNonNull(quality);
    }

    public void resetAll() {
        for (KavoraSetting<?> s : all) {
            s.reset();
        }
        afterVisualApply();
    }

    void resetGroup(SettingGroup group) {
        for (KavoraSetting<?> setting : all) {
            if (group.contains(setting.group())) {
                setting.reset();
            }
        }
    }

    void resetCategory(SettingCategory category) {
        for (KavoraSetting<?> setting : all) {
            if (setting.group().category() == category) setting.reset();
        }
    }

    boolean hasOverrides(SettingGroup group) {
        for (KavoraSetting<?> setting : all) {
            if (setting.group() == group && !setting.isDefault()) {
                return true;
            }
        }
        return false;
    }
}
