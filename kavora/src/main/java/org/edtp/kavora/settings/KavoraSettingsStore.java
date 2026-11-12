/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.edtp.kavora.QualityPreset;
import org.edtp.kavora.logic.AtmosphereDimension;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.LinkedHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Persists the visual selection and its editable values independently of display reconstruction. */
public final class KavoraSettingsStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(KavoraSettingsStore.class);
    private static final int FIRST_RELEASE_SCHEMA = 3;
    private static final int VERSION = 4;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private boolean preserveFile;

    public KavoraSettingsStore(Path file) {
        this.file = file;
    }

    public void save(KavoraSettings settings) {
        settings.afterVisualApply();
        if (preserveFile) return;
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        JsonObject quality = new JsonObject();
        quality.addProperty("base", settings.quality().base().id());
        quality.addProperty("custom", settings.quality().customized());
        root.add("quality", quality);
        JsonObject values = new JsonObject();
        for (KavoraSetting<?> s : settings.all()) {
            // Unedited artistic defaults must remain available to world/biome conf layers.
            if (!KavoraQualityPresets.owns(s.id()) && s.isDefault()) {
                continue;
            }
            switch (s.kind()) {
                case BOOL -> values.addProperty(s.id(), (Boolean) s.get());
                case INT -> values.addProperty(s.id(), (Integer) s.get());
                case FLOAT -> values.addProperty(s.id(), (Double) s.get());
            }
        }
        root.add("settings", values);
        write(root);
    }

    private void write(JsonObject root) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer w = new OutputStreamWriter(Files.newOutputStream(temporary), StandardCharsets.UTF_8)) {
                GSON.toJson(root, w);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to save RTX settings to {}", file, e);
        }
    }

    public void load(KavoraSettings settings) {
        preserveFile = false;
        if (!Files.exists(file)) {
            return;
        }
        JsonObject root;
        JsonObject values;
        QualityPreset base = settings.quality().base();
        boolean migrated;
        try (Reader r = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
            int version = root.has("version") ? root.get("version").getAsBigDecimal().intValueExact() : 0;
            if (version < FIRST_RELEASE_SCHEMA || version > VERSION) {
                preserveFile = true;
                LOGGER.warn("RTX settings file {} has unsupported schema {}; preserving it", file, version);
                return;
            }
            migrated = version != VERSION;
            migrate(root, version);
            values = root.getAsJsonObject("settings");
            if (values == null) throw new IllegalArgumentException("Missing settings object");
            if (root.has("quality")) {
                JsonObject selection = root.getAsJsonObject("quality");
                base = QualityPreset.byId(selection.get("base").getAsString()).orElse(base);
            }
        } catch (Exception e) {
            preserveFile = true;
            LOGGER.warn("Failed to read RTX settings from {}, ignoring", file, e);
            return;
        }
        Map<KavoraSetting<?>, Object> loaded = new LinkedHashMap<>();
        if (root.has("quality")) {
            KavoraQualityPresets.values(base).forEach((id, value) -> {
                KavoraSetting<?> setting = settings.byId(id);
                if (setting != null) loaded.put(setting, value);
            });
        }
        for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
            KavoraSetting<?> setting = settings.byId(entry.getKey());
            if (setting == null) {
                LOGGER.warn("Unknown RTX setting id '{}' in config, skipping", entry.getKey());
                continue;
            }
            Object value = readEntry(setting, entry.getValue());
            if (value != null) loaded.put(setting, value);
        }
        loaded.forEach(KavoraSettingsStore::applyEntry);
        settings.afterVisualApply();
        settings.setQuality(KavoraQualityPresets.selection(settings, base, KavoraSetting::get));
        if (migrated) {
            JsonObject quality = root.has("quality") ? root.getAsJsonObject("quality") : new JsonObject();
            quality.addProperty("base", base.id());
            quality.addProperty("custom", settings.quality().customized());
            root.add("quality", quality);
            write(root);
        }
    }

    private static void migrate(JsonObject root, int version) {
        while (version < VERSION) {
            switch (version) {
                case 3 -> migrateRelease3To4(root.getAsJsonObject("settings"));
                default -> throw new IllegalArgumentException("No migration from settings schema " + version);
            }
            root.addProperty("version", ++version);
        }
    }

    private static void migrateRelease3To4(JsonObject values) {
        values.remove("image.saturation");
        for (String suffix : java.util.List.of("range", "anisotropy", "baseHeight", "heightFalloff",
                "transmittance.red", "transmittance.green", "transmittance.blue",
                "scatteringAlbedo.red", "scatteringAlbedo.green", "scatteringAlbedo.blue")) {
            JsonElement value = values.remove("fog." + suffix);
            if (value == null) continue;
            for (var dimension : AtmosphereDimension.values()) {
                String scopedId = "fog." + dimension.id() + "." + suffix;
                if (!values.has(scopedId)) values.add(scopedId, value.deepCopy());
            }
        }
    }

    private Object readEntry(KavoraSetting<?> setting, JsonElement value) {
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
    private static <T> void applyEntry(KavoraSetting<T> setting, Object value) {
        boolean different = setting.kind() == KavoraSetting.Kind.FLOAT
                ? Float.compare(((Number) setting.get()).floatValue(), ((Number) value).floatValue()) != 0
                : !setting.get().equals(value);
        if (different || KavoraQualityPresets.owns(setting.id())) setting.apply((T) value);
    }

    private void warnRange(KavoraSetting<?> setting, double v) {
        LOGGER.warn("Saved RTX setting '{}' value {} out of range [{}, {}], skipping",
                setting.id(), v, setting.min(), setting.max());
    }
}
