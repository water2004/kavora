/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.settings;

import org.edtp.kavora.KavoraMod;
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
public final class KavoraSodiumConfig implements ConfigEntryPoint {
    private static final String MOD_ID = "kavora";
    private static final StorageEventHandler VIDEO_STORAGE = KavoraVideoSettings::save;
    private static final StorageEventHandler SETTINGS_STORAGE = KavoraMod::saveRtxSettings;

    public enum Dlss {
        AUTO(4), QUALITY(3), BALANCED(2), PERFORMANCE(1), ULTRA_PERFORMANCE(0), DLAA(5);
        final int value;
        Dlss(int value) { this.value = value; }
        static Dlss current() {
            int value = KavoraVideoSettings.readInt(KavoraVideoSettings.DLSS, 4);
            for (Dlss mode : values()) if (mode.value == value) return mode;
            return AUTO;
        }
    }

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        var mod = builder.registerOwnModOptions().setName("Kavora")
                .setNonTintedIcon(id("icon.png"))
                .setColorTheme(builder.createColorTheme().setBaseThemeRGB(0x76B900));
        OptionGroupBuilder rendering = builder.createOptionGroup();
        rendering.addOption(builder.createBooleanOption(id("video.enabled"))
                .setName(text("enabled")).setTooltip(text("enabled.tip"))
                .setDefaultValue(true).setBinding(KavoraVideoSettings::stageEnabled, KavoraMod::isRtxEnabled)
                .setStorageHandler(VIDEO_STORAGE)
                .setEnabledProvider(state -> KavoraVideoSettings.available(), ConfigState.UPDATE_ON_REBUILD));
        rendering.addOption(builder.createEnumOption(id("video.dlss"), Dlss.class)
                .setName(text("dlss")).setTooltip(text("dlss.tip"))
                .setDefaultValue(Dlss.AUTO).setElementNameProvider(value -> text("dlss." + value.name().toLowerCase(Locale.ROOT)))
                .setBinding(value -> {
                    KavoraVideoSettings.stage(KavoraVideoSettings.UPSCALER, "1");
                    KavoraVideoSettings.stage(KavoraVideoSettings.DLSS, Integer.toString(value.value));
                }, Dlss::current)
                .setStorageHandler(VIDEO_STORAGE)
                .setEnabledProvider(state -> KavoraVideoSettings.available(), ConfigState.UPDATE_ON_REBUILD));
        rendering.addOption(builder.createBooleanOption(id("video.reconstruction"))
                .setName(text("reconstruction")).setTooltip(text("reconstruction.tip"))
                .setDefaultValue(true).setBinding(value -> KavoraVideoSettings.stage(KavoraVideoSettings.RECONSTRUCTION, value.toString()),
                        KavoraVideoSettings::reconstruction).setStorageHandler(VIDEO_STORAGE)
                .setEnabledProvider(state -> KavoraVideoSettings.available(), ConfigState.UPDATE_ON_REBUILD));
        rendering.addOption(builder.createEnumOption(id("video.frame_generation"), FrameGenerationMode.class)
                .setName(text("frameGeneration"))
                .setTooltip(mode -> {
                    var capabilities = KavoraVideoSettings.frameGenerationCapabilities();
                    return capabilities.initializationComplete && capabilities.supported ? text("frameGeneration.tip")
                            : Component.translatable("kavora.video.frameGeneration.unavailable", capabilities.notSupportedReason);
                })
                .setDefaultValue(FrameGenerationMode.OFF)
                .setElementNameProvider(mode -> mode == FrameGenerationMode.OFF ? text("frameGeneration.off")
                        : Component.literal(mode.multiplier + "×"))
                .setAllowedValuesProvider(state -> FrameGenerationMode.allowed(KavoraVideoSettings.frameGenerationCapabilities()),
                        ConfigState.UPDATE_ON_REBUILD)
                .setBinding(mode -> KavoraVideoSettings.stage(KavoraVideoSettings.FRAME_GENERATION, Integer.toString(mode.multiplier)),
                        KavoraVideoSettings::frameGenerationMode)
                .setStorageHandler(VIDEO_STORAGE)
                .setEnabledProvider(state -> KavoraVideoSettings.available()
                        && KavoraVideoSettings.frameGenerationCapabilities().supported, ConfigState.UPDATE_ON_REBUILD));
        mod.addPage(builder.createOptionPage().setName(text("title")).addOptionGroup(rendering));

