/* SPDX-License-Identifier: LGPL-3.0-only */
package com.gbwater.rtxremix.horizons;

import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.*;

public final class HorizonsSettings {
    public static final HorizonsSettings INSTANCE = load();
    public boolean enabled = true;
    public int distanceChunks = 256;
    public int nodePixels = 64;
    public int geometryMiB = 512;

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("remix-horizons.json");
    }

    private static HorizonsSettings load() {
        try {
            if (!Files.exists(path())) return new HorizonsSettings();
            var settings =
                    new GsonBuilder()
                            .create()
                            .fromJson(Files.readString(path()), HorizonsSettings.class);
            if (settings == null) throw new IOException("Empty Horizons settings");
            settings.validate();
            return settings;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + path(), e);
        }
    }

    private void validate() {
        distanceChunks = Math.clamp(distanceChunks, 32, 4096);
        nodePixels = Math.clamp(nodePixels, 32, 256);
        geometryMiB = Math.clamp(geometryMiB, 128, 2048);
    }

    public void save() {
        validate();
        try {
            Files.createDirectories(path().getParent());
            Files.writeString(path(), new GsonBuilder().setPrettyPrinting().create().toJson(this));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot save " + path(), e);
        }
    }
}
