/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.gametest;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.edtp.kavora.QualityPreset;
import org.edtp.kavora.KavoraMod;
import org.edtp.kavora.settings.KavoraQualityPresets;
import org.edtp.kavora.settings.KavoraQualitySelection;
import org.edtp.kavora.settings.KavoraSodiumConfig;
import org.edtp.kavora.settings.KavoraSetting;
import org.edtp.kavora.settings.KavoraSettingsScreen;
import org.edtp.kavora.settings.KavoraSettingsStore;
import org.edtp.kavora.settings.KavoraVideoSettings;
import org.edtp.kavora.texture.BlockTextureManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.caffeinemc.mods.sodium.client.config.structure.BooleanOption;
import net.caffeinemc.mods.sodium.client.config.structure.EnumOption;
import net.caffeinemc.mods.sodium.client.config.structure.ExternalButtonOption;
import net.caffeinemc.mods.sodium.client.config.structure.ExternalPage;
import net.caffeinemc.mods.sodium.client.config.structure.OptionPage;
import net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Exercises the visual and Sodium settings transactions with native option readback. */
final class KavoraSettingsChecks {
    private static final String CLOUD_TRANSPORT = "rtx.clouds.transportMode";
    private static final String BOUNCES_ID = "lighting.bounces";
    private static final String[] QUALITY_SNAPSHOT_KEYS = {
        "rtx.graphicsPreset",
        "rtx.dlssPreset",
        "rtx.upscalerType",
        "rtx.qualityDLSS",
        "rtx.enableRayReconstruction",
        "rtx.dlfg.enable",
        "rtx.dlfg.maxInterpolatedFrames",
        "rtx.dlfg.litePreset",
        "rtx.dlfg.enablePresentMetering",
        "rtx.integrateIndirectMode",
        "rtx.useRTXDI",
        "rtx.pathMinBounces",
        "rtx.pathMaxBounces",
        "rtx.enablePSRR",
        "rtx.enablePSTR",
        "rtx.psrrMaxBounces",
        "rtx.pstrMaxBounces",
        "rtx.displacement.mode",
        "rtx.displacement.enableDirectLighting",
        "rtx.displacement.enableIndirectLighting",
        "rtx.displacement.enableNEECache",
        "rtx.displacement.enableReSTIRGI",
        "rtx.displacement.enableIndirectHit",
        "rtx.displacement.enablePSR",
        "rtx.displacement.displacementFactor",
        "rtx.displacement.displacementInFactor",
        "rtx.displacement.displacementOutFactor",
        "rtx.displacement.maxIterations",
        "rtx.spatiallyHashedRadianceCache.qualityPreset",
        "rtx.spatiallyHashedRadianceCache.queryForThinOpaquePrimaryView",
        "rtx.spatiallyHashedRadianceCache.primaryQuerySpatialJitter",
        "rtx.spatiallyHashedRadianceCache.primaryQueryLodJitter",
        "rtx.spatiallyHashedRadianceCache.primaryQuerySpatialJitterRadius",
        "rtx.spatiallyHashedRadianceCache.primaryQueryLodJitterWidth",
        "rtx.spatiallyHashedRadianceCache.cellFilterMode",
        "rtx.spatiallyHashedRadianceCache.cellFilterSpatialStrength",
        "rtx.spatiallyHashedRadianceCache.cellFilterFeedback",
        "rtx.spatiallyHashedRadianceCache.cellFilterNormalCosine",
        "rtx.spatiallyHashedRadianceCache.enableUpdate",
        "rtx.spatiallyHashedRadianceCache.enableResolve",
        "rtx.spatiallyHashedRadianceCache.combineUpdateAndQuery",
        "rtx.spatiallyHashedRadianceCache.downscaleFactor",
        "rtx.spatiallyHashedRadianceCache.targetNumUpdateRecords",
        "rtx.spatiallyHashedRadianceCache.excludePrimaryVerticesFromTarget",
        "rtx.spatiallyHashedRadianceCache.averageUpdateRecordsPerPath",
        "rtx.spatiallyHashedRadianceCache.numFramesToSmoothOutUpdateDimensions",
        "rtx.spatiallyHashedRadianceCache.voxelSize",
        "rtx.spatiallyHashedRadianceCache.maxNumFramesToAccumulate",
        "rtx.spatiallyHashedRadianceCache.staleFrameNumber",
        "rtx.spatiallyHashedRadianceCache.perceptualRoughnessThreshold",
        "rtx.spatiallyHashedRadianceCache.fireflyClampMultiplier",
        "rtx.spatiallyHashedRadianceCache.jitterSequenceLength",
        "rtx.sparseRendering.enableSparseRendering",
        "rtx.sparseRendering.enableAdaptiveSamplingRate",
        "rtx.sparseRendering.quality",
        "rtx.sparseRendering.samplingRate",
        "rtx.sparseRendering.samplingRateResolutionExponent",
        "rtx.sparseRendering.enableSparseAwareAlphaBlendSearch",
        "rtx.volumetrics.froxelGridResolutionScale",
        "rtx.volumetrics.froxelDepthSlices",
        "rtx.volumetrics.froxelDepthSliceDistributionExponent",
        "rtx.volumetrics.froxelMaxDistanceMeters",
        "rtx.volumetrics.maxAccumulationFrames",
        "rtx.volumetrics.restirGridGuardBandFactor",
        "rtx.volumetrics.restirGridScale",
        "rtx.volumetrics.restirFroxelDepthSlices",
        "rtx.volumetrics.initialRISSampleCount",
        "rtx.volumetrics.noiseFieldOctaves",
        "rtx.clouds.steps",
        "rtx.clouds.lightSteps",
    };

