/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.sky;

import org.edtp.kavora.render.RenderOrigin;
import org.edtp.kavora.sdk.LightInfo;
import org.edtp.kavora.sdk.LightInfoDomeEXT;
import org.edtp.kavora.sdk.LightInfoAtmosphereEXT;
import org.edtp.kavora.sdk.LightInfoRectEXT;
import org.edtp.kavora.sdk.MaterialInfoOpaqueEXT;
import org.edtp.kavora.sdk.RemixAPI;
import org.edtp.kavora.sdk.TextureBatchEntry;
import org.edtp.kavora.sdk.TextureFormat;
import org.edtp.kavora.sdk.TexturePatch;
import org.edtp.kavora.sdk.TexturePatchInfo;
import org.edtp.kavora.benchmark.BenchmarkController;
import org.edtp.kavora.asset.KavoraAssetBase;
import org.edtp.kavora.asset.KavoraLight;
import org.edtp.kavora.asset.KavoraLightStore;
import org.edtp.kavora.asset.KavoraTexture;
import org.edtp.kavora.asset.KavoraTextureStore;
import org.edtp.kavora.light.LightManager;
import org.edtp.kavora.profiling.ProfileScope;
import org.edtp.kavora.util.DirectBufferPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;

/**
 * Manages sky rendering for Kavora.
 *
 * <p>Uses a physically-based dome light generated from Rayleigh+Mie atmospheric
 * scattering, driven by Minecraft's time-of-day. The HDR weather dome provides the
 * environment behind procedural and classic clouds. Other dimensions publish a
 * fixed dome instead: the End's shared cosmic environment, and black with no sky ambient for a
 * skyless (SkyType.NONE) dimension such as the Nether.
 */
