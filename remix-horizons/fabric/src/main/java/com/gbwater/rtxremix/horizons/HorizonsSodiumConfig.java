/* SPDX-License-Identifier: LGPL-3.0-only */
package com.gbwater.rtxremix.horizons;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.Range;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class HorizonsSodiumConfig implements ConfigEntryPoint {
    private static final HorizonsSettings SETTINGS = HorizonsSettings.INSTANCE;
    private static final StorageEventHandler STORAGE = SETTINGS::save;

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        var mod =
                builder.registerOwnModOptions()
                        .setName("Remix Horizons")
                        .setVersion("Vulkan")
                        .setColorTheme(builder.createColorTheme().setBaseThemeRGB(0x579D73));
        var group = builder.createOptionGroup();
        group.addOption(
                builder.createBooleanOption(id("enabled"))
                        .setName(text("enabled"))
                        .setTooltip(text("enabled.tip"))
                        .setDefaultValue(true)
                        .setBinding(value -> SETTINGS.enabled = value, () -> SETTINGS.enabled)
                        .setStorageHandler(STORAGE));
        group.addOption(
                builder.createIntegerOption(id("distance"))
                        .setName(text("distance"))
                        .setTooltip(text("distance.tip"))
                        .setRange(new Range(32, 4096, 32))
                        .setDefaultValue(256)
                        .setBinding(
                                value -> SETTINGS.distanceChunks = value,
                                () -> SETTINGS.distanceChunks)
                        .setValueFormatter(
                                value ->
                                        Component.translatable("remixhorizons.video.chunks", value))
                        .setStorageHandler(STORAGE));
        group.addOption(
                builder.createIntegerOption(id("pixels"))
                        .setName(text("pixels"))
                        .setTooltip(text("pixels.tip"))
                        .setRange(new Range(32, 256, 16))
                        .setDefaultValue(64)
                        .setBinding(value -> SETTINGS.nodePixels = value, () -> SETTINGS.nodePixels)
                        .setValueFormatter(value -> Component.literal(value + " px"))
                        .setStorageHandler(STORAGE));
        group.addOption(
                builder.createIntegerOption(id("memory"))
                        .setName(text("memory"))
                        .setTooltip(text("memory.tip"))
                        .setRange(new Range(128, 2048, 128))
                        .setDefaultValue(512)
                        .setBinding(
                                value -> SETTINGS.geometryMiB = value, () -> SETTINGS.geometryMiB)
                        .setValueFormatter(value -> Component.literal(value + " MiB"))
                        .setStorageHandler(STORAGE));
        mod.addPage(builder.createOptionPage().setName(text("terrain")).addOptionGroup(group));
    }

    private static Identifier id(String key) {
        return Identifier.fromNamespaceAndPath("remix-horizons", key);
    }

    private static Component text(String key) {
        return Component.translatable("remixhorizons.video." + key);
    }
}