    private KavoraSettingsChecks() {}

    static void run(ClientGameTestContext context) {
        if (Boolean.getBoolean("kavora.gametest.qualitySnapshotOnly")) {
            writeQualitySnapshot(context);
            return;
        }
        boolean originalReconstruction = context.computeOnClient(client -> ConfigManager.CONFIG.readBooleanOption(id("video.reconstruction")));
        var originalDlss = context.computeOnClient(client -> ConfigManager.CONFIG.readEnumOption(
                id("video.dlss"), KavoraSodiumConfig.Dlss.class));
        context.runOnClient(client -> {
            var mod = ConfigManager.CONFIG.getModOptions().stream()
                    .filter(value -> value.configId().equals("kavora")).findFirst().orElseThrow();
            require(mod.pages().size() == 4 && mod.pages().contains(neuralRenderingPage()),
                    "Expected rendering, performance, DLSS 5 and visual-settings pages inside Kavora");
            require(mod.pages().getLast() instanceof ExternalPage,
                    "Visual settings were embedded into the Sodium performance pages");
            require(ConfigManager.CONFIG.getOption(id("video.preset")) == null
                    && ConfigManager.CONFIG.getOption(id("runtime.bounces")) == null,
                    "Visual quality controls remained registered in Sodium");
            KavoraSodiumConfig.open(client.gui.screen());
            require(client.gui.screen() instanceof VideoSettingsScreen, "RTX menu did not open Sodium settings");
            require(dlss().isEnabled() && reconstruction().isEnabled(), "Display reconstruction controls are unavailable");
        });
        var sodiumScreen = context.computeOnClient(client -> client.gui.screen());
        if (Boolean.getBoolean("kavora.gametest.neuralRenderingOnly")) {
            checkNeuralRendering(context, (VideoSettingsScreen) sodiumScreen);
            return;
        }
        context.waitTicks(10);
        KavoraWorldGameTest.captureCompletedFrame(context, "settings");
        context.runOnClient(client -> {
            KavoraSodiumConfig.openEffects(client.gui.screen());
            require(client.gui.screen() instanceof KavoraSettingsScreen,
                    "Visual settings did not open their independent category screen");
        });
        context.waitTicks(3);
        context.runOnClient(client -> requireNonOverlappingWidgets(client.gui.screen()));
        KavoraWorldGameTest.captureCompletedFrame(context, "settings-effects");
        checkEffects(context);
        checkFolderNavigation(context, sodiumScreen);
        checkQualityPresets(context, sodiumScreen);
        try {
            context.runOnClient(client -> {
                dlss().modifyValue(KavoraSodiumConfig.Dlss.DLAA);
                ConfigManager.CONFIG.applyAllOptions();
            });
            context.waitFor(client -> read(KavoraVideoSettings.UPSCALER).equals("1")
                    && read(KavoraVideoSettings.DLSS).equals("5"), 200);
            context.runOnClient(client -> {
                reconstruction().modifyValue(false);
                ConfigManager.CONFIG.applyAllOptions();
            });
            context.waitFor(client -> read(KavoraVideoSettings.UPSCALER).equals("1")
                    && read(KavoraVideoSettings.RECONSTRUCTION).equalsIgnoreCase("false"), 200);
            for (var mode : KavoraSodiumConfig.Dlss.values()) {
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
                context.waitFor(client -> read(KavoraVideoSettings.DLSS).equals(expected), 200);
            }
            context.runOnClient(client -> {
                reconstruction().modifyValue(true);
                ConfigManager.CONFIG.applyAllOptions();
            });
            context.waitFor(client -> read(KavoraVideoSettings.RECONSTRUCTION).equalsIgnoreCase("true"), 200);
            String before = context.computeOnClient(client -> read(KavoraVideoSettings.DLSS));
            var target = before.equals("2") ? KavoraSodiumConfig.Dlss.QUALITY : KavoraSodiumConfig.Dlss.BALANCED;
            String targetNative = target == KavoraSodiumConfig.Dlss.QUALITY ? "3" : "2";
            context.runOnClient(client -> {
                dlss().modifyValue(target);
                require(read(KavoraVideoSettings.DLSS).equals(before), "Editing a control applied before Apply");
                ConfigManager.CONFIG.resetAllOptionsFromBindings();
                require(!dlss().hasChanged(), "Undo retained the modified DLSS value");
                dlss().modifyValue(target);
                ConfigManager.CONFIG.applyAllOptions();
            });
            context.waitFor(client -> read(KavoraVideoSettings.DLSS).equals(targetNative), 200);
            context.runOnClient(client -> {
                var file = client.gameDirectory.toPath().resolve("config/kavora-video.json");
                try {
                    var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                    require(json.get(KavoraVideoSettings.DLSS).getAsString().equals(targetNative), "Video setting was not persisted");
                } catch (IOException e) {
                    throw new AssertionError("Cannot read saved video settings", e);
                }
                KavoraVideoSettings.load();
            });
            context.waitTicks(5);
            context.runOnClient(client -> require(read(KavoraVideoSettings.DLSS).equals(targetNative),
                    "Reload changed the saved DLSS setting"));
        } finally {
            context.runOnClient(client -> {
                reconstruction().modifyValue(originalReconstruction);
                dlss().modifyValue(originalDlss);
                ConfigManager.CONFIG.applyAllOptions();
                client.gui.setScreen(null);
            });
            context.waitTicks(5);
        }
    }