public class SkyRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger(SkyRenderer.class);

    /** Equirectangular sky texture resolution. */
    private static final int SKY_W = AtmosphericSkyGenerator.WIDTH;
    private static final int SKY_H = AtmosphericSkyGenerator.HEIGHT;
    private static final int OVERCAST_LATITUDES = 128;
    private static final float WEATHER_UPDATE_DELTA = 1.0e-4f;
    /** Sky ambient rect light: Y position just above the 1.18+ overworld build limit (y=320). */
    private static final float SKY_AMBIENT_RECT_Y = 325.0f;
    /** Sky ambient rect light: half-size in blocks, so it covers 8000 blocks from origin. */
    private static final float SKY_AMBIENT_RECT_HALF_SIZE = 8000.0f;
    /**
     * Constant sky for the End, in the atmospheric dome's radiance units: vanilla end_sky.png's
     * mean chroma under its 0xFF282828 vertex tint, at the luminance of that tint (40/255) times
     * the End's 0.15 fog factor. The native compositor replaces it with the procedural
     * End environment while that effect is enabled.
     */
    static final float[] END_SKY_RADIANCE = {0.0275f, 0.0210f, 0.0372f};
    /**
     * Constant sky for a skyless dimension (SkyType.NONE): a black dome and, since the ambient
     * rect carries the same constant, no sky ambient. Nothing above a roofed world reaches its
     * interior, so a rect above the build limit would light only the roof top; interior light
     * comes from emissives and the fog medium. The black dome is still published: without an
     * active dome light Remix composites escaped rays from its own sky matte, which nothing
     * draws here.
     */
    static final float[] SKYLESS_SKY_RADIANCE = {0.0f, 0.0f, 0.0f};
    public static final float DEFAULT_NIGHT_AMBIENT_BOOST = 2.5f;
    public static final float DEFAULT_DAY_AMBIENT_BOOST = 0.0f;
    public static final float DEFAULT_MOON_SIZE = 1.0f;
    public static final boolean DEFAULT_SKY_AMBIENT_RECT_ENABLED = true;
    public static final float DEFAULT_MIE_SCATTERING_ANISOTROPY = 0.9f;
    public static final float MAX_MIE_SCATTERING_ANISOTROPY = 0.99f;
    private static float nightAmbientBoost = DEFAULT_NIGHT_AMBIENT_BOOST;
    private static float dayAmbientBoost = DEFAULT_DAY_AMBIENT_BOOST;
    private static float mieScatteringAnisotropy = DEFAULT_MIE_SCATTERING_ANISOTROPY;
    private static float moonSize = DEFAULT_MOON_SIZE;

    public static float getNightAmbientBoost()        { return nightAmbientBoost; }
    public static void setNightAmbientBoost(float v)  { nightAmbientBoost = v; }
    public static float getDayAmbientBoost()          { return dayAmbientBoost; }
    public static void setDayAmbientBoost(float v)    { dayAmbientBoost = v; }
    public static float getMoonSize()                { return moonSize; }
    public static void setMoonSize(float v)           { moonSize = Math.clamp(v, 0.1f, 2.0f); }
    public static float getMieScatteringAnisotropy()  { return mieScatteringAnisotropy; }
    public static void setMieScatteringAnisotropy(float v) {
        mieScatteringAnisotropy = Math.clamp(v, 0.0f, MAX_MIE_SCATTERING_ANISOTROPY);
    }

    /**
     * Sun path tilt in degrees, matching popular shader packs (BSL, Complementary).
     * Tilts the sun's orbital plane around the east-west (X) axis so that even
     * at noon, shadows have an angle instead of pointing straight down.
     */
    public static final float SUN_PATH_ROTATION_DEG = LightManager.SUN_PATH_ROTATION_DEG;
    // ---- Instance state ----

    private final RemixAPI api;
    private final BenchmarkController clock;
    private final LightManager lights;
    private ClientLevel currentWorld;
    private KavoraTexture skyTextureAsset;
    private KavoraLight skyDomeLightAsset;
    private KavoraTexture skyTransmittanceAsset;
    private KavoraTexture airTransmittanceLutAsset;
    private KavoraTexture airScatteringLutAsset;
    private final LightInfoAtmosphereEXT atmosphereInfo = new LightInfoAtmosphereEXT();
    private final LightInfoDomeEXT skyDomeInfo = new LightInfoDomeEXT();
    private final LightInfo skyLightInfo = new LightInfo();
    private boolean hasAtmosphericTransport;
    private boolean atmosphereInfoChanged;
    private KavoraTexture overcastTexture;
    private KavoraLight overcastDomeLightAsset;
    private KavoraLight skyAmbientRectLight;
    private final CelestialSky celestialSky = new CelestialSky();
    private final AtmosphericSkyGenerator atmosphericGenerator;
    private final NightSkyState nightSky;
    private final TexturePatch atmosphericTexturePatch = new TexturePatch();
    private final TexturePatch transmittanceTexturePatch = new TexturePatch();
    private final TextureBatchEntry transmittanceTextureEntry = new TextureBatchEntry();
    private final TextureBatchEntry atmosphericTextureEntry = new TextureBatchEntry();
    private final TexturePatchInfo atmosphericTextureUpdate = new TexturePatchInfo();
    private final float[] atmosphericMean = new float[3];
    private float lastWeatherOpacity = -1f;
    private final float[] overcastIrradianceOverPi = new float[3];
    private final float[] lastWeatherRadiance = new float[3];
    private float lastSkyDomeMultiplier = Float.NaN;
    private OvercastIllumination lastAirWeather = OvercastIllumination.CLEAR;

    private static boolean enabled = true;
    private static boolean skyAmbientRectEnabled = DEFAULT_SKY_AMBIENT_RECT_ENABLED;
    private static float skyDomeMultiplier = 1.0f;

    public SkyRenderer(RemixAPI api, BenchmarkController clock, LightManager lights) {
        this.api = api;
        this.clock = clock;
        this.lights = lights;
        nightSky = new NightSkyState(api::setConfigVariable);
        atmosphericGenerator = new AtmosphericSkyGenerator();
        skyDomeInfo.transform[6] = 1.0f / AtmosphericSkyGenerator.VERTICAL_SCALE;
        skyLightInfo.domeExt = skyDomeInfo;
        atmosphericTexturePatch.width = SKY_W;
        atmosphericTexturePatch.height = SKY_H;
        atmosphericTexturePatch.rowPitch = SKY_W * 4 * Short.BYTES;
        atmosphericTexturePatch.format = TextureFormat.RGBA16_SFLOAT;
        atmosphericTextureEntry.patches = new TexturePatch[]{atmosphericTexturePatch};
        transmittanceTexturePatch.width = 1;
        transmittanceTexturePatch.height = SKY_H;
        transmittanceTexturePatch.rowPitch = 8;
        transmittanceTexturePatch.format = TextureFormat.RGBA16_SFLOAT;
        transmittanceTextureEntry.patches = new TexturePatch[]{transmittanceTexturePatch};
        atmosphericTextureUpdate.entries = new TextureBatchEntry[]{atmosphericTextureEntry, transmittanceTextureEntry};
        LOGGER.info("Sky renderer initialized (Rayleigh+Mie dome light)");
    }

    public static boolean isEnabled()                        { return enabled; }
    public static void setEnabled(boolean v)                 { enabled = v; }
    public static boolean isSkyAmbientRectEnabled()          { return skyAmbientRectEnabled; }
    public static void setSkyAmbientRectEnabled(boolean v)   { skyAmbientRectEnabled = v; }
    public static float getSkyDomeMultiplier()               { return skyDomeMultiplier; }
    public static void setSkyDomeMultiplier(float v)         { skyDomeMultiplier = v; }

    /** Invalidates cached sky parameters so they are applied on the next frame. */
    public void forceRefresh() {
        lastWeatherOpacity = -1f;
    }

    // ====================================================================
    //  Main render entry
    // ====================================================================

    /** Publishes the sky from the same weather snapshot used by the cloud renderer. */
    public void render(ClientLevel world, Vec3 cameraPos, EnvironmentSnapshot environment,
                       boolean volumetricClouds) {
        if (world == null || !enabled) {
            nightSky.disable();
            return;
        }

        // A portal replaces the world without a disconnect, and each sky type publishes a
        // different set of lights, so nothing published for the previous world may carry over.
        if (world != currentWorld) {
            releaseSkyLights();
            currentWorld = world;
        }

        float[] fixedRadiance = fixedSkyRadiance(environment.skybox);
        if (fixedRadiance == null) {
            renderAtmosphericSky(world, cameraPos, environment, volumetricClouds);
        } else {
            if (environment.skybox == DimensionType.Skybox.END) {
                nightSky.updateEnd(clock.animationGameTime(environment.gameTime) * 0.05);
            } else {
                nightSky.disable();
            }
            renderFixedSky(api, fixedRadiance);
        }
    }

    /**
     * Maps a fixed sky type to its base dome radiance.
     *
     * @return the constant, or null for a NORMAL sky, whose dome is the time-driven atmosphere
     */
    static float[] fixedSkyRadiance(DimensionType.Skybox skyType) {
        return switch (skyType) {
            case OVERWORLD -> null;
            case END -> END_SKY_RADIANCE;
            case NONE -> SKYLESS_SKY_RADIANCE;
        };
    }

    private void renderAtmosphericSky(
            ClientLevel world,
            Vec3 cameraPos,
            EnvironmentSnapshot environment,
            boolean volumetricClouds) {
        LightManager.SkyLightState lightState = lights.getSkyLightState();
        OvercastIllumination weather = lightState.weather();

        try (ProfileScope ignored = ProfileScope.begin("Sky.Celestial")) {
            celestialSky.draw(api, lightState);
        }
        try (ProfileScope ignored = ProfileScope.begin("Sky.Atmosphere")) {
            nightSky.update(environment.starBrightness, environment.starAngle,
                    clock.animationGameTime(environment.gameTime) * 0.05, environment.moonPhase);
            updateAtmosphericDomeLight(api, lightState, (float) (cameraPos.y - world.getSeaLevel()),
                    world.getSeaLevel(), volumetricClouds);
        }
        try (ProfileScope ignored = ProfileScope.begin("Sky.Weather")) {
            if (weather.opacity() > 0) {
                for (int i = 0; i < 3; i++) {
                    overcastIrradianceOverPi[i] = atmosphericMean[i] * weather.diffuseSky()
                            + lightState.scatteredWeatherBeam()[i];
                }
                drawWeatherDomeLight(api, weather);
            }
        }
        try (ProfileScope ignored = ProfileScope.begin("Sky.Ambient")) {
            publishCombinedSkyAmbient(api, lightState.sunPosition()[1], weather);
        }
    }

    /**
     * Publishes the fixed-sky base layer. Environment illumination follows the same dome
     * sampled by camera rays. The texture is filled once per world without atmospheric compute.
     */
    private void renderFixedSky(RemixAPI api, float[] radiance) {
        if (skyTextureAsset == null || !skyTextureAsset.isAlive()) {
            destroyAsset(skyTextureAsset);
            ByteBuffer pixels = DirectBufferPool.acquire(AtmosphericSkyGenerator.OUTPUT_BYTES);
            fillFixedDome(pixels, radiance);
            skyTextureAsset = createSkyTexture(pixels);
        }
        publishSkyDome(api, false, OvercastIllumination.CLEAR);
    }

    /**
     * Fills an RGBA16F equirect of the dome's resolution with one opaque radiance, leaving the
     * buffer positioned at zero with its limit on the last dome byte.
     *
     * @throws IllegalArgumentException when the buffer cannot hold the whole dome
     */
    static void fillFixedDome(ByteBuffer pixels, float[] radiance) {
        if (pixels.capacity() < AtmosphericSkyGenerator.OUTPUT_BYTES) {
            throw new IllegalArgumentException(
                    "Dome buffer is smaller than " + AtmosphericSkyGenerator.OUTPUT_BYTES);
        }
        short r = Float.floatToFloat16(radiance[0]);
        short g = Float.floatToFloat16(radiance[1]);
        short b = Float.floatToFloat16(radiance[2]);
        short a = Float.floatToFloat16(1.0f);
        pixels.clear();
        pixels.order(ByteOrder.nativeOrder());
        pixels.limit(AtmosphericSkyGenerator.OUTPUT_BYTES);
        for (int texel = 0; texel < SKY_W * SKY_H; texel++) {
            pixels.putShort(r).putShort(g).putShort(b).putShort(a);
        }
        pixels.flip();
    }

    // ====================================================================
    //  Dome lights
    // ====================================================================

    private void updateAtmosphericDomeLight(
            RemixAPI api,
            LightManager.SkyLightState lightState, float observerHeight, float seaLevelMeters,
            boolean finiteAtmosphere) {
        regenerateSkyTexture(api, lightState, observerHeight, seaLevelMeters);
        publishSkyDome(api, finiteAtmosphere, lightState.weather());
    }

    /**
     * Publishes the live sky texture with its matching optical state and multiplier.
     * Does nothing until the texture exists.
     */
    private void publishSkyDome(RemixAPI api, boolean finiteAtmosphere, OvercastIllumination weather) {
        if (skyTextureAsset == null || !skyTextureAsset.isAlive()) {
            return;
        }
        boolean domeAlive = skyDomeLightAsset != null && skyDomeLightAsset.isAlive();
        LightInfoAtmosphereEXT desiredAtmosphere = finiteAtmosphere && hasAtmosphericTransport ? atmosphereInfo : null;
        boolean airChanged = desiredAtmosphere != null
                && (atmosphereInfoChanged || lastAirWeather.sunBeam() != weather.sunBeam()
                || lastAirWeather.moonBeam() != weather.moonBeam());
        if (airChanged) atmosphericGenerator.describeAtmosphere(desiredAtmosphere, weather);
        if (!domeAlive || skyLightInfo.atmosphereExt != desiredAtmosphere
                || airChanged
                || Float.compare(lastSkyDomeMultiplier, skyDomeMultiplier) != 0) {
            skyDomeInfo.colorTexture = skyTextureAsset.getHandle();
            skyDomeInfo.transmittanceTexture = skyTransmittanceAsset != null ? skyTransmittanceAsset.getHandle() : 0;
            skyLightInfo.radiance.x = skyDomeMultiplier;
            skyLightInfo.radiance.y = skyDomeMultiplier;
            skyLightInfo.radiance.z = skyDomeMultiplier;
            skyLightInfo.atmosphereExt = desiredAtmosphere;
            if (domeAlive) {
                KavoraLightStore.get().update(skyDomeLightAsset, skyLightInfo);
            } else {
                destroyAsset(skyDomeLightAsset);
                skyDomeLightAsset = new KavoraLight(skyLightInfo, null);
                KavoraLightStore.get().create(skyDomeLightAsset);
            }
            lastSkyDomeMultiplier = skyDomeMultiplier;
            if (desiredAtmosphere != null) {
                lastAirWeather = weather;
                atmosphereInfoChanged = false;
            }
        }
        if (skyDomeLightAsset.isAlive()) {
            try (ProfileScope ignored =
                    ProfileScope.begin("Sky.Atmosphere.DomeDraw")) {
                skyDomeLightAsset.draw(api);
            }
        }
    }

    private void drawWeatherDomeLight(RemixAPI api, OvercastIllumination weather) {
        boolean textureChanged = overcastTexture == null || !overcastTexture.isAlive();
        if (textureChanged) {
            destroyAsset(overcastTexture);
            overcastTexture = createOvercastTexture();
        }
        boolean needsUpdate = textureChanged || overcastDomeLightAsset == null
                || !overcastDomeLightAsset.isAlive()
                || Math.abs(weather.opacity() - lastWeatherOpacity) > WEATHER_UPDATE_DELTA;
        for (int i = 0; i < 3; i++) {
            needsUpdate |= Math.abs(overcastIrradianceOverPi[i] * skyDomeMultiplier - lastWeatherRadiance[i]) > WEATHER_UPDATE_DELTA;
        }

        if (needsUpdate) {
            LightInfoDomeEXT domeExt = new LightInfoDomeEXT();
            domeExt.setColorTexture(overcastTexture.getHandle());
            domeExt.setOpacity(weather.opacity());
            domeExt.setBlendType(MaterialInfoOpaqueEXT.BLEND_TYPE_ALPHA);
            for (int i = 0; i < 3; i++) lastWeatherRadiance[i] = overcastIrradianceOverPi[i] * skyDomeMultiplier;
            LightInfo lightInfo = LightInfo.builder()
                    .radiance(lastWeatherRadiance[0], lastWeatherRadiance[1], lastWeatherRadiance[2])
                    .domeExt(domeExt)
                    .build();

            if (overcastDomeLightAsset != null && overcastDomeLightAsset.isAlive()) {
                KavoraLightStore.get().update(overcastDomeLightAsset, lightInfo);
            } else {
                if (overcastDomeLightAsset != null && !overcastDomeLightAsset.isDeleted()) {
                    overcastDomeLightAsset.destroy();
                }
                overcastDomeLightAsset = new KavoraLight(lightInfo, null);
                KavoraLightStore.get().create(overcastDomeLightAsset);
            }

            lastWeatherOpacity = weather.opacity();
        }

        if (overcastDomeLightAsset.isAlive()) overcastDomeLightAsset.draw(api);
    }

    private static KavoraTexture createOvercastTexture() {
        ByteBuffer pixels = DirectBufferPool.acquire(OVERCAST_LATITUDES * 8);
        short opaque = Float.floatToFloat16(1f);
        for (int y = 0; y < OVERCAST_LATITUDES; y++) {
            float elevation = (float) Math.cos(Math.PI * (y + 0.5) / OVERCAST_LATITUDES);
            // Normalize the angular profile so its cosine-weighted mean equals one.
            // The published RGB then represents the same E/pi as the sky reduction.
            short radiance = Float.floatToFloat16((9f + 6f * Math.max(0f, elevation)) / 13f);
            pixels.putShort(radiance).putShort(radiance).putShort(radiance).putShort(opaque);
        }
        pixels.flip();
        return KavoraTextureStore.get().create(
                new KavoraTexture(1, OVERCAST_LATITUDES, TextureFormat.RGBA16_SFLOAT, 0, pixels, true));
    }

    private void regenerateSkyTexture(
            RemixAPI api,
            LightManager.SkyLightState lightState, float observerHeight, float seaLevelMeters) {
        // All published assets must be live for a skip to be safe. They are released whenever the world
        // changes, and a world whose time never advances keeps the parameters constant forever,
        // so gating on the parameters alone would never rebuild them and the dome would stay black.
        boolean publishedOutputValid = skyTextureAsset != null
                && skyTextureAsset.isAlive()
                && skyDomeLightAsset != null
                && skyDomeLightAsset.isAlive()
                && skyTransmittanceAsset != null && skyTransmittanceAsset.isAlive()
                && airTransmittanceLutAsset != null && airTransmittanceLutAsset.isAlive()
                && airScatteringLutAsset != null && airScatteringLutAsset.isAlive();
        ByteBuffer pixelBuf = atmosphericGenerator.generate(
                lightState,
                atmosphericMean,
                mieScatteringAnisotropy,
                observerHeight,
                seaLevelMeters,
                publishedOutputValid);
        if (pixelBuf == null) {
            return;
        }
        publishAtmosphereModel();
        String auditDirectory = System.getProperty("kavora.skySnapshotDirectory");
        if (auditDirectory != null) {
            byte[] pixels = new byte[pixelBuf.remaining()];
            pixelBuf.duplicate().get(pixels);
            try {
                var auditPath = java.nio.file.Path.of(auditDirectory);
                java.nio.file.Files.createDirectories(auditPath);
                java.nio.file.Files.write(auditPath.resolve("sky-panorama.rgba16f"), pixels);
                byte[] transmission = new byte[SKY_H * 8];
                atmosphericGenerator.transmittance().duplicate().get(transmission);
                java.nio.file.Files.write(auditPath.resolve("sky-transmittance.rgba16f"), transmission);
                java.nio.file.Files.writeString(auditPath.resolve("sky-irradiance.json"),
                        java.util.Arrays.toString(atmosphericMean));
                atmosphericGenerator.snapshotTransport(auditPath);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        try (ProfileScope ignored =
                ProfileScope.begin("Sky.Atmosphere.Upload")
                        .bytes(AtmosphericSkyGenerator.OUTPUT_BYTES)) {
            if (skyTextureAsset == null || !skyTextureAsset.isAlive()) {
                destroyAsset(skyTextureAsset);
                ByteBuffer ownedPixels =
                        DirectBufferPool.acquire(
                                AtmosphericSkyGenerator.OUTPUT_BYTES);
                ownedPixels.put(pixelBuf);
                ownedPixels.flip();
                pixelBuf.position(0);
                skyTextureAsset = createSkyTexture(ownedPixels);
                ByteBuffer transmission = DirectBufferPool.acquire(SKY_H * 8);
                transmission.put(atmosphericGenerator.transmittance().duplicate()).flip();
                destroyAsset(skyTransmittanceAsset);
                skyTransmittanceAsset = KavoraTextureStore.get().create(
                        new KavoraTexture(1, SKY_H, TextureFormat.RGBA16_SFLOAT, 0, transmission));
            } else {
                updateAtmosphericTexture(api, pixelBuf);
            }
        }
    }

    /** Publishes each immutable table once per asset lifetime, using the completed sky's light state. */
    private void publishAtmosphereModel() {
        if (airTransmittanceLutAsset == null || !airTransmittanceLutAsset.isAlive()) {
            destroyAsset(airTransmittanceLutAsset);
            airTransmittanceLutAsset = createAirTable(0, AtmosphericSkyGenerator.LUT_WIDTH, AtmosphericSkyGenerator.LUT_HEIGHT);
        }
        if (airScatteringLutAsset == null || !airScatteringLutAsset.isAlive()) {
            destroyAsset(airScatteringLutAsset);
            airScatteringLutAsset = createAirTable(1, AtmosphericSkyGenerator.SCATTERING_WIDTH, AtmosphericSkyGenerator.SCATTERING_HEIGHT);
        }
        atmosphereInfo.transmittanceLut = airTransmittanceLutAsset.getHandle();
        atmosphereInfo.scatteringLut = airScatteringLutAsset.getHandle();
        hasAtmosphericTransport = true;
        atmosphereInfoChanged = true;
    }

    private KavoraTexture createAirTable(int index, int width, int height) {
        ByteBuffer pixels = DirectBufferPool.acquire(width * height * 16);
        atmosphericGenerator.copyTransportTable(index, pixels);
        return KavoraTextureStore.get().create(new KavoraTexture(width, height, TextureFormat.RGBA32_SFLOAT, 0, pixels, true));
    }

    /** Creates the dome's equirect texture over a pool buffer the texture then owns. */
    private static KavoraTexture createSkyTexture(ByteBuffer ownedPixels) {
        KavoraTexture texture = new KavoraTexture(
                SKY_W,
                SKY_H,
                TextureFormat.RGBA16_SFLOAT,
                0,
                ownedPixels);
        return KavoraTextureStore.get().create(texture);
    }

    private void updateAtmosphericTexture(RemixAPI api, ByteBuffer pixelData) {
        atmosphericTexturePatch.pixelData = pixelData;
        atmosphericTextureEntry.handle = skyTextureAsset.getHandle();
        transmittanceTexturePatch.pixelData = atmosphericGenerator.transmittance();
        transmittanceTextureEntry.handle = skyTransmittanceAsset.getHandle();
        api.updateTextureFromMemory(atmosphericTextureUpdate);
    }

    private void publishCombinedSkyAmbient(
            RemixAPI api,
            float sunHeight,
            OvercastIllumination weather) {
        float multiplier = skyAmbientMultiplier(sunHeight);
        publishSkyAmbient(
                api,
                Mth.lerp(weather.opacity(), atmosphericMean[0], overcastIrradianceOverPi[0]) * multiplier,
                Mth.lerp(weather.opacity(), atmosphericMean[1], overcastIrradianceOverPi[1]) * multiplier,
                Mth.lerp(weather.opacity(), atmosphericMean[2], overcastIrradianceOverPi[2]) * multiplier);
    }

    /** Updates the sky ambient rect to this radiance and draws it; a black or disabled rect stays undrawn. */
    private void publishSkyAmbient(RemixAPI api, float radianceR, float radianceG, float radianceB) {
        if (!skyAmbientRectEnabled
                || (radianceR == 0.0f && radianceG == 0.0f && radianceB == 0.0f)) {
            return;
        }

        LightInfoRectEXT rectExt = LightInfoRectEXT.builder()
                .position(-RenderOrigin.current().x(), SKY_AMBIENT_RECT_Y, -RenderOrigin.current().z())
                .xAxis(1, 0, 0)
                .xSize(SKY_AMBIENT_RECT_HALF_SIZE)
                .yAxis(0, 0, 1)
                .ySize(SKY_AMBIENT_RECT_HALF_SIZE)
                .direction(0, -1, 0)
                .volumetricRadianceScale(1.0f)
                .build();

        LightInfo ambientInfo = LightInfo.builder()
                .radiance(radianceR, radianceG, radianceB)
                .rectExt(rectExt)
                .build();

        if (skyAmbientRectLight != null && skyAmbientRectLight.isAlive()) {
            KavoraLightStore.get().update(skyAmbientRectLight, ambientInfo);
        } else {
            destroyAsset(skyAmbientRectLight);
            skyAmbientRectLight = new KavoraLight(ambientInfo, null);
            KavoraLightStore.get().create(skyAmbientRectLight);
        }
        if (skyAmbientRectLight.isAlive()) {
            skyAmbientRectLight.draw(api);
        }
    }

    static float skyAmbientMultiplier(float sunHeight) {
        float nightFactor = 1.0f - LightManager.celestialVisibility(sunHeight);
        return dayAmbientBoost * (1.0f - nightFactor) + nightAmbientBoost * nightFactor;
    }

    public void reloadResources() {
        celestialSky.close();
    }

    private static void destroyAsset(KavoraAssetBase asset) {
        if (asset != null && !asset.isDeleted()) {
            asset.destroy();
        }
    }

    // ====================================================================
    //  Sun direction (shared with LightManager)
    // ====================================================================

    /**
     * Computes the sun's position direction from Minecraft's {@code skyAngle},
     * with the standard shader-pack tilt applied.
     *
     * @param skyAngle {@code world.getSkyAngle(tickDelta)}: 0.0 at noon, 0.25 at sunset, etc.
     * @return float[3] {x, y, z} normalised sun position direction
     */
    public static float[] computeSunDirection(float skyAngle) {
        return LightManager.computeSunDirection(skyAngle);
    }

    // ====================================================================
    //  Cleanup
    // ====================================================================

    /** Releases world-scoped Remix assets while preserving reusable atmospheric Vulkan state. */
    public void cleanup() {
        nightSky.disable();
        celestialSky.close();
        releaseSkyLights();
        currentWorld = null;
    }

    /**
     * Releases the dome, overcast, and ambient lights with the sky texture they read, so the
     * next frame publishes them from scratch for whatever sky the current world has.
     */
    private void releaseSkyLights() {
        atmosphericGenerator.invalidate();
        destroyAsset(skyDomeLightAsset);
        skyDomeLightAsset = null;
        destroyAsset(skyTextureAsset);
        skyTextureAsset = null;
        destroyAsset(skyTransmittanceAsset);
        skyTransmittanceAsset = null;
        destroyAsset(airTransmittanceLutAsset);
        airTransmittanceLutAsset = null;
        destroyAsset(airScatteringLutAsset);
        airScatteringLutAsset = null;
        hasAtmosphericTransport = false;
        atmosphereInfoChanged = false;
        atmosphericTexturePatch.pixelData = null;
        transmittanceTexturePatch.pixelData = null;
        destroyAsset(overcastDomeLightAsset);
        overcastDomeLightAsset = null;
        destroyAsset(overcastTexture);
        overcastTexture = null;
        destroyAsset(skyAmbientRectLight);
        skyAmbientRectLight = null;
        lastWeatherOpacity = -1f;
        lastSkyDomeMultiplier = Float.NaN;
        lastAirWeather = OvercastIllumination.CLEAR;
        java.util.Arrays.fill(atmosphericMean, 0f);
    }

    /** Releases world assets and process-lifetime atmospheric Vulkan resources. */
    public void shutdown() {
        try {
            cleanup();
        } finally {
            atmosphericGenerator.destroy();
        }
    }
}
