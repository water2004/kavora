/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import com.nvidia.rtxremix.LightInfo;
import com.nvidia.rtxremix.LightInfoDomeEXT;
import com.nvidia.rtxremix.LightInfoRectEXT;
import com.nvidia.rtxremix.MaterialInfoOpaqueEXT;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.TextureBatchEntry;
import com.nvidia.rtxremix.TextureFormat;
import com.nvidia.rtxremix.TexturePatch;
import com.nvidia.rtxremix.TexturePatchInfo;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.asset.RemixAssetBase;
import com.nvidia.rtxremix.minecraft.asset.RemixLight;
import com.nvidia.rtxremix.minecraft.asset.RemixLightStore;
import com.nvidia.rtxremix.minecraft.asset.RemixTexture;
import com.nvidia.rtxremix.minecraft.asset.RemixTextureStore;
import com.nvidia.rtxremix.minecraft.light.LightManager;
import com.nvidia.rtxremix.minecraft.logic.ConfLayerDriver;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;

/**
 * Manages sky rendering for RTX Remix.
 *
 * <p>Uses a physically-based dome light generated from Rayleigh+Mie atmospheric
 * scattering, driven by Minecraft's time-of-day. Weather blends a static HDR
 * overcast dome over the atmospheric sky. Dimensions without a NORMAL sky publish a
 * fixed dome instead: the End's shared cosmic environment, and black with no sky ambient for a
 * skyless (SkyType.NONE) dimension such as the Nether.
 */