    private static OptionPage neuralRenderingPage() {
        String label = Component.translatable("kavora.video.neuralRendering.title").getString();
        return ConfigManager.CONFIG.getModOptions().stream().filter(mod -> mod.configId().equals("kavora"))
                .flatMap(mod -> mod.pages().stream()).filter(OptionPage.class::isInstance).map(OptionPage.class::cast)
                .filter(page -> page.name().getString().equals(label)).findFirst().orElseThrow();
    }

    private static void checkNeuralRendering(ClientGameTestContext context, VideoSettingsScreen sodium) {
        String enableKey = "rtx.dlssNeuralRendering.enable";
        String modelKey = "rtx.dlssNeuralRendering.model";
        String intensityKey = "rtx.dlssNeuralRendering.intensity";
        Path file = FabricLoader.getInstance().getConfigDir().resolve("kavora-video.json");
        byte[] originalFile = readSettingsFile(file);
        var toggle = (BooleanOption) ConfigManager.CONFIG.getOption(id("video.neural_rendering"));
        var details = (ExternalButtonOption) ConfigManager.CONFIG.getOption(id("video.neural_rendering_settings"));
        var page = neuralRenderingPage();
        require(page.groups().stream().flatMap(group -> group.options().stream()).toList().equals(List.of(toggle, details)),
                "DLSS 5 page must contain just the toggle and the settings entry");
        context.waitFor(client -> KavoraMod.renderer.getRemixApi().getDlssNeuralRenderingStatus() >= 0, 200);
        int support = context.computeOnClient(client -> {
            sodium.jumpToPage(page);
            return KavoraMod.renderer.getRemixApi().getDlssNeuralRenderingStatus();
        });
        context.waitTicks(10);
        KavoraWorldGameTest.captureCompletedFrame(context, "settings-dlss5-tab");
        context.runOnClient(client -> require(toggle.isEnabled() == (support == 1) && details.isEnabled() == (support == 1),
                "DLSS 5 controls did not match NGX availability: " + support));
        if (support != 1) {
            context.runOnClient(client -> {
                details.getCurrentScreenConsumer().accept(sodium);
                require(client.gui.screen() == sodium, "Unavailable DLSS 5 settings opened");
                org.slf4j.LoggerFactory.getLogger(KavoraSettingsChecks.class).info("DLSS 5 unavailable on this test device");
            });
            return;
        }
        String originalEnable = context.computeOnClient(client -> read(enableKey));
        int originalModel = context.computeOnClient(client -> Integer.parseInt(read(modelKey)));
        String originalIntensity = context.computeOnClient(client -> read(intensityKey));
        String expectedIntensity = Float.parseFloat(originalIntensity) > 0.5f ? "0.35" : "0.85";
        var originalQuality = context.computeOnClient(client -> KavoraMod.getRtxSettings().quality());
        String expectedModel = Integer.toString((originalModel + 1) % 3);
        try {
            context.runOnClient(client -> {
                details.getCurrentScreenConsumer().accept(sodium);
                require(client.gui.screen() instanceof KavoraSettingsScreen, "DLSS 5 did not open the Iris-style settings");
                require(findWidget(client.gui.screen(), Component.translatable("kavora.settings.effects").getString()) == null,
                        "DLSS 5 opened the general visual settings");
                press(client.gui.screen(), "kavora.video.neuralRendering.model");
                press(client.gui.screen(), "gui.cancel");
                require(client.gui.screen() == sodium && read(modelKey).equals(Integer.toString(originalModel)),
                        "Cancel committed the DLSS 5 model or lost the parent tab");
                details.getCurrentScreenConsumer().accept(sodium);
                press(client.gui.screen(), "kavora.video.neuralRendering.model");
                var intensity = findWidget(client.gui.screen(),
                        Component.translatable("kavora.video.neuralRendering.intensity").getString());
                require(intensity != null && intensity.visible, "Missing DLSS 5 strength slider");
                editNumber(intensity, "neuralRendering.intensity", expectedIntensity);
                press(client.gui.screen(), "kavora.settings.apply");
            });
            context.waitFor(client -> read(modelKey).equals(expectedModel)
                    && Float.parseFloat(read(intensityKey)) == Float.parseFloat(expectedIntensity), 200);
            KavoraWorldGameTest.captureCompletedFrame(context, "settings-dlss5-controls");
            context.runOnClient(client -> {
                require(KavoraMod.getRtxSettings().quality().equals(originalQuality), "DLSS 5 changed the quality preset");
                press(client.gui.screen(), "gui.done");
                toggle.modifyValue(true);
                ConfigManager.CONFIG.applyAllOptions();
                client.gui.setScreen(null);
            });
            context.waitFor(client -> Boolean.parseBoolean(read(enableKey)), 200);
            long presented = context.computeOnClient(client -> KavoraFrameProbe.presents);
            context.waitFor(client -> KavoraFrameProbe.presents >= presented + 16, 400);
            context.runOnClient(client -> {
                require(Boolean.parseBoolean(read(enableKey)), "DLSS 5 disabled itself after activation");
                JsonObject saved = JsonParser.parseString(new String(readSettingsFile(file), java.nio.charset.StandardCharsets.UTF_8))
                        .getAsJsonObject();
                require(saved.get(enableKey).getAsBoolean() && saved.get(modelKey).getAsString().equals(expectedModel)
                                && saved.get(intensityKey).getAsString().equals(expectedIntensity),
                        "DLSS 5 preferences were not persisted");
            });
            KavoraWorldGameTest.captureCompletedFrame(context, "dlss5-enabled");
        } finally {
            context.runOnClient(client -> {
                restoreSettingsFile(file, originalFile);
                KavoraVideoSettings.load();
                var api = KavoraMod.renderer.getRemixApi();
                api.setConfigVariable(enableKey, originalEnable);
                api.setConfigVariable(modelKey, Integer.toString(originalModel));
                api.setConfigVariable(intensityKey, originalIntensity);
                client.gui.setScreen(null);
            });
        }
        context.waitFor(client -> read(enableKey).equalsIgnoreCase(originalEnable), 200);
    }