        KavoraSettings settings = KavoraMod.getRtxSettings();
        if (settings != null) {
            var page = builder.createOptionPage().setName(Component.translatable(SettingCategory.PERFORMANCE.labelKey()));
            for (SettingGroup group : SettingGroup.values()) {
                if (group.category() != SettingCategory.PERFORMANCE || settings.group(group).isEmpty()) continue;
                var options = builder.createOptionGroup().setName(Component.translatable(group.labelKey()));
                for (KavoraSetting<?> setting : settings.group(group)) options.addOption(option(builder, setting));
                page.addOptionGroup(options);
            }
            mod.addPage(page);
        }
        mod.addPage(builder.createExternalPage().setName(text("details"))
                .setScreenConsumer(KavoraSodiumConfig::openEffects));
    }

    @SuppressWarnings("unchecked")
    private static OptionBuilder option(ConfigBuilder builder, KavoraSetting<?> setting) {
        var name = Component.translatable(setting.labelKey());
        var tooltip = Component.translatable(setting.tooltipKey());
        return switch (setting.kind()) {
            case BOOL -> {
                var value = (KavoraSetting<Boolean>) setting;
                yield builder.createBooleanOption(id(setting.id().toLowerCase(Locale.ROOT)))
                        .setName(name).setTooltip(tooltip).setDefaultValue(value.defaultValue())
                        .setBinding(value::apply, value::get).setStorageHandler(SETTINGS_STORAGE);
            }
            case INT -> {
                var value = (KavoraSetting<Integer>) setting;
                int sentinel = KavoraOptionFactory.sentinelSliderValue(value.id());
                int min = sentinel > 0 ? 1 : (int) value.min() / value.step();
                int max = sentinel > 0 ? sentinel : (int) value.max() / value.step();
                yield builder.createIntegerOption(id(setting.id().toLowerCase(Locale.ROOT)))
                        .setName(name).setTooltip(tooltip).setRange(new Range(min, max, 1))
                        .setDefaultValue(KavoraOptionFactory.sliderValueFromInt(value, value.defaultValue()))
                        .setValueFormatter(i -> KavoraOptionFactory.intValueText(value.id(), KavoraOptionFactory.intValueFromSlider(value, i)))
                        .setBinding(i -> value.apply(KavoraOptionFactory.intValueFromSlider(value, i)),
                                () -> KavoraOptionFactory.sliderValueFromInt(value, value.get())).setStorageHandler(SETTINGS_STORAGE);
            }
            case FLOAT -> {
                var value = (KavoraSetting<Double>) setting;
                yield builder.createIntegerOption(id(setting.id().toLowerCase(Locale.ROOT)))
                        .setName(name).setTooltip(tooltip).setRange(new Range(0, 1000, 1))
                        .setDefaultValue(floatPosition(value, value.defaultValue()))
                        .setValueFormatter(i -> KavoraOptionFactory.floatValueText(value.id(), floatValue(value, i)))
                        .setBinding(i -> value.apply(floatValue(value, i)), () -> floatPosition(value, value.get()))
                        .setStorageHandler(SETTINGS_STORAGE);
            }
        };
    }

    private static int floatPosition(KavoraSetting<Double> setting, double value) {
        return (int) Math.round(1000 * KavoraOptionFactory.sliderValueFromFloat(setting.id(), value, setting.min(), setting.max()));
    }

    private static double floatValue(KavoraSetting<Double> setting, int position) {
        return KavoraOptionFactory.floatValueFromSlider(setting.id(), position / 1000.0, setting.min(), setting.max());
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
        KavoraSettings settings = KavoraMod.getRtxSettings();
        if (settings != null) {
            Minecraft.getInstance().gui.setScreen(new KavoraSettingsScreen(parent, settings, KavoraMod::saveRtxSettings));
        }
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath(MOD_ID, path); }
    private static Component text(String key) { return Component.translatable("kavora.video." + key); }
}

