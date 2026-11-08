/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.RemixAPI;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Explicit video-menu overrides; native reads can lag a write by several frames. */
public final class RtxVideoSettings {
    public static final String UPSCALER = "rtx.upscalerType";
    public static final String DLSS = "rtx.qualityDLSS";
    public static final String RECONSTRUCTION = "rtx.enableRayReconstruction";
    public static final String FRAME_GENERATION = "frameGenerationMultiplier";
    private static final String ENABLED = "enabled";
    private static final Logger LOGGER = LoggerFactory.getLogger(RtxVideoSettings.class);
    private static final Map<String, String> saved = new LinkedHashMap<>();
    private static final Map<String, String> pending = new LinkedHashMap<>();

    private RtxVideoSettings() {}

    public static boolean available() {
        return RtxRemixMod.renderer != null && RtxRemixMod.renderer.isInitialized();
    }

    private static RemixAPI api() {
        return RtxRemixMod.renderer.getRemixApi();
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("rtxremix-video.json");
    }

    public static void load() {
        saved.clear();
        pending.clear();
        if (!Files.isRegularFile(file())) return;
        try (var reader = Files.newBufferedReader(file())) {
            JsonObject values = JsonParser.parseReader(reader).getAsJsonObject();
            for (String key : new String[] { UPSCALER, DLSS, RECONSTRUCTION, FRAME_GENERATION, ENABLED }) {
                if (!values.has(key)) continue;
                String value = values.get(key).getAsString();
                if (valid(key, value)) saved.put(key, value);
                else LOGGER.warn("Ignoring invalid video setting {}={}", key, value);
            }
            apply(saved);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not load RTX video settings", e);
        }
    }

    static boolean valid(String key, String value) {
        try {
            return switch (key) {
                case UPSCALER -> value.equals("0") || value.equals("1");
                case DLSS -> Integer.parseInt(value) >= 0 && Integer.parseInt(value) <= 5;
                case FRAME_GENERATION -> Integer.parseInt(value) >= 1 && Integer.parseInt(value) <= 7;
                case RECONSTRUCTION, ENABLED -> value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false");
                default -> false;
            };
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static int readInt(String key, int fallback) {
        if (!available()) return fallback;
        try {
            return Integer.parseInt(api().getConfigVariableResolved(key));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    static boolean readBoolean(String key, boolean fallback) {
        return available() ? Boolean.parseBoolean(api().getConfigVariableResolved(key)) : fallback;
    }

    static boolean reconstruction() {
        return available() && Boolean.parseBoolean(api().getConfigVariableResolved(RECONSTRUCTION));
    }

    static com.nvidia.rtxremix.FrameGenerationCapabilities frameGenerationCapabilities() {
        var capabilities = new com.nvidia.rtxremix.FrameGenerationCapabilities();
        if (available()) api().getFrameGenerationCapabilities(capabilities);
        return capabilities;
    }

    static FrameGenerationMode frameGenerationMode() {
        if (!readBoolean("rtx.dlfg.enable", false)) return FrameGenerationMode.OFF;
        var allowed = FrameGenerationMode.allowed(frameGenerationCapabilities());
        int selected = readInt("rtx.dlfg.maxInterpolatedFrames", 1) + 1;
        return allowed.stream().filter(mode -> mode.multiplier <= selected)
                .max(java.util.Comparator.comparingInt(mode -> mode.multiplier)).orElse(FrameGenerationMode.OFF);
    }

    static void stage(String key, String value) {
        if (!valid(key, value)) throw new IllegalArgumentException("Invalid video setting: " + key);
        pending.put(key, value);
    }

    static void stageEnabled(boolean enabled) {
        stage(ENABLED, Boolean.toString(enabled));
    }

    static void save() {
        if (pending.isEmpty() || !available()) return;
        apply(pending);
        saved.putAll(pending);
        pending.clear();
        writeSaved();
    }

    private static void writeSaved() {
        JsonObject json = new JsonObject();
        saved.forEach(json::addProperty);
        Path target = file();
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(json));
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.warn("Could not save RTX video settings to {}", target, e);
        }
    }

    private static void apply(Map<String, String> values) {
        normalizeNativeMode(values);
        if (values.containsKey(UPSCALER) || values.containsKey(DLSS)) {
            // The native DLSS preset otherwise resets an explicit quality choice to Auto.
            api().setConfigVariable("rtx.dlssPreset", "2");
        }
        for (String key : new String[] { UPSCALER, DLSS, RECONSTRUCTION }) {
            if (values.containsKey(key)) api().setConfigVariable(key, values.get(key));
        }
        if (values.containsKey(FRAME_GENERATION)) {
            int multiplier = Integer.parseInt(values.get(FRAME_GENERATION));
            api().setConfigVariable("rtx.dlfg.maxInterpolatedFrames", Integer.toString(Math.max(1, multiplier - 1)));
            api().setConfigVariable("rtx.dlfg.enable", Boolean.toString(multiplier > 1));
        }
        if (values.containsKey(ENABLED)) RtxRemixMod.setRtxEnabled(Boolean.parseBoolean(values.get(ENABLED)));
    }

    static void normalizeNativeMode(Map<String, String> values) {
        if ("0".equals(values.get(UPSCALER))) {
            values.put(UPSCALER, "1");
            values.put(DLSS, "5");
        }
    }
}
