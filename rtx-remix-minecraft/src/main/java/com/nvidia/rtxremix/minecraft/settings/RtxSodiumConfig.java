/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import java.util.Locale;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.Range;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.caffeinemc.mods.sodium.client.config.structure.OptionPage;
import net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Video settings integration through Sodium's public registration API, as used by Iris. */
public final class RtxSodiumConfig implements ConfigEntryPoint {
    private static final String MOD_ID = "rtx-remix-renderer";
    private static final StorageEventHandler VIDEO_STORAGE = RtxVideoSettings::save;
    private static final StorageEventHandler SETTINGS_STORAGE = RtxRemixMod::saveRtxSettings;

    public enum Dlss {
        AUTO(4), QUALITY(3), BALANCED(2), PERFORMANCE(1), ULTRA_PERFORMANCE(0), DLAA(5);
        final int value;
        Dlss(int value) { this.value = value; }
        static Dlss current() {
            int value = RtxVideoSettings.readInt(RtxVideoSettings.DLSS, 4);
            for (Dlss mode : values()) if (mode.value == value) return mode;
            return AUTO;
        }
    }

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        var mod = builder.registerOwnModOptions().setName("RTX Remix")
                .setVersion("Vulkan")
                .setColorTheme(builder.createColorTheme().setBaseThemeRGB(0x76B900));
        OptionGroupBuilder rendering = builder.createOptionGroup();
        rendering.addOption(builder.createBooleanOption(id("video.enabled"))
                .setName(text("enabled")).setTooltip(text("enabled.tip"))
                .setDefaultValue(true).setBinding(RtxVideoSettings::stageEnabled, RtxRemixMod::isRtxEnabled)
                .setStorageHandler(VIDEO_STORAGE)
                .setEnabledProvider(state -> RtxVideoSettings.available(), ConfigState.UPDATE_ON_REBUILD));
        rendering.addOption(builder.createEnumOption(id("video.dlss"), Dlss.class)
                .setName(text("dlss")).setTooltip(text("dlss.tip"))
                .setDefaultValue(Dlss.AUTO).setElementNameProvider(value -> text("dlss." + value.name().toLowerCase(Locale.ROOT)))
                .setBinding(value -> {
                    RtxVideoSettings.stage(RtxVideoSettings.UPSCALER, "1");
                    RtxVideoSettings.stage(RtxVideoSettings.DLSS, Integer.toString(value.value));
                }, Dlss::current)
                .setStorageHandler(VIDEO_STORAGE)
                .setEnabledProvider(state -> RtxVideoSettings.available(), ConfigState.UPDATE_ON_REBUILD));
        rendering.addOption(builder.createBooleanOption(id("video.reconstruction"))
                .setName(text("reconstruction")).setTooltip(text("reconstruction.tip"))
                .setDefaultValue(true).setBinding(value -> RtxVideoSettings.stage(RtxVideoSettings.RECONSTRUCTION, value.toString()),
                        RtxVideoSettings::reconstruction).setStorageHandler(VIDEO_STORAGE)
                .setEnabledProvider(state -> RtxVideoSettings.available(), ConfigState.UPDATE_ON_REBUILD));
        rendering.addOption(builder.createEnumOption(id("video.frame_generation"), FrameGenerationMode.class)
                .setName(text("frameGeneration"))
                .setTooltip(mode -> {
                    var capabilities = RtxVideoSettings.frameGenerationCapabilities();
                    return capabilities.initializationComplete && capabilities.supported ? text("frameGeneration.tip")
                            : Component.translatable("rtxremix.video.frameGeneration.unavailable", capabilities.notSupportedReason);
                })
                .setDefaultValue(FrameGenerationMode.OFF)
                .setElementNameProvider(mode -> mode == FrameGenerationMode.OFF ? text("frameGeneration.off")
                        : Component.literal(mode.multiplier + "×"))
                .setAllowedValuesProvider(state -> FrameGenerationMode.allowed(RtxVideoSettings.frameGenerationCapabilities()),
                        ConfigState.UPDATE_ON_REBUILD)
                .setBinding(mode -> RtxVideoSettings.stage(RtxVideoSettings.FRAME_GENERATION, Integer.toString(mode.multiplier)),
                        RtxVideoSettings::frameGenerationMode)
                .setStorageHandler(VIDEO_STORAGE)
                .setEnabledProvider(state -> RtxVideoSettings.available()
                        && RtxVideoSettings.frameGenerationCapabilities().supported, ConfigState.UPDATE_ON_REBUILD));
        mod.addPage(builder.createOptionPage().setName(text("title")).addOptionGroup(rendering));

