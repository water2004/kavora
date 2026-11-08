/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nvidia.rtxremix.minecraft.QualityPreset;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.LinkedHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Persists the visual selection and its editable values independently of display reconstruction. */
public final class RtxSettingsStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(RtxSettingsStore.class);
    // A file whose version does not match is rejected, never converted: the
    // meaning of a stored value can change with the version, and a mismatched
    // file read as if it matched is a silent wrong setting. Defaults re-apply
    // on the next launch.
    private static final int VERSION = 3;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;

    public RtxSettingsStore(Path file) {
        this.file = file;
    }

    public void save(RtxSettings settings) {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        JsonObject quality = new JsonObject();
        quality.addProperty("base", settings.quality().base().id());
        quality.addProperty("custom", settings.quality().customized());
        root.add("quality", quality);
        JsonObject values = new JsonObject();
        for (RtxSetting<?> s : settings.all()) {
            // Unedited artistic defaults must remain available to world/biome conf layers.
            if (!RtxQualityPresets.owns(s.id()) && s.isDefault()) {
                continue;
            }
            switch (s.kind()) {
                case BOOL -> values.addProperty(s.id(), (Boolean) s.get());
                case INT -> values.addProperty(s.id(), (Integer) s.get());
                case FLOAT -> values.addProperty(s.id(), (Double) s.get());
            }
        }
        root.add("settings", values);
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer w = new OutputStreamWriter(Files.newOutputStream(temporary), StandardCharsets.UTF_8)) {
                GSON.toJson(root, w);
            }
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.warn("Failed to save RTX settings to {}", file, e);
        }
    }

    public void load(RtxSettings settings) {
        if (!Files.exists(file)) {
            return;
        }
        JsonObject values;
        RtxQualitySelection quality = null;
        try (Reader r = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            int version = root.has("version") ? root.get("version").getAsInt() : 0;
            if (version != VERSION) {
                LOGGER.warn("RTX settings file {} is version {}, expected {}; ignoring it and "
                        + "using defaults, because a file from another version cannot be read "
                        + "safely.", file, version, VERSION);
                return;
            }
            if (!root.has("settings")) {
                return;
            }
            values = root.getAsJsonObject("settings");
            if (root.has("quality")) {
                JsonObject selection = root.getAsJsonObject("quality");
                var base = QualityPreset.byId(selection.get("base").getAsString());
                if (base.isPresent()) quality = new RtxQualitySelection(base.get(), selection.get("custom").getAsBoolean());
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to read RTX settings from {}, ignoring", file, e);
            return;
        }
        Map<RtxSetting<?>, Object> loaded = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
            RtxSetting<?> setting = settings.byId(entry.getKey());
            if (setting == null) {
                LOGGER.warn("Unknown RTX setting id '{}' in config, skipping", entry.getKey());
                continue;
            }
            Object value = readEntry(setting, entry.getValue());
            if (value != null) loaded.put(setting, value);
        }
        boolean visualOverride = loaded.keySet().stream()
                .anyMatch(setting -> setting.group().category() != SettingCategory.PERFORMANCE);
        if (visualOverride) settings.beforeVisualApply();
        loaded.forEach(RtxSettingsStore::applyEntry);
        if (quality != null) settings.setQuality(quality);
        else if (visualOverride) {
            settings.setQuality(new RtxQualitySelection(settings.quality().base(), true));
        }
    }

    private Object readEntry(RtxSetting<?> setting, JsonElement value) {
        try {
            return switch (setting.kind()) {
                case BOOL -> value.getAsBoolean();
                case INT -> {
                    int v = value.getAsInt();
                    if (!setting.inRange(v)) {
                        warnRange(setting, v);
                        yield null;
                    }
                    yield v;
                }
                case FLOAT -> {
                    double v = value.getAsDouble();
                    if (!setting.inRange(v)) {
                        warnRange(setting, v);
                        yield null;
                    }
                    yield v;
                }
            };
        } catch (Exception e) {
            LOGGER.warn("Failed to read saved RTX setting '{}', skipping", setting.id(), e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> void applyEntry(RtxSetting<T> setting, Object value) {
        boolean different = setting.kind() == RtxSetting.Kind.FLOAT
                ? Float.compare(((Number) setting.get()).floatValue(), ((Number) value).floatValue()) != 0
                : !setting.get().equals(value);
        if (different || RtxQualityPresets.owns(setting.id())) setting.apply((T) value);
    }

    private void warnRange(RtxSetting<?> setting, double v) {
        LOGGER.warn("Saved RTX setting '{}' value {} out of range [{}, {}], skipping",
                setting.id(), v, setting.min(), setting.max());
    }
}
