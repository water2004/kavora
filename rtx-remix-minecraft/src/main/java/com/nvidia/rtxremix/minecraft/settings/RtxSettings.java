/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import com.nvidia.rtxremix.minecraft.QualityPreset;
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
public final class RtxSettings {

    private final List<RtxSetting<?>> all;
    private final Map<String, RtxSetting<?>> byId;
    private final Runnable beforeVisualApply;
    private RtxQualitySelection quality = new RtxQualitySelection(QualityPreset.ULTRA, false);

    public RtxSettings(List<RtxSetting<?>> settings) {
        this(settings, () -> {});
    }

    public RtxSettings(List<RtxSetting<?>> settings, Runnable beforeVisualApply) {
        this.beforeVisualApply = java.util.Objects.requireNonNull(beforeVisualApply);
        Map<String, RtxSetting<?>> map = new LinkedHashMap<>();
        for (RtxSetting<?> s : settings) {
            if (map.put(s.id(), s) != null) {
                throw new IllegalArgumentException("Duplicate setting id: " + s.id());
            }
        }
        this.all = List.copyOf(settings);
        this.byId = Collections.unmodifiableMap(map);
    }

    public List<RtxSetting<?>> all() {
        return all;
    }

    public List<RtxSetting<?>> group(SettingGroup group) {
        List<RtxSetting<?>> out = new ArrayList<>();
        for (RtxSetting<?> s : all) {
            if (s.group() == group) {
                out.add(s);
            }
        }
        return out;
    }

    List<RtxSetting<?>> category(SettingCategory category) {
        List<RtxSetting<?>> out = new ArrayList<>();
        for (RtxSetting<?> setting : all) {
            if (setting.group().category() == category) {
                out.add(setting);
            }
        }
        return out;
    }

    public RtxSetting<?> byId(String id) {
        return byId.get(id);
    }

    boolean hasGroup(SettingGroup group) {
        return all.stream().anyMatch(setting -> group.contains(setting.group()));
    }

    public RtxQualitySelection quality() { return quality; }

    void beforeVisualApply() { beforeVisualApply.run(); }

    public void setQuality(RtxQualitySelection quality) {
        this.quality = java.util.Objects.requireNonNull(quality);
    }

    public void resetAll() {
        for (RtxSetting<?> s : all) {
            s.reset();
        }
    }

    void resetGroup(SettingGroup group) {
        for (RtxSetting<?> setting : all) {
            if (group.contains(setting.group())) {
                setting.reset();
            }
        }
    }

    void resetCategory(SettingCategory category) {
        for (RtxSetting<?> setting : all) {
            if (setting.group().category() == category) setting.reset();
        }
    }

    boolean hasOverrides(SettingGroup group) {
        for (RtxSetting<?> setting : all) {
            if (setting.group() == group && !setting.isDefault()) {
                return true;
            }
        }
        return false;
    }
}