    private static void writeQualitySnapshot(ClientGameTestContext context) {
        context.runOnClient(client -> {
            var gson = new GsonBuilder().setPrettyPrinting().create();
            var runtime = new JsonObject();
            for (String key : QUALITY_SNAPSHOT_KEYS) {
                try {
                    runtime.addProperty(key, read(key));
                } catch (RuntimeException e) {
                    throw new AssertionError("Cannot read resolved quality option " + key, e);
                }
            }
            var settings = new JsonObject();
            for (var setting : KavoraMod.getRtxSettings().all()) {
                try {
                    settings.add(setting.id(), gson.toJsonTree(setting.get()));
                } catch (RuntimeException e) {
                    throw new AssertionError("Cannot read RTX setting " + setting.id(), e);
                }
            }
            var snapshot = new JsonObject();
            snapshot.add("runtime", runtime);
            snapshot.add("settings", settings);
            var file = client.gameDirectory.toPath().resolve("quality-settings-snapshot.json");
            try {
                Files.writeString(file, gson.toJson(snapshot) + "\n");
            } catch (IOException e) {
                throw new AssertionError("Cannot write quality settings snapshot to " + file, e);
            }
        });
    }

    private static void checkEffects(ClientGameTestContext context) {
        var root = context.computeOnClient(client -> (KavoraSettingsScreen) client.gui.screen());
        var originalQuality = context.computeOnClient(client -> KavoraMod.getRtxSettings().quality());
        Map<String, Double> original = context.computeOnClient(client -> {
            var values = new LinkedHashMap<String, Double>();
            for (String id : new String[] {"clouds.coverage", "fog.transmittance.red", "fog.transmittance.green",
                    "water.ior", "water.red", "water.absorptionDistance"}) values.put(id, floating(id).get());
            return values;
        });
        try {
            context.runOnClient(client -> press(client.gui.screen(), "kavora.settings.category.weather"));
            context.waitTicks(2);
            KavoraWorldGameTest.captureCompletedFrame(context, "settings-atmosphere");
            context.runOnClient(client -> {
                press(client.gui.screen(), "kavora.settings.group.clouds");
                editNumber("clouds.coverage", "0.7");
                require(floating("clouds.coverage").get().equals(original.get("clouds.coverage")),
                        "Shader control applied before Apply");
            });
            context.waitTicks(2);
            context.runOnClient(client -> requireNonOverlappingWidgets(client.gui.screen()));
            KavoraWorldGameTest.captureCompletedFrame(context, "settings-clouds");
            context.runOnClient(client -> {
                press(client.gui.screen(), "kavora.settings.back");
                press(client.gui.screen(), "kavora.settings.group.fog");
                editNumber("fog.transmittance.red", "0.8");
                editNumber("fog.transmittance.green", "0.85");
            });
            context.waitTicks(2);
            context.runOnClient(client -> requireNonOverlappingWidgets(client.gui.screen()));
            KavoraWorldGameTest.captureCompletedFrame(context, "settings-fog");
            context.runOnClient(client -> press(client.gui.screen(), "kavora.settings.apply"));
            context.waitFor(client -> Math.abs(Double.parseDouble(read("rtx.clouds.coverage")) - 0.7) < 1e-5
                    && read("rtx.volumetrics.transmittanceColor").startsWith("0.8, 0.85,"), 100);
            var references = context.computeOnClient(client -> new org.edtp.kavora.asset.KavoraMaterial[] {
                    BlockTextureManager.get().getWaterSurfaceMaterial(), BlockTextureManager.get().getWaterCameraMediumMaterial()});
            context.runOnClient(client -> {
                press(client.gui.screen(), "kavora.settings.back");
                press(client.gui.screen(), "kavora.settings.back");
                press(client.gui.screen(), "kavora.settings.category.materials");
                press(client.gui.screen(), "kavora.settings.group.water");
                editNumber("water.ior", "1.4");
                editNumber("water.red", "0.7");
                editNumber("water.absorptionDistance", "3.0");
            });
            context.waitTicks(2);
            KavoraWorldGameTest.captureCompletedFrame(context, "settings-water");
            context.runOnClient(client -> {
                press(client.gui.screen(), "kavora.settings.apply");
                var textures = BlockTextureManager.get();
                require(references[0] != null && references[1] != null, "Water materials were not initialized");
                require(textures.getWaterSurfaceMaterial() == references[0] && textures.getWaterCameraMediumMaterial() == references[1],
                        "Water parameters replaced live material handles");
                require(Math.abs(textures.getWaterIor() - 1.4) < 1e-5 && textures.getWaterTransmittanceDistance() == 3,
                        "Water settings did not update the live material state");
                press(client.gui.screen(), "kavora.settings.back");
                press(client.gui.screen(), "kavora.settings.back");
                press(client.gui.screen(), "kavora.settings.category.weather");
                press(client.gui.screen(), "kavora.settings.group.clouds");
                editNumber("clouds.coverage", "0.1");
                press(client.gui.screen(), "gui.cancel");
                require(Math.abs(floating("clouds.coverage").get() - 0.7) < 1e-5,
                        "Cancel changed the last applied cloud setting");
            });
        } finally {
            context.runOnClient(client -> {
                original.forEach((id, value) -> floating(id).apply(value));
                KavoraMod.getRtxSettings().setQuality(originalQuality);
                KavoraMod.saveRtxSettings();
                root.onClose();
                KavoraSodiumConfig.openEffects(client.gui.screen());
            });
        }
    }