        RtxSettings settings = RtxRemixMod.getRtxSettings();
        if (settings != null) {
            var page = builder.createOptionPage().setName(Component.translatable(SettingCategory.PERFORMANCE.labelKey()));
            for (SettingGroup group : SettingGroup.values()) {
                if (group.category() != SettingCategory.PERFORMANCE || settings.group(group).isEmpty()) continue;
                var options = builder.createOptionGroup().setName(Component.translatable(group.labelKey()));
                for (RtxSetting<?> setting : settings.group(group)) options.addOption(option(builder, setting));
                page.addOptionGroup(options);
            }
            mod.addPage(page);
        }
        mod.addPage(builder.createExternalPage().setName(text("details"))
                .setScreenConsumer(RtxSodiumConfig::openEffects));
    }

    @SuppressWarnings("unchecked")
    private static OptionBuilder option(ConfigBuilder builder, RtxSetting<?> setting) {
        var name = Component.translatable(setting.labelKey());
        var tooltip = Component.translatable(setting.tooltipKey());
        return switch (setting.kind()) {
            case BOOL -> {
                var value = (RtxSetting<Boolean>) setting;
                yield builder.createBooleanOption(id(setting.id().toLowerCase(Locale.ROOT)))
                        .setName(name).setTooltip(tooltip).setDefaultValue(value.defaultValue())
                        .setBinding(value::apply, value::get).setStorageHandler(SETTINGS_STORAGE);
            }
            case INT -> {
                var value = (RtxSetting<Integer>) setting;
                int sentinel = RtxOptionFactory.sentinelSliderValue(value.id());
                int min = sentinel > 0 ? 1 : (int) value.min() / value.step();
                int max = sentinel > 0 ? sentinel : (int) value.max() / value.step();
                yield builder.createIntegerOption(id(setting.id().toLowerCase(Locale.ROOT)))
                        .setName(name).setTooltip(tooltip).setRange(new Range(min, max, 1))
                        .setDefaultValue(RtxOptionFactory.sliderValueFromInt(value, value.defaultValue()))
                        .setValueFormatter(i -> RtxOptionFactory.intValueText(value.id(), RtxOptionFactory.intValueFromSlider(value, i)))
                        .setBinding(i -> value.apply(RtxOptionFactory.intValueFromSlider(value, i)),
                                () -> RtxOptionFactory.sliderValueFromInt(value, value.get())).setStorageHandler(SETTINGS_STORAGE);
            }
            case FLOAT -> {
                var value = (RtxSetting<Double>) setting;
                yield builder.createIntegerOption(id(setting.id().toLowerCase(Locale.ROOT)))
                        .setName(name).setTooltip(tooltip).setRange(new Range(0, 1000, 1))
                        .setDefaultValue(floatPosition(value, value.defaultValue()))
                        .setValueFormatter(i -> RtxOptionFactory.floatValueText(value.id(), floatValue(value, i)))
                        .setBinding(i -> value.apply(floatValue(value, i)), () -> floatPosition(value, value.get()))
                        .setStorageHandler(SETTINGS_STORAGE);
            }
        };
    }

    private static int floatPosition(RtxSetting<Double> setting, double value) {
        return (int) Math.round(1000 * RtxOptionFactory.sliderValueFromFloat(setting.id(), value, setting.min(), setting.max()));
    }

    private static double floatValue(RtxSetting<Double> setting, int position) {
        return RtxOptionFactory.floatValueFromSlider(setting.id(), position / 1000.0, setting.min(), setting.max());
    }

    /** Open the same RTX page from Minecraft's Options menu and Sodium's sidebar. */
    public static void open(Screen parent) {
        OptionPage page = ConfigManager.CONFIG.getModOptions().stream()
                .filter(mod -> mod.configId().equals(MOD_ID)).flatMap(mod -> mod.pages().stream())
                .filter(OptionPage.class::isInstance).map(OptionPage.class::cast).findFirst().orElse(null);
        Minecraft.getInstance().gui.setScreen(VideoSettingsScreen.createScreen(parent, page));
    }

    /** Opens the visual-effect categories independently of Sodium's performance pages. */
    public static void openEffects(Screen parent) {
        RtxSettings settings = RtxRemixMod.getRtxSettings();
        if (settings != null) {
            Minecraft.getInstance().gui.setScreen(new RtxSettingsScreen(parent, settings, RtxRemixMod::saveRtxSettings));
        }
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath(MOD_ID, path); }
    private static Component text(String key) { return Component.translatable("rtxremix.video." + key); }
}

