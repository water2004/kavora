/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.settings;

import org.edtp.kavora.sdk.RemixAPI;
import org.edtp.kavora.sdk.RemixException;
import org.edtp.kavora.light.BlockLightIntensityControls;
import org.edtp.kavora.texture.BlockTextureManager;
import org.edtp.kavora.texture.AutoPbr;
import org.edtp.kavora.terrain.ChunkManager;
import org.edtp.kavora.light.LightManager;
import org.edtp.kavora.KavoraRenderer;
import org.edtp.kavora.KavoraMod;
import org.edtp.kavora.sky.SkyRenderer;
import org.edtp.kavora.UnfocusedFramerateLimit;
import org.edtp.kavora.entity.KavoraEntityPass;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Constructs the curated KavoraSettings bound to a live, initialized KavoraRenderer. */
public final class KavoraSettingsFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(KavoraSettingsFactory.class);

    /**
     * Minecraft's maximum render distance, and therefore the point past which a
     * cull can no longer remove anything.
     */
    private static final int MAX_DISTANCE_CHUNKS = 32;

    private KavoraSettingsFactory() {}

    /**
     * Builds the curated registry.
     *
     * <p>A new setting is one entry here, bound to an authoritative live getter
     * and setter, plus the {@code kavora.setting.} label and {@code .tip}
     * keys for its id in the language file. It then appears in
     * the menu, persists, and resets without further wiring. A setting needs
     * both a live model and a defined authored baseline. A boolean Remix
     * option gets both from {@link #remixBool}.
     *
     * <p><b>A declared range must contain the live default.</b> Defaults are
     * captured from the live getters as this method runs. A default outside its
     * own {@code [min, max]} is rejected by the store's range guard when the
     * saved file is read back, so the setting silently loses its persisted
     * value. Sun and moon intensity are normalized to {@code 1.0}; the other
     * artist-authored controls expose their native values, so check the runtime
     * model as well as the range when adding one.
     *
     * <p><b>The label must fit its widget.</b> Option rows are 150 px vanilla
     * widgets that draw {@code Label: Value} with a 2 px margin, and wider text
     * scrolls as a marquee that clips the label. {@code SettingsRowWidthTest}
     * measures every label at its registered widest value with the real
     * default-font advances, so a new setting also needs an entry there.
     */
    public static KavoraSettings build(KavoraRenderer renderer) {
        List<KavoraSetting<?>> list = new ArrayList<>();

        ChunkManager chunks = renderer.getChunkManager();
        KavoraEntityPass entities = renderer.getEntityPass();
        PostProcessingSettings postProcessing = renderer.getPostProcessingSettings();
        var environment = renderer.getConfLayerDriver();
        list.add(KavoraSetting.ofBool("fog.automatic", SettingGroup.GROUND_MIST, "kavora.setting.fog.automatic",
                "kavora.setting.fog.automatic.tip", true, environment::isAutomaticMist, environment::setAutomaticMist));
        list.add(floatSetting("fog.automaticStrength", SettingGroup.GROUND_MIST, "kavora.setting.fog.automaticStrength", 0, 2,
                environment::getMistStrength, environment::setMistStrength));

        // --- Render distances (live-bound to managers) ---
        list.add(KavoraSetting.ofBool("dist.autoFade", SettingGroup.RENDER_DISTANCE,
                "kavora.setting.dist.autoFade", "kavora.setting.dist.autoFade.tip",
                renderer.isViewDistanceFadeAuto(), renderer::isViewDistanceFadeAuto,
                renderer::setViewDistanceFadeAuto));

        // Both ranges are 0 to 32 chunks and both zeros mean Auto, Minecraft's
        // render distance. 32 is Minecraft's maximum render distance and
        // therefore the point past which a cull stops culling anything, so one
        // ceiling fits every distance and they share a single sentinel position.
        list.add(KavoraSetting.ofInt("dist.light", SettingGroup.RENDER_DISTANCE,
                "kavora.setting.dist.light", "kavora.setting.dist.light.tip",
                0, MAX_DISTANCE_CHUNKS, chunks.getLightCullDistance(),
                chunks::getLightCullDistance, chunks::setLightCullDistance));

        list.add(KavoraSetting.ofInt("dist.entity", SettingGroup.RENDER_DISTANCE,
                "kavora.setting.dist.entity", "kavora.setting.dist.entity.tip",
                0, MAX_DISTANCE_CHUNKS, entities.getEntityCullDistance(),
                entities::getEntityCullDistance, entities::setEntityCullDistance));

        list.add(KavoraSetting.ofInt("light.reach", SettingGroup.RENDER_DISTANCE,
                "kavora.setting.light.reach", "kavora.setting.light.reach.tip",
                ChunkManager.MIN_LIGHT_REACH_BLOCKS, ChunkManager.MAX_LIGHT_REACH_BLOCKS,
                chunks.getLightReach(), chunks::getLightReach, chunks::setLightReach));

        // --- Framerate ---
        list.add(unfocusedFramerate());

        // --- Sky & time of day ---
        list.add(KavoraSetting.ofBool("sky.enabled", SettingGroup.SKY,
                "kavora.setting.sky.enabled", "kavora.setting.sky.enabled.tip",
                KavoraMod.isSkyEnabled(), KavoraMod::isSkyEnabled,
                v -> {
                    KavoraMod.setSkyEnabled(v);
                    if (renderer.getSkyRenderer() != null) {
                        renderer.getSkyRenderer().forceRefresh();
                    }
                }));

        list.add(KavoraSetting.ofBool("sky.ambient", SettingGroup.SKY,
                "kavora.setting.sky.ambient", "kavora.setting.sky.ambient.tip",
                SkyRenderer.isSkyAmbientRectEnabled(),
                SkyRenderer::isSkyAmbientRectEnabled,
                v -> {
                    SkyRenderer.setSkyAmbientRectEnabled(v);
                    if (renderer.getSkyRenderer() != null) {
                        renderer.getSkyRenderer().forceRefresh();
                    }
                }));
        list.add(skyFloat(
                renderer,
                "sky.mieScatteringAnisotropy",
                "kavora.setting.sky.haloFocus",
                0.0,
                SkyRenderer.MAX_MIE_SCATTERING_ANISOTROPY,
                SkyRenderer::getMieScatteringAnisotropy,
                SkyRenderer::setMieScatteringAnisotropy));


        list.add(KavoraSetting.ofBool("clouds.procedural", SettingGroup.CLOUDS,
                "kavora.setting.clouds.procedural",
                "kavora.setting.clouds.procedural.tip",
                true,
                renderer.getCloudRenderer()::isProceduralClouds,
                renderer.getCloudRenderer()::setProceduralClouds));
        list.add(floatSetting(
                "clouds.vanillaEdgeSmoothness",
                SettingGroup.CLOUDS,
                "kavora.setting.clouds.vanillaEdgeSmoothness",
                0.0,
                1.99,
                renderer.getCloudRenderer()::getVanillaEdgeSmoothness,
                renderer.getCloudRenderer()::setVanillaEdgeSmoothness));
        list.add(floatSetting(
                "clouds.size",
                SettingGroup.CLOUDS,
                "kavora.setting.clouds.size",
                0.1,
                2.0,
                renderer.getCloudRenderer()::getCloudSize,
                renderer.getCloudRenderer()::setCloudSize));
        list.add(floatSetting(
                "clouds.height",
                SettingGroup.CLOUDS,
                "kavora.setting.clouds.height",
                -128.0,
                128.0,
                renderer.getCloudRenderer()::getCloudsHeight,
                renderer.getCloudRenderer()::setCloudsHeight));

        list.add(skyFloat(renderer, SettingGroup.CELESTIAL_LIGHTS, "sky.sunIntensity", "kavora.setting.sky.sunIntensity",
                0.0, 100.0,
                LightManager::getSunIntensity, LightManager::setSunIntensity));
        list.add(skyFloat(renderer, SettingGroup.CELESTIAL_LIGHTS, "sky.moonIntensity", "kavora.setting.sky.moonIntensity",
                0.0, 10.0,
                LightManager::getMoonIntensity, LightManager::setMoonIntensity));
        list.add(skyFloat(renderer, SettingGroup.CELESTIAL_LIGHTS, "sky.moonSize", "kavora.setting.sky.moonSize",
                0.1, 2.0,
                SkyRenderer::getMoonSize, SkyRenderer::setMoonSize));
        list.add(skyFloat(renderer, SettingGroup.CELESTIAL_LIGHTS, "sky.moonPhaseContrast", "kavora.setting.sky.moonPhaseContrast",
                0.0, 1.0,
                LightManager::getMoonPhaseContrast, LightManager::setMoonPhaseContrast));
        list.add(skyFloat(renderer, SettingGroup.CELESTIAL_LIGHTS, "sky.dayAmbient", "kavora.setting.sky.dayAmbient",
                0.0, 5.0,
                SkyRenderer::getDayAmbientBoost, SkyRenderer::setDayAmbientBoost));
        list.add(skyFloat(renderer, SettingGroup.CELESTIAL_LIGHTS, "sky.nightAmbient", "kavora.setting.sky.nightAmbient",
                0.0, 10.0,
                SkyRenderer::getNightAmbientBoost, SkyRenderer::setNightAmbientBoost));

        for (BlockLightIntensityControls.Control control
                : BlockLightIntensityControls.controls()) {
            list.add(KavoraSetting.ofFloat(
                    control.settingId(),
                    SettingGroup.BLOCK_LIGHTS,
                    control.labelKey(),
                    "kavora.setting.blockLights.multiplier.tip",
                    0.0,
                    BlockLightIntensityControls.MAX_MULTIPLIER,
                    BlockLightIntensityControls.DEFAULT_MULTIPLIER,
                    control::get,
                    control::set));
        }

        // --- Materials ---
        BlockTextureManager textures = renderer.getTextureManager();
        addWaterSettings(list, textures);
        list.add(KavoraSetting.ofBool("materials.autoPbr", SettingGroup.MATERIALS,
                "kavora.setting.materials.autoPbr", "kavora.setting.materials.autoPbr.tip",
                AutoPbr.DEFAULT_ENABLED, textures::isAutoPbr, textures::setAutoPbr));
        list.add(KavoraSetting.ofFloat(
                KavoraOptionFactory.TERRAIN_EMISSION_MULTIPLIER_ID,
                SettingGroup.MATERIALS,
                "kavora.setting.materials.terrainEmission",
                "kavora.setting.materials.terrainEmission.tip",
                0.0,
                BlockTextureManager.MAX_TERRAIN_EMISSION_MULTIPLIER,
                BlockTextureManager.DEFAULT_TERRAIN_EMISSION_MULTIPLIER,
                () -> (double) textures.getTerrainEmissionMultiplier(),
                value -> textures.setTerrainEmissionMultiplier(value.floatValue())));
        RemixAPI remix = renderer.getRemixApi();
        remixBool("fog.enabled", SettingGroup.FOG, "kavora.setting.fog.enabled",
                "rtx.volumetrics.enable", remix::getConfigVariableResolved, remix::setConfigVariable)
                .ifPresent(list::add);
        remixBool("post.bloom", SettingGroup.BLOOM, "kavora.setting.post.bloom",
                "rtx.bloom.enable", remix::getConfigVariableResolved, remix::setConfigVariable)
                .ifPresent(list::add);
        remixBool("materials.smoothReliefMaps", SettingGroup.PARALLAX,
                "kavora.setting.materials.smoothReliefMaps",
                "rtx.materials.smoothReliefMapFiltering",
                remix::getConfigVariableResolved, remix::setConfigVariable)
                .ifPresent(list::add);

        // --- Tonemapping ---
        list.add(floatSetting("post.exposure", SettingGroup.TONEMAPPING,
                "kavora.setting.post.exposure", 0.0, 2.0,
                postProcessing::getExposure, postProcessing::setExposure));
        list.add(floatSetting("post.shadows", SettingGroup.TONEMAPPING,
                "kavora.setting.post.shadows", -5.0, 5.0,
                postProcessing::getShadowLevel, postProcessing::setShadowLevel));
        list.add(floatSetting("post.highlights", SettingGroup.TONEMAPPING,
                "kavora.setting.post.highlights", -5.0, 10.0,
                postProcessing::getHighlightsLevel, postProcessing::setHighlightsLevel));

        // --- Eye adaptation ---
        list.add(floatSetting("post.lowLightAdaptation", SettingGroup.EYE_ADAPTATION,
                "kavora.setting.post.lowLightAdaptation", -5.0, 5.0,
                postProcessing::getLowLightAdaptation, postProcessing::setLowLightAdaptation));
        list.add(floatSetting("post.highLightAdaptation", SettingGroup.EYE_ADAPTATION,
                "kavora.setting.post.highLightAdaptation", -5.0, 10.0,
                postProcessing::getHighLightAdaptation, postProcessing::setHighLightAdaptation));

        KavoraEffectOptions.addTo(list, remix);
        var fogProfiles = new FogProfileOptions(list, environment, remix);
        LavaLightOptions.add(list, textures);
        return new KavoraSettings(list, fogProfiles::publish);
    }

    static KavoraSetting<Integer> unfocusedFramerate() {
        return KavoraSetting.ofInt(KavoraOptionFactory.UNFOCUSED_FRAMERATE_ID, SettingGroup.FRAMERATE,
                "kavora.setting.framerate.unfocused",
                "kavora.setting.framerate.unfocused.tip",
                UnfocusedFramerateLimit.MIN, UnfocusedFramerateLimit.UNLIMITED,
                UnfocusedFramerateLimit.DEFAULT,
                UnfocusedFramerateLimit::get, UnfocusedFramerateLimit::set)
                .withStep(UnfocusedFramerateLimit.STEP);
    }

    private static void addWaterSettings(List<KavoraSetting<?>> list, BlockTextureManager textures) {
        list.add(floatSetting("water.ior", SettingGroup.WATER, "kavora.effect.water.ior", 1, 2.5,
                textures::getWaterIor, value -> textures.updateWaterIor(value)));
        list.add(floatSetting("water.absorptionDistance", SettingGroup.WATER, "kavora.effect.water.absorptionDistance", 0.05, 128,
                textures::getWaterTransmittanceDistance, value -> textures.updateWaterTransmittanceDistance(value)));
        list.add(floatSetting("water.red", SettingGroup.WATER, "kavora.effect.water.red", 0, 1,
                textures::getWaterTransmittanceR, value -> textures.updateWaterTransmittanceColor(
                        value, textures.getWaterTransmittanceG(), textures.getWaterTransmittanceB())));
        list.add(floatSetting("water.green", SettingGroup.WATER, "kavora.effect.water.green", 0, 1,
                textures::getWaterTransmittanceG, value -> textures.updateWaterTransmittanceColor(
                        textures.getWaterTransmittanceR(), value, textures.getWaterTransmittanceB())));
        list.add(floatSetting("water.blue", SettingGroup.WATER, "kavora.effect.water.blue", 0, 1,
                textures::getWaterTransmittanceB, value -> textures.updateWaterTransmittanceColor(
                        textures.getWaterTransmittanceR(), textures.getWaterTransmittanceG(), value)));
        list.add(KavoraSetting.ofInt("water.animationFps", SettingGroup.WATER,
                "kavora.effect.water.animationFps", "kavora.effect.water.animationFps.tip", 0, 120,
                textures.getWaterAnimationFps(), textures::getWaterAnimationFps,
                value -> textures.updateWaterAnimationFps(value)));
    }

    /**
     * Binds a boolean Remix option, with the value Remix resolved at startup as
     * its default. The setting then reports its own writes rather than reading
     * the option again, because Remix resolves a write at a later option
     * update and a read before that still returns the previous value.
     *
     * <p>Returns empty when the loaded runtime cannot resolve the option, for
     * example a runtime built before the option existed, so the menu leaves
     * the setting out instead of failing client startup.
     */
    static Optional<KavoraSetting<Boolean>> remixBool(String id, SettingGroup group, String labelKey,
            String option, Function<String, String> configReader,
            BiConsumer<String, String> configWriter) {
        boolean[] value = new boolean[1];
        try {
            value[0] = Boolean.parseBoolean(configReader.apply(option));
        } catch (RemixException e) {
            LOGGER.warn("Remix option {} is unavailable in this runtime, so the {} setting is hidden",
                    option, id, e);
            return Optional.empty();
        }
        return Optional.of(KavoraSetting.ofBool(id, group, labelKey, labelKey + ".tip", value[0],
                () -> value[0],
                v -> {
                    value[0] = v;
                    configWriter.accept(option, Boolean.toString(v));
                }));
    }

    private static KavoraSetting<Double> skyFloat(KavoraRenderer renderer, String id, String labelKey,
            double min, double max,
            Supplier<Float> getter, Consumer<Float> setter) {
        return skyFloat(renderer, SettingGroup.SKY, id, labelKey, min, max, getter, setter);
    }

    private static KavoraSetting<Double> skyFloat(KavoraRenderer renderer, SettingGroup group, String id, String labelKey,
            double min, double max, Supplier<Float> getter, Consumer<Float> setter) {
        return KavoraSetting.ofFloat(id, group, labelKey, labelKey + ".tip",
                min, max, getter.get(),
                () -> (double) getter.get(),
                v -> {
                    setter.accept(v.floatValue());
                    if (renderer.getSkyRenderer() != null) {
                        renderer.getSkyRenderer().forceRefresh();
                    }
                });
    }

    private static KavoraSetting<Double> floatSetting(String id, SettingGroup group, String labelKey,
            double min, double max,
            Supplier<Float> getter, Consumer<Float> setter) {
        return KavoraSetting.ofFloat(id, group, labelKey, labelKey + ".tip", min, max, getter.get(),
                () -> (double) getter.get(), v -> setter.accept(v.floatValue()));
    }

}