public class SkyRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger(SkyRenderer.class);

    /** Equirectangular sky texture resolution. */
    private static final int SKY_W = AtmosphericSkyGenerator.WIDTH;
    private static final int SKY_H = AtmosphericSkyGenerator.HEIGHT;
    private static final String OVERCAST_TEXTURE_DDS = "textures/overcast_thunderstorm_bc6u.dds";
    private static final float RAIN_DOME_MIN_INTENSITY = 0.02f;
    private static final float OVERCAST_MEAN_R = 0.2393801f;
    private static final float OVERCAST_MEAN_G = 0.26890025f;
    private static final float OVERCAST_MEAN_B = 0.31064802f;
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

    private final RemixRenderer renderer;
    private ClientLevel currentWorld;
    private RemixTexture skyTextureAsset;
    private RemixLight skyDomeLightAsset;
    private RemixTexture skyTransmittanceAsset;
    private long overcastTextureHandle;
    private RemixLight overcastDomeLightAsset;
    private RemixLight skyAmbientRectLight;
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
    private float lastWeatherIntensity = -1f;
    private float lastSkyDomeMultiplier = Float.NaN;
    private float atmosphericMeanR;
    private float atmosphericMeanG;
    private float atmosphericMeanB;
    private boolean overcastInitializationFailed;

    private static boolean enabled = true;
    private static boolean skyAmbientRectEnabled = DEFAULT_SKY_AMBIENT_RECT_ENABLED;
    private static float skyDomeMultiplier = 1.0f;

    public SkyRenderer(RemixRenderer renderer) {
        this.renderer = renderer;
        nightSky = new NightSkyState((key, value) -> renderer.getRemixApi().setConfigVariable(key, value));
        atmosphericGenerator = new AtmosphericSkyGenerator();
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
        lastWeatherIntensity = -1f;
    }

    // ====================================================================
    //  Main render entry
    // ====================================================================

    private final EnvironmentSnapshot environment = new EnvironmentSnapshot();

    public void render(ClientLevel world, Vec3 cameraPos, float tickDelta) {
        if (renderer.getRemixApi() == null) return;
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

        RemixAPI api = renderer.getRemixApi();
        environment.capture(world, Minecraft.getInstance().gameRenderer.mainCamera(), tickDelta);
        float[] fixedRadiance = fixedSkyRadiance(environment.skybox);
        if (fixedRadiance == null) {
            renderAtmosphericSky(api, world, cameraPos, tickDelta);
        } else {
            if (environment.skybox == DimensionType.Skybox.END) {
                nightSky.updateEnd(renderer.getBenchmarkController().animationGameTime(environment.gameTime) * 0.05);
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
            RemixAPI api,
            ClientLevel world,
            Vec3 cameraPos,
            float tickDelta) {
        float skyAngle;
        LightManager.SkyLightState lightState;
        float[] sunDirection;
        WeatherDomeState weather;
        try (ProfileScope ignored = ProfileScope.begin("Sky.Prepare")) {
            skyAngle = environment.sunAngle;
            ConfLayerDriver confLayers = renderer.getConfLayerDriver();
            ConfLayerDriver.WeatherStrengths weatherStrengths =
                    confLayers != null && confLayers.isActive()
                            ? confLayers.weatherStrengths()
                            : new ConfLayerDriver.WeatherStrengths(
                                    environment.rain,
                                    environment.thunder);
            LightManager lightManager = renderer.getLightManager();
            lightState = lightManager != null
                    ? lightManager.getSkyLightState()
                    : LightManager.SkyLightState.empty();
            sunDirection = LightManager.computeSunDirection(skyAngle);
            float celestialIntensity =
                    lightState.weatherDomeRadiance();
            weather = weatherDomeState(
                    weatherStrengths.rain(),
                    weatherStrengths.thunder(),
                    Math.max(
                            celestialIntensity,
                            RAIN_DOME_MIN_INTENSITY),
                    celestialIntensity
                            * LightManager.THUNDER_SKY_INTENSITY);
        }

        try (ProfileScope ignored = ProfileScope.begin("Sky.Celestial")) {
            celestialSky.draw(api, lightState);
        }
        try (ProfileScope ignored = ProfileScope.begin("Sky.Atmosphere")) {
            nightSky.update(environment.starBrightness, environment.starAngle,
                    renderer.getBenchmarkController().animationGameTime(environment.gameTime) * 0.05, environment.moonPhase);
            updateAtmosphericDomeLight(api, lightState, (float) (cameraPos.y - world.getSeaLevel()));
        }
        boolean weatherRendered;
        try (ProfileScope ignored = ProfileScope.begin("Sky.Weather")) {
            weatherRendered = updateWeatherDomeLight(api, weather);
        }
        try (ProfileScope ignored = ProfileScope.begin("Sky.Ambient")) {
            publishCombinedSkyAmbient(
                    api,
                    sunDirection[1],
                    weatherRendered
                            ? weather
                            : WeatherDomeState.CLEAR);
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
        publishSkyDome(api);
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
            LightManager.SkyLightState lightState, float observerHeight) {
        regenerateSkyTexture(api, lightState, observerHeight);
        publishSkyDome(api);
    }

    /**
     * Creates the dome light over the live sky texture when it is missing, refreshes its
     * multiplier when that changed, and draws it. Does nothing until the texture exists.
     */
    private void publishSkyDome(RemixAPI api) {
        if (skyTextureAsset == null || !skyTextureAsset.isAlive()) {
            return;
        }
        boolean domeAlive = skyDomeLightAsset != null && skyDomeLightAsset.isAlive();
        if (!domeAlive || Float.compare(lastSkyDomeMultiplier, skyDomeMultiplier) != 0) {
            LightInfoDomeEXT domeExt = new LightInfoDomeEXT();
            domeExt.transform[6] = 1.0f / AtmosphericSkyGenerator.VERTICAL_SCALE;
            domeExt.setColorTexture(skyTextureAsset.getHandle());
            if (skyTransmittanceAsset != null) domeExt.transmittanceTexture = skyTransmittanceAsset.getHandle();
            LightInfo lightInfo = LightInfo.builder()
                    .radiance(skyDomeMultiplier, skyDomeMultiplier, skyDomeMultiplier)
                    .domeExt(domeExt)
                    .build();
            if (domeAlive) {
                RemixLightStore.get().update(skyDomeLightAsset, lightInfo);
            } else {
                destroyAsset(skyDomeLightAsset);
                skyDomeLightAsset = new RemixLight(lightInfo, null);
                RemixLightStore.get().create(skyDomeLightAsset);
            }
            lastSkyDomeMultiplier = skyDomeMultiplier;
        }
        if (skyDomeLightAsset.isAlive()) {
            try (ProfileScope ignored =
                    ProfileScope.begin("Sky.Atmosphere.DomeDraw")) {
                skyDomeLightAsset.draw(api);
            }
        }
    }

    private boolean updateWeatherDomeLight(RemixAPI api, WeatherDomeState weather) {
        if (weather.opacity() <= 0.0f) {
            return false;
        }

        float domeIntensity = weather.intensity() * skyDomeMultiplier;
        boolean needsUpdate = overcastDomeLightAsset == null
                || Math.abs(weather.opacity() - lastWeatherOpacity) > WEATHER_UPDATE_DELTA
                || Math.abs(domeIntensity - lastWeatherIntensity) > WEATHER_UPDATE_DELTA;

        if (needsUpdate) {
            if (!ensureOvercastTexture(api)) {
                return false;
            }

            LightInfoDomeEXT domeExt = new LightInfoDomeEXT();
            domeExt.setColorTexture(overcastTextureHandle);
            domeExt.setOpacity(weather.opacity());
            domeExt.setBlendType(MaterialInfoOpaqueEXT.BLEND_TYPE_ALPHA);
            LightInfo lightInfo = LightInfo.builder()
                    .radiance(domeIntensity, domeIntensity, domeIntensity)
                    .domeExt(domeExt)
                    .build();

            if (overcastDomeLightAsset != null && overcastDomeLightAsset.isAlive()) {
                RemixLightStore.get().update(overcastDomeLightAsset, lightInfo);
            } else {
                if (overcastDomeLightAsset != null && !overcastDomeLightAsset.isDeleted()) {
                    overcastDomeLightAsset.destroy();
                }
                overcastDomeLightAsset = new RemixLight(lightInfo, null);
                RemixLightStore.get().create(overcastDomeLightAsset);
            }

            lastWeatherOpacity = weather.opacity();
            lastWeatherIntensity = domeIntensity;
        }

        if (overcastDomeLightAsset == null || !overcastDomeLightAsset.isAlive()) {
            return false;
        }
        overcastDomeLightAsset.draw(api);
        return true;
    }

    private boolean ensureOvercastTexture(RemixAPI api) {
        if (overcastTextureHandle != 0) {
            return true;
        }
        if (overcastInitializationFailed) {
            return false;
        }

        String gameDirectory = Minecraft.getInstance()
                .gameDirectory.getAbsolutePath();
        String texturePath = gameDirectory + File.separator + OVERCAST_TEXTURE_DDS;
        try {
            overcastTextureHandle = api.createTextureFromFile(texturePath);
            if (overcastTextureHandle == 0) {
                throw new IllegalStateException("Remix returned a null texture handle");
            }
            LOGGER.info("Loaded overcast dome texture {}", texturePath);
            return true;
        } catch (RuntimeException e) {
            overcastInitializationFailed = true;
            LOGGER.error("Failed to load overcast dome texture {}; using atmospheric sky", texturePath, e);
            return false;
        }
    }

    private void regenerateSkyTexture(
            RemixAPI api,
            LightManager.SkyLightState lightState, float observerHeight) {
        // Both assets must be live for a skip to be safe. They are released whenever the world
        // changes, and a world whose time never advances keeps the parameters constant forever,
        // so gating on the parameters alone would never rebuild them and the dome would stay black.
        boolean publishedOutputValid = skyTextureAsset != null
                && skyTextureAsset.isAlive()
                && skyDomeLightAsset != null
                && skyDomeLightAsset.isAlive();
        ByteBuffer pixelBuf = atmosphericGenerator.generate(
                lightState,
                atmosphericMean,
                mieScatteringAnisotropy,
                observerHeight,
                publishedOutputValid);
        if (pixelBuf == null) {
            return;
        }
        String auditDirectory = System.getProperty("rtxremix.skySnapshotDirectory");
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
        atmosphericMeanR = atmosphericMean[0];
        atmosphericMeanG = atmosphericMean[1];
        atmosphericMeanB = atmosphericMean[2];

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
                skyTransmittanceAsset = RemixTextureStore.get().create(
                        new RemixTexture(1, SKY_H, TextureFormat.RGBA16_SFLOAT, 0, transmission));
            } else {
                updateAtmosphericTexture(api, pixelBuf);
            }
        }
    }

    /** Creates the dome's equirect texture over a pool buffer the texture then owns. */
    private static RemixTexture createSkyTexture(ByteBuffer ownedPixels) {
        RemixTexture texture = new RemixTexture(
                SKY_W,
                SKY_H,
                TextureFormat.RGBA16_SFLOAT,
                0,
                ownedPixels);
        return RemixTextureStore.get().create(texture);
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
            WeatherDomeState weather) {
        float multiplier = skyAmbientMultiplier(sunHeight);
        publishSkyAmbient(
                api,
                lerp(atmosphericMeanR, OVERCAST_MEAN_R * weather.intensity(), weather.opacity())
                        * multiplier,
                lerp(atmosphericMeanG, OVERCAST_MEAN_G * weather.intensity(), weather.opacity())
                        * multiplier,
                lerp(atmosphericMeanB, OVERCAST_MEAN_B * weather.intensity(), weather.opacity())
                        * multiplier);
    }

    /** Updates the sky ambient rect to this radiance and draws it; a black or disabled rect stays undrawn. */
    private void publishSkyAmbient(RemixAPI api, float radianceR, float radianceG, float radianceB) {
        if (!skyAmbientRectEnabled
                || (radianceR == 0.0f && radianceG == 0.0f && radianceB == 0.0f)) {
            return;
        }

        LightInfoRectEXT rectExt = LightInfoRectEXT.builder()
                .position(0.0f, SKY_AMBIENT_RECT_Y, 0.0f)
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
            RemixLightStore.get().update(skyAmbientRectLight, ambientInfo);
        } else {
            destroyAsset(skyAmbientRectLight);
            skyAmbientRectLight = new RemixLight(ambientInfo, null);
            RemixLightStore.get().create(skyAmbientRectLight);
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

    private static void destroyAsset(RemixAssetBase asset) {
        if (asset != null && !asset.isDeleted()) {
            asset.destroy();
        }
    }

    record WeatherDomeState(float opacity, float intensity) {
        private static final WeatherDomeState CLEAR = new WeatherDomeState(0.0f, 0.0f);
    }

    static WeatherDomeState weatherDomeState(
            float rainStrength,
            float thunderStrength,
            float rainIntensity,
            float thunderIntensity) {
        float rain = clamp01(rainStrength);
        float thunder = clamp01(thunderStrength);
        float opacity = lerp(rain, 1.0f, thunder);
        float premultipliedIntensity = lerp(
                rain * rainIntensity,
                thunderIntensity,
                thunder);
        float intensity = opacity > 0.0f ? premultipliedIntensity / opacity : 0.0f;
        return new WeatherDomeState(opacity, intensity);
    }

    private static float lerp(float from, float to, float amount) {
        return from + (to - from) * clamp01(amount);
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
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

        RemixAPI api = renderer.getRemixApi();
        if (overcastTextureHandle != 0 && api != null) {
            try {
                api.destroyTexture(overcastTextureHandle);
            } catch (RuntimeException e) {
                LOGGER.warn("Failed to destroy overcast dome texture: {}", e.getMessage());
            }
        }
        overcastTextureHandle = 0;
        overcastInitializationFailed = false;
    }

    /**
     * Releases the dome, overcast, and ambient lights with the sky texture they read, so the
     * next frame publishes them from scratch for whatever sky the current world has.
     */
    private void releaseSkyLights() {
        destroyAsset(skyDomeLightAsset);
        skyDomeLightAsset = null;
        destroyAsset(skyTextureAsset);
        skyTextureAsset = null;
        destroyAsset(skyTransmittanceAsset);
        skyTransmittanceAsset = null;
        atmosphericTexturePatch.pixelData = null;
        transmittanceTexturePatch.pixelData = null;
        destroyAsset(overcastDomeLightAsset);
        overcastDomeLightAsset = null;
        destroyAsset(skyAmbientRectLight);
        skyAmbientRectLight = null;
        lastWeatherOpacity = -1f;
        lastWeatherIntensity = -1f;
        lastSkyDomeMultiplier = Float.NaN;
        atmosphericMeanR = 0.0f;
        atmosphericMeanG = 0.0f;
        atmosphericMeanB = 0.0f;
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