    private static void editNumber(String id, String value) {
        var screen = net.minecraft.client.Minecraft.getInstance().gui.screen();
        editNumber(revealOption(screen, id), id, value);
    }

    private static void editNumber(AbstractWidget widget, String id, String value) {
        var client = net.minecraft.client.Minecraft.getInstance();
        widget.keyPressed(new KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN, 0, 0));
        var input = client.gui.screen().children().stream().filter(child -> child instanceof net.minecraft.client.gui.components.EditBox)
                .map(child -> (net.minecraft.client.gui.components.EditBox) child).findFirst()
                .orElseThrow(() -> new AssertionError("Numeric entry did not open for " + id));
        input.setValue(value);
        press(client.gui.screen(), "gui.done");
    }

    private static AbstractWidget revealOption(Screen screen, String id) {
        var widget = findWidget(screen, Component.translatable(setting(id).labelKey()).getString());
        require(widget != null, "Missing shader option " + id);
        int remaining = screen.children().size();
        while (!widget.visible && remaining-- > 0) {
            screen.keyPressed(new KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_PAGEDOWN, 0, 0));
        }
        require(widget.visible, "Shader option cannot be reached by scrolling: " + id);
        return widget;
    }

    private static void checkFolderNavigation(ClientGameTestContext context, Screen parent) {
        Map<String, List<String>> routes = new LinkedHashMap<>();
        routes.put("cloud-quality", List.of("weather", "clouds", "cloudQuality"));
        routes.put("fog-quality", List.of("weather", "fog", "fogQuality"));
        routes.put("parallax", List.of("materials", "materials", "parallax"));
        routes.put("radiance-cache", List.of("rayTracing", "pathTracing", "radianceCache"));
        for (var route : routes.entrySet()) {
            context.runOnClient(client -> {
                KavoraSodiumConfig.openEffects(parent);
                var path = route.getValue();
                press(client.gui.screen(), "kavora.settings.category." + path.getFirst());
                for (String folder : path.subList(1, path.size())) {
                    press(client.gui.screen(), "kavora.settings.group." + folder);
                }
                requireNonOverlappingWidgets(client.gui.screen());
            });
            context.waitTick();
            KavoraWorldGameTest.captureCompletedFrame(context, "settings-" + route.getKey());
        }
        context.runOnClient(client -> KavoraSodiumConfig.openEffects(parent));
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
    private static KavoraSetting<Double> floating(String id) {
        return (KavoraSetting<Double>) setting(id);
    }

    private static KavoraSetting<?> setting(String id) {
        return Objects.requireNonNull(KavoraMod.getRtxSettings().byId(id), id);
    }

    static void press(ContainerEventHandler container, String labelKey) {
        press(container, Component.translatable(labelKey));
    }

    private static void press(ContainerEventHandler container, Component label) {
        AbstractWidget button = findWidget(container, label.getString());
        require(button != null, "Missing settings control " + label.getString());
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

    private static void checkQualityPresets(ClientGameTestContext context, Screen sodiumScreen) {
        var settings = context.computeOnClient(client -> KavoraMod.getRtxSettings());
        var originalQuality = context.computeOnClient(client -> settings.quality());
        var originalDisplay = context.computeOnClient(client -> displaySettings());
        var restoreValues = context.computeOnClient(client -> KavoraQualityPresets.values(QualityPreset.ULTRA).keySet()
                .stream().map(id -> restoreSetting(setting(id))).toList());
        Path file = FabricLoader.getInstance().getConfigDir().resolve("kavora.json");
        byte[] originalFile = readSettingsFile(file);
        var ultra = new KavoraQualitySelection(QualityPreset.ULTRA, false);
        var high = new KavoraQualitySelection(QualityPreset.HIGH, false);
        var customHigh = new KavoraQualitySelection(QualityPreset.HIGH, true);
        int highBounces = (Integer) KavoraQualityPresets.values(QualityPreset.HIGH).get(BOUNCES_ID);
        int ultraBounces = (Integer) KavoraQualityPresets.values(QualityPreset.ULTRA).get(BOUNCES_ID);
        int editedBounces = java.util.stream.IntStream.of(3, 4, 5)
                .filter(value -> value != highBounces && value != ultraBounces).findFirst().orElseThrow();
        try {
            context.runOnClient(client -> {
                KavoraQualityPresets.apply(settings, QualityPreset.ULTRA);
                KavoraSodiumConfig.openEffects(sodiumScreen);
            });
            context.waitFor(client -> read(CLOUD_TRANSPORT).equals("0"), 100);
            context.runOnClient(client -> {
                selectQuality(client.gui.screen(), ultra, QualityPreset.HIGH);
                requireQualityLabel(client.gui.screen(), high);
            });
            context.waitTicks(3);
            context.runOnClient(client -> {
                require(read(CLOUD_TRANSPORT).equals("0") && settings.quality().equals(ultra),
                        "Selecting High changed the committed settings before Apply");
                press(client.gui.screen(), "gui.cancel");
                require(client.gui.screen() == sodiumScreen, "Cancel did not return to Sodium");
                KavoraSodiumConfig.openEffects(sodiumScreen);
                requireQualityLabel(client.gui.screen(), ultra);
                require(read(CLOUD_TRANSPORT).equals("0"), "Cancel changed native cloud transport");
                selectQuality(client.gui.screen(), ultra, QualityPreset.HIGH);
                press(client.gui.screen(), "kavora.settings.apply");
            });
            context.waitFor(client -> read(CLOUD_TRANSPORT).equals("1"), 100);
            context.runOnClient(client -> {
                require(settings.quality().equals(high), "Apply did not commit High");
                require(displaySettings().equals(originalDisplay), "High changed display reconstruction or frame generation");
                press(client.gui.screen(), "kavora.settings.category.rayTracing");
                press(client.gui.screen(), "kavora.settings.group.pathTracing");
                editNumber(BOUNCES_ID, Integer.toString(editedBounces));
                press(client.gui.screen(), "kavora.settings.back");
                press(client.gui.screen(), "kavora.settings.back");
                requireQualityLabel(client.gui.screen(), customHigh);
                press(client.gui.screen(), "kavora.settings.apply");
            });
            context.waitFor(client -> read("rtx.pathMaxBounces").equals(Integer.toString(editedBounces)), 100);
            context.runOnClient(client -> {
                press(client.gui.screen(), "kavora.settings.category.weather");
                press(client.gui.screen(), "kavora.settings.group.fog");
                press(client.gui.screen(), "kavora.settings.group.fogQuality");
                var integration = revealOption(client.gui.screen(), "fog.integration");
                require(integration.keyPressed(new KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN, 0, 0)),
                        "Fog integration mode did not accept the cycle input");
                editNumber("fog.integrationResolution", "7");
                press(client.gui.screen(), "kavora.settings.apply");
            });
            context.waitFor(client -> read("rtx.volumetrics.fogIntegrationMode").equals("0")
                    && read("rtx.volumetrics.fogIntegrationPixelStride").equals("7"), 100);
            context.runOnClient(client -> {
                require(settings.quality().equals(customHigh), "Editing a High value lost its preset source");
                require(read(CLOUD_TRANSPORT).equals("1") && read("rtx.displacement.mode").equals("2")
                        && displaySettings().equals(originalDisplay), "An individual fog edit changed other quality mechanisms");
                KavoraQualityPresets.apply(settings, QualityPreset.ULTRA);
                require(settings.quality().equals(ultra) && setting(BOUNCES_ID).get().equals(ultraBounces),
                        "Could not clear the live custom settings before reloading");
                new KavoraSettingsStore(file).load(settings);
                require(settings.quality().equals(customHigh) && setting(BOUNCES_ID).get().equals(editedBounces),
                        "Reload did not restore the custom High source and edited bounces");
                KavoraSodiumConfig.openEffects(sodiumScreen);
                requireQualityLabel(client.gui.screen(), customHigh);
                selectQuality(client.gui.screen(), customHigh, QualityPreset.ULTRA);
                press(client.gui.screen(), "kavora.settings.apply");
            });
            context.waitFor(client -> read(CLOUD_TRANSPORT).equals("0"), 100);
            context.runOnClient(client -> {
                require(settings.quality().equals(ultra), "Ultra did not clear the custom label");
                require(displaySettings().equals(originalDisplay), "Ultra changed display reconstruction or frame generation");
            });
            // A developer-menu preset must not mask a later shader-menu transaction.
            context.runOnClient(client -> KavoraMod.renderer.getRemixApi().setConfigVariable("rtx.graphicsPreset", "0"));
            context.waitFor(client -> read("rtx.pathMinBounces").equals("1")
                    && read("rtx.pathMaxBounces").equals("4"), 100);
            context.runOnClient(client -> KavoraQualityPresets.apply(settings, QualityPreset.HIGH));
            context.waitFor(client -> read("rtx.graphicsPreset").equals("4")
                    && read("rtx.pathMinBounces").equals("0")
                    && read("rtx.pathMaxBounces").equals(Integer.toString(highBounces)), 100);
            context.runOnClient(client -> require(displaySettings().equals(originalDisplay),
                    "Reclaiming visual settings changed display reconstruction or frame generation"));
        } finally {
            context.runOnClient(client -> {
                restoreValues.forEach(Runnable::run);
                settings.setQuality(originalQuality);
                restoreSettingsFile(file, originalFile);
                client.gui.setScreen(sodiumScreen);
            });
        }
    }

    private static Map<String, String> displaySettings() {
        var values = new LinkedHashMap<String, String>();
        for (String key : new String[] { KavoraVideoSettings.UPSCALER, "rtx.dlssPreset", KavoraVideoSettings.DLSS,
                KavoraVideoSettings.RECONSTRUCTION, "rtx.dlfg.enable", "rtx.dlfg.maxInterpolatedFrames", "rtx.dlfg.litePreset",
                "rtx.dlfg.enablePresentMetering" }) {
            values.put(key, read(key));
        }
        return values;
    }

    private static void selectQuality(Screen screen, KavoraQualitySelection current, QualityPreset next) {
        press(screen, qualityLabel(current));
        press(screen, "kavora.quality." + next.id());
    }

    private static void requireQualityLabel(Screen screen, KavoraQualitySelection quality) {
        Component label = qualityLabel(quality);
        require(findWidget(screen, label.getString()) != null, "Missing quality selection " + label.getString());
    }

    private static Component qualityLabel(KavoraQualitySelection quality) {
        Component name = Component.translatable("kavora.quality." + quality.base().id());
        if (quality.customized()) name = Component.translatable("kavora.quality.custom", name);
        return Component.translatable("kavora.quality.selected", name);
    }

    private static <T> Runnable restoreSetting(KavoraSetting<T> setting) {
        T original = setting.get();
        return () -> {
            if (!Objects.equals(setting.get(), original)) setting.apply(original);
        };
    }

    private static byte[] readSettingsFile(Path file) {
        try {
            return Files.exists(file) ? Files.readAllBytes(file) : null;
        } catch (IOException e) {
            throw new AssertionError("Cannot read visual settings " + file, e);
        }
    }

    private static void restoreSettingsFile(Path file, byte[] contents) {
        try {
            if (contents == null) Files.deleteIfExists(file);
            else Files.write(file, contents);
        } catch (IOException e) {
            throw new AssertionError("Cannot restore visual settings " + file, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static EnumOption<KavoraSodiumConfig.Dlss> dlss() {
        return (EnumOption<KavoraSodiumConfig.Dlss>) ConfigManager.CONFIG.getOption(id("video.dlss"));
    }

    private static net.caffeinemc.mods.sodium.client.config.structure.BooleanOption reconstruction() {
        return (net.caffeinemc.mods.sodium.client.config.structure.BooleanOption) ConfigManager.CONFIG.getOption(id("video.reconstruction"));
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("kavora", path); }
    private static String read(String key) { return KavoraMod.renderer.getRemixApi().getConfigVariableResolved(key); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

