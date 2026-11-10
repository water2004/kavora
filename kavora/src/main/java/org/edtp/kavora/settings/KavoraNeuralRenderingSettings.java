/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.settings;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.edtp.kavora.KavoraMod;

/** DLSS 5 settings have their own transaction and do not participate in quality presets. */
final class KavoraNeuralRenderingSettings {
    static final String PREFIX = "rtx.dlssNeuralRendering.";
    static final String ENABLED = PREFIX + "enable";
    static final String MODEL = "neuralRendering.model";
    static final List<Control> ADVANCED = List.of(
            new Control("model", 0),
            new Control("intensity", 1.0),
            new Control("structuralStrength", 0.7),
            new Control("toneStrength", 0.3),
            new Control("useAutoMask", true),
            new Control("skinStructureStrength", 0.5),
            new Control("enableHighlightRecovery", true),
            new Control("enableVolumetricControlMask", true));

    private KavoraNeuralRenderingSettings() {}

    static int status() {
        return KavoraVideoSettings.available()
                ? KavoraMod.renderer.getRemixApi().getDlssNeuralRenderingStatus() : -1;
    }

    static boolean supported() { return status() == 1; }

    static boolean enabled() {
        return supported() && KavoraVideoSettings.readBoolean(ENABLED, false);
    }

    static Component tooltip() {
        return Component.translatable("kavora.video.neuralRendering." + switch (status()) {
            case 1 -> "enabled.tip";
            case -1 -> "initializing";
            default -> "unavailable";
        });
    }

    static void open(Screen parent) {
        // Sodium can invoke an external button's callback through keyboard navigation.
        if (!supported()) return;
        List<KavoraSetting<?>> settings = new ArrayList<>(ADVANCED.size());
        for (Control control : ADVANCED) settings.add(control.createSetting());
        Minecraft.getInstance().gui.setScreen(new KavoraSettingsScreen(parent, new KavoraSettings(settings),
                KavoraVideoSettings::save, SettingGroup.NEURAL_RENDERING,
                Component.translatable("kavora.video.neuralRendering.title")));
    }

    record Control(String name, Object defaultValue) {
        String key() { return PREFIX + name; }

        boolean valid(String value) {
            return switch (defaultValue) {
                case Integer ignored -> {
                    int model = Integer.parseInt(value);
                    yield model >= 0 && model <= 2;
                }
                case Double ignored -> {
                    double strength = Double.parseDouble(value);
                    yield strength >= 0.0 && strength <= 1.0;
                }
                case Boolean ignored -> value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false");
                default -> throw new IllegalStateException("Unsupported DLSS 5 control: " + name);
            };
        }

        KavoraSetting<?> createSetting() {
            String id = "neuralRendering." + name;
            String label = "kavora.video." + id;
            String tooltip = label + ".tip";
            String key = key();
            String initial = KavoraVideoSettings.neuralOptionValue(this);
            var group = SettingGroup.NEURAL_RENDERING;
            return switch (defaultValue) {
                case Integer fallback -> {
                    int[] value = {Integer.parseInt(initial)};
                    yield KavoraSetting.ofInt(id, group, label, tooltip, 0, 2, fallback, () -> value[0], next -> {
                        KavoraVideoSettings.stage(key, next.toString());
                        value[0] = next;
                    }).withChoices();
                }
                case Double fallback -> {
                    double[] value = {Double.parseDouble(initial)};
                    yield KavoraSetting.ofFloat(id, group, label, tooltip, 0, 1, fallback, () -> value[0], next -> {
                        KavoraVideoSettings.stage(key, Float.toString(next.floatValue()));
                        value[0] = next;
                    });
                }
                case Boolean fallback -> {
                    boolean[] value = {Boolean.parseBoolean(initial)};
                    yield KavoraSetting.ofBool(id, group, label, tooltip, fallback, () -> value[0], next -> {
                        KavoraVideoSettings.stage(key, next.toString());
                        value[0] = next;
                    });
                }
                default -> throw new IllegalStateException("Unsupported DLSS 5 control: " + name);
            };
        }
    }
}
