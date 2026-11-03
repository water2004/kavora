/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gametest;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.settings.RtxSodiumConfig;
import com.nvidia.rtxremix.minecraft.settings.RtxSetting;
import com.nvidia.rtxremix.minecraft.settings.RtxVideoSettings;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import java.util.LinkedHashMap;
import java.util.Map;
import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.caffeinemc.mods.sodium.client.config.structure.EnumOption;
import net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.resources.Identifier;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** Exercises Sodium's real apply/undo bindings and the native option readback. */
final class RemixSettingsChecks {
    private RemixSettingsChecks() {}

    static void run(ClientGameTestContext context) {
        var originalPreset = context.computeOnClient(client -> ConfigManager.CONFIG.readEnumOption(
                id("video.preset"), RtxSodiumConfig.Preset.class));
        boolean originalReconstruction = context.computeOnClient(client -> ConfigManager.CONFIG.readBooleanOption(id("video.reconstruction")));
        var originalDlss = context.computeOnClient(client -> ConfigManager.CONFIG.readEnumOption(
                id("video.dlss"), RtxSodiumConfig.Dlss.class));
        context.runOnClient(client -> {
            var mod = ConfigManager.CONFIG.getModOptions().stream()
                    .filter(value -> value.configId().equals("rtx-remix-renderer")).findFirst().orElseThrow();
            require(mod.pages().size() == 4, "RTX performance pages and visual-settings entry were not registered");
            require(mod.pages().getLast() instanceof net.caffeinemc.mods.sodium.client.config.structure.ExternalPage,
                    "Visual settings were embedded into the Sodium performance pages");
            RtxSodiumConfig.open(client.gui.screen());
            require(client.gui.screen() instanceof VideoSettingsScreen, "RTX menu did not open Sodium settings");
        });
        context.waitTicks(10);
        RemixWorldGameTest.captureCompletedFrame(context, "settings");
        context.runOnClient(client -> {
            RtxSodiumConfig.openEffects(client.gui.screen());
            require(client.gui.screen() instanceof com.nvidia.rtxremix.minecraft.settings.RtxSettingsScreen,
                    "Visual settings did not open their independent category screen");
        });
        context.waitTicks(3);
        context.runOnClient(client -> requireNonOverlappingWidgets(client.gui.screen()));
        RemixWorldGameTest.captureCompletedFrame(context, "settings-effects");
        checkEffects(context);
        context.runOnClient(client -> client.gui.screen().onClose());
        try {
            context.runOnClient(client -> {
                preset().modifyValue(RtxSodiumConfig.Preset.CUSTOM);
                ConfigManager.CONFIG.applyAllOptions();
            });
            context.waitFor(client -> read(RtxVideoSettings.PRESET).equals("4"), 200);
            checkAdvancedPage(context);
            context.runOnClient(client -> {
                dlss().modifyValue(RtxSodiumConfig.Dlss.DLAA);
                ConfigManager.CONFIG.applyAllOptions();
            });
            context.waitFor(client -> read(RtxVideoSettings.UPSCALER).equals("1")
                    && read(RtxVideoSettings.DLSS).equals("5"), 200);
            context.waitTicks(10);
            context.runOnClient(client -> {
                reconstruction().modifyValue(false);
                ConfigManager.CONFIG.applyAllOptions();
            });
            context.waitFor(client -> read(RtxVideoSettings.UPSCALER).equals("1")
                    && read(RtxVideoSettings.RECONSTRUCTION).equalsIgnoreCase("false"), 200);
            for (var mode : RtxSodiumConfig.Dlss.values()) {
                context.runOnClient(client -> {
                    dlss().modifyValue(mode);
                    ConfigManager.CONFIG.applyAllOptions();
                });
                String expected = switch (mode) {
                    case ULTRA_PERFORMANCE -> "0";
                    case PERFORMANCE -> "1";
                    case BALANCED -> "2";
                    case QUALITY -> "3";
                    case AUTO -> "4";
                    case DLAA -> "5";
                };
                context.waitFor(client -> read(RtxVideoSettings.DLSS).equals(expected), 200);
                context.waitTicks(10);
            }
            context.runOnClient(client -> {
                reconstruction().modifyValue(true);
                ConfigManager.CONFIG.applyAllOptions();
            });
            context.waitFor(client -> read(RtxVideoSettings.RECONSTRUCTION).equalsIgnoreCase("true"), 200);
            context.waitTicks(10);
            String before = context.computeOnClient(client -> read(RtxVideoSettings.DLSS));
            var target = before.equals("2") ? RtxSodiumConfig.Dlss.QUALITY : RtxSodiumConfig.Dlss.BALANCED;
            String targetNative = target == RtxSodiumConfig.Dlss.QUALITY ? "3" : "2";
            context.runOnClient(client -> {
                dlss().modifyValue(target);
                require(read(RtxVideoSettings.DLSS).equals(before), "Editing a control applied before Apply");
                ConfigManager.CONFIG.resetAllOptionsFromBindings();
                require(!dlss().hasChanged(), "Undo retained the modified DLSS value");
                dlss().modifyValue(target);
                ConfigManager.CONFIG.applyAllOptions();
            });
            context.waitFor(client -> read(RtxVideoSettings.DLSS).equals(targetNative), 200);
            context.runOnClient(client -> {
                var file = client.gameDirectory.toPath().resolve("config/rtxremix-video.json");
                try {
                    var json = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(file)).getAsJsonObject();
                    require(json.get(RtxVideoSettings.DLSS).getAsString().equals(targetNative), "Video setting was not persisted");
                } catch (java.io.IOException e) {
                    throw new AssertionError("Cannot read saved video settings", e);
                }
                RtxVideoSettings.load();
            });
            context.waitTicks(5);
            context.runOnClient(client -> require(read(RtxVideoSettings.DLSS).equals(targetNative),
                    "Reload changed the saved DLSS setting"));
        } finally {
            context.runOnClient(client -> {
                reconstruction().modifyValue(originalReconstruction);
                preset().modifyValue(originalPreset);
                dlss().modifyValue(originalDlss);
                ConfigManager.CONFIG.applyAllOptions();
                client.gui.setScreen(null);
            });
            context.waitTicks(5);
        }
    }

    private static void checkEffects(ClientGameTestContext context) {
        var root = context.computeOnClient(client -> (com.nvidia.rtxremix.minecraft.settings.RtxSettingsScreen) client.gui.screen());
        Map<String, Double> original = context.computeOnClient(client -> {
            var values = new LinkedHashMap<String, Double>();
            for (String id : new String[] {"clouds.coverage", "fog.transmittance.red", "fog.transmittance.green",
                    "water.ior", "water.red", "water.absorptionDistance"}) values.put(id, floating(id).get());
            return values;
        });
        try {
            context.runOnClient(client -> press(client.gui.screen(), "rtxremix.settings.category.atmosphere"));
            context.waitTicks(2);
            RemixWorldGameTest.captureCompletedFrame(context, "settings-atmosphere");
            context.runOnClient(client -> {
                press(client.gui.screen(), "rtxremix.settings.group.clouds");
                editNumber("clouds.coverage", "0.7");
                require(floating("clouds.coverage").get().equals(original.get("clouds.coverage")),
                        "Shader control applied before Apply");
            });
            context.waitTicks(2);
            context.runOnClient(client -> requireNonOverlappingWidgets(client.gui.screen()));
            RemixWorldGameTest.captureCompletedFrame(context, "settings-clouds");
            context.runOnClient(client -> {
                press(client.gui.screen(), "rtxremix.settings.back");
                press(client.gui.screen(), "rtxremix.settings.group.fog");
                editNumber("fog.transmittance.red", "0.8");
                editNumber("fog.transmittance.green", "0.85");
            });
            context.waitTicks(2);
            context.runOnClient(client -> requireNonOverlappingWidgets(client.gui.screen()));
            RemixWorldGameTest.captureCompletedFrame(context, "settings-fog");
            context.runOnClient(client -> press(client.gui.screen(), "rtxremix.settings.apply"));
            context.waitFor(client -> Math.abs(Double.parseDouble(read("rtx.clouds.coverage")) - 0.7) < 1e-5
                    && read("rtx.volumetrics.transmittanceColor").startsWith("0.8, 0.85,"), 100);
            var references = context.computeOnClient(client -> new com.nvidia.rtxremix.minecraft.asset.RemixMaterial[] {
                    BlockTextureManager.get().getWaterSurfaceMaterial(), BlockTextureManager.get().getWaterCameraMediumMaterial()});
            context.runOnClient(client -> {
                press(client.gui.screen(), "rtxremix.settings.back");
                press(client.gui.screen(), "rtxremix.settings.back");
                press(client.gui.screen(), "rtxremix.settings.category.materials");
                press(client.gui.screen(), "rtxremix.settings.group.water");
                editNumber("water.ior", "1.4");
                editNumber("water.red", "0.7");
                editNumber("water.absorptionDistance", "3.0");
            });
            context.waitTicks(2);
            RemixWorldGameTest.captureCompletedFrame(context, "settings-water");
            context.runOnClient(client -> {
                press(client.gui.screen(), "rtxremix.settings.apply");
                var textures = BlockTextureManager.get();
                require(references[0] != null && references[1] != null, "Water materials were not initialized");
                require(textures.getWaterSurfaceMaterial() == references[0] && textures.getWaterCameraMediumMaterial() == references[1],
                        "Water parameters replaced live material handles");
                require(Math.abs(textures.getWaterIor() - 1.4) < 1e-5 && textures.getWaterTransmittanceDistance() == 3,
                        "Water settings did not update the live material state");
                press(client.gui.screen(), "rtxremix.settings.back");
                press(client.gui.screen(), "rtxremix.settings.back");
                press(client.gui.screen(), "rtxremix.settings.category.atmosphere");
                press(client.gui.screen(), "rtxremix.settings.group.clouds");
                editNumber("clouds.coverage", "0.1");
                press(client.gui.screen(), "gui.cancel");
                require(Math.abs(floating("clouds.coverage").get() - 0.7) < 1e-5,
                        "Cancel changed the last applied cloud setting");
            });
        } finally {
            context.runOnClient(client -> {
                original.forEach((id, value) -> floating(id).apply(value));
                RtxRemixMod.saveRtxSettings();
                root.onClose();
                RtxSodiumConfig.openEffects(client.gui.screen());
            });
        }
    }

    private static void editNumber(String id, String value) {
        var client = net.minecraft.client.Minecraft.getInstance();
        var widget = findWidget(client.gui.screen(), Component.translatable(floating(id).labelKey()).getString());
        require(widget != null, "Missing shader option " + id);
        int remaining = client.gui.screen().children().size();
        while (!widget.visible && remaining-- > 0) {
            client.gui.screen().keyPressed(new KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_PAGEDOWN, 0, 0));
        }
        require(widget.visible, "Shader option cannot be reached by scrolling: " + id);
        widget.keyPressed(new KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN, 0, 0));
        var input = client.gui.screen().children().stream().filter(child -> child instanceof net.minecraft.client.gui.components.EditBox)
                .map(child -> (net.minecraft.client.gui.components.EditBox) child).findFirst().orElseThrow();
        input.setValue(value);
        press(client.gui.screen(), "gui.done");
    }

    private static void requireNonOverlappingWidgets(net.minecraft.client.gui.screens.Screen screen) {
        var visible = screen.children().stream().filter(child -> child instanceof AbstractWidget)
                .map(child -> (AbstractWidget) child).filter(widget -> widget.visible).toList();
        for (int i = 0; i < visible.size(); i++) {
            var a = visible.get(i);
            require(a.getX() >= 0 && a.getY() >= 0 && a.getRight() <= screen.width && a.getBottom() <= screen.height,
                    "Shader widget is outside the screen: " + a.getMessage().getString());
            for (int j = 0; j < i; j++) {
                var b = visible.get(j);
                require(a.getRight() <= b.getX() || b.getRight() <= a.getX()
                                || a.getBottom() <= b.getY() || b.getBottom() <= a.getY(),
                        "Overlapping shader widgets: " + a.getMessage().getString() + " / " + b.getMessage().getString());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static RtxSetting<Double> floating(String id) {
        return (RtxSetting<Double>) java.util.Objects.requireNonNull(RtxRemixMod.getRtxSettings().byId(id), id);
    }

    private static void press(ContainerEventHandler container, String labelKey) {
        AbstractWidget button = findWidget(container, Component.translatable(labelKey).getString());
        require(button != null, "Missing settings folder: " + labelKey);
        button.keyPressed(new KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN, 0, 0));
    }

    private static AbstractWidget findWidget(ContainerEventHandler container, String label) {
        for (var child : container.children()) {
            if (child instanceof AbstractWidget button && button.getMessage().getString().equals(label)) return button;
            if (child instanceof ContainerEventHandler nested) {
                AbstractWidget button = findWidget(nested, label);
                if (button != null) return button;
            }
        }
        return null;
    }

    private static void checkAdvancedPage(ClientGameTestContext context) {
        String original = context.computeOnClient(client -> read("rtx.pathMaxBounces"));
        int target = original.equals("4") ? 3 : 4;
        context.runOnClient(client -> {
            var mod = ConfigManager.CONFIG.getModOptions().stream()
                    .filter(value -> value.configId().equals("rtx-remix-renderer")).findFirst().orElseThrow();
            var page = (net.caffeinemc.mods.sodium.client.config.structure.OptionPage) mod.pages().get(1);
            client.gui.setScreen(VideoSettingsScreen.createScreen(client.gui.screen(), page));
            require(!RtxRemixMod.getRemixInputGate().isCaptureSessionOpen(), "Advanced page captured native-menu input");
            var option = (net.caffeinemc.mods.sodium.client.config.structure.IntegerOption)
                    ConfigManager.CONFIG.getOption(id("runtime.bounces"));
            option.modifyValue(target);
            require(read("rtx.pathMaxBounces").equals(original), "Advanced slider applied before Apply");
            ConfigManager.CONFIG.resetAllOptionsFromBindings();
            require(!option.hasChanged(), "Advanced Undo retained the changed value");
            option.modifyValue(target);
            ConfigManager.CONFIG.applyAllOptions();
        });
        context.waitFor(client -> read("rtx.pathMaxBounces").equals(Integer.toString(target)), 200);
        context.waitTicks(10);
        context.runOnClient(client -> {
            require(client.gui.screen() instanceof VideoSettingsScreen, "Advanced page left Sodium");
            require(!RtxRemixMod.getRemixInputGate().isCaptureSessionOpen(), "Advanced Apply captured native-menu input");
            require(read("rtx.showUI").equals("0"), "Advanced page opened a native menu");
            RtxVideoSettings.load();
        });
        context.waitFor(client -> read("rtx.pathMaxBounces").equals(Integer.toString(target)), 200);
        RemixWorldGameTest.captureCompletedFrame(context, "settings-advanced");
        context.runOnClient(client -> {
            var option = (net.caffeinemc.mods.sodium.client.config.structure.IntegerOption)
                    ConfigManager.CONFIG.getOption(id("runtime.bounces"));
            option.modifyValue(Integer.parseInt(original));
            ConfigManager.CONFIG.applyAllOptions();
        });
        context.waitFor(client -> read("rtx.pathMaxBounces").equals(original), 200);
    }

    @SuppressWarnings("unchecked")
    private static EnumOption<RtxSodiumConfig.Preset> preset() {
        return (EnumOption<RtxSodiumConfig.Preset>) ConfigManager.CONFIG.getOption(id("video.preset"));
    }

    @SuppressWarnings("unchecked")
    private static EnumOption<RtxSodiumConfig.Dlss> dlss() {
        return (EnumOption<RtxSodiumConfig.Dlss>) ConfigManager.CONFIG.getOption(id("video.dlss"));
    }

    private static net.caffeinemc.mods.sodium.client.config.structure.BooleanOption reconstruction() {
        return (net.caffeinemc.mods.sodium.client.config.structure.BooleanOption) ConfigManager.CONFIG.getOption(id("video.reconstruction"));
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("rtx-remix-renderer", path); }
    private static String read(String key) { return RtxRemixMod.renderer.getRemixApi().getConfigVariableResolved(key); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

