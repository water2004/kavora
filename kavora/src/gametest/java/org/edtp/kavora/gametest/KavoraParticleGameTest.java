/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.gametest;

import org.edtp.kavora.KavoraMod;
import org.edtp.kavora.render.RenderBackend;
import java.nio.file.Path;
import java.util.Set;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;

/** Real pixel readback distinguishes blended ordinary particles from terrain cutouts. */
public final class KavoraParticleGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        if (!Boolean.getBoolean("kavora.gametest.particleOnly")) return;
        boolean hidden = context.computeOnClient(client -> client.gui.hud.isHidden());
        try (var world = context.worldBuilder().create()) {
            world.getServer().runOnServer(server -> {
                server.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
                server.getGameRules().set(GameRules.ADVANCE_WEATHER, false, server);
                server.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
                KavoraSceneFixtures.commands(server, "weather clear", "time set 9000",
                        "fill -3 -61 5 3 -54 5 minecraft:white_concrete");
                var player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.CREATIVE);
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                if (!player.teleportTo(server.overworld(), .5, -58, .5, Set.of(), 0, 0, true)) {
                    throw new AssertionError("Particle camera setup failed");
                }
            });
            context.runOnClient(client -> {
                if (!KavoraMod.isParticlesEnabled()) throw new AssertionError("Particle capture must be enabled");
                if (!client.gui.hud.isHidden()) client.gui.hud.toggle();
            });
            context.waitTicks(30);
            Path baseline = KavoraWorldGameTest.assertWorldRenders(context, world, "particle-baseline");
            if (Boolean.getBoolean("kavora.gametest.particleTemporalOnly")) {
                KavoraParticleTemporalChecks.run(context, world);
                return;
            }
            if (Boolean.getBoolean("kavora.gametest.particleLighting")) {
                diagnoseLighting(context, world);
                return;
            }
            try (var generation = RuntimeOptionScope.acquire(context, "rtx.dlfg.enable", "false")) {
                KavoraParticleMotionChecks.run(context, world);
            }
            context.waitTicks(3);
            baseline = KavoraWorldGameTest.captureCompletedFrame(context, "particle-after-motion");
            Probe ordinary = spawn(context, false);
            context.waitTicks(30);
            Path blended = KavoraWorldGameTest.captureCompletedFrame(context, name(context, "particle-quarter-opacity"));
            double ordinaryDelta = blueContrast(blended) - blueContrast(baseline);
            context.runOnClient(client -> ordinary.remove());
            if (ordinaryDelta < 8) throw new AssertionError(
                    "Ordinary particle alpha 0.25 was cut out instead of blended; blue delta=" + ordinaryDelta);
            if (meanRed(blended) < meanRed(baseline) * .3) throw new AssertionError(
                    "Ordinary particle lost the background transmission expected at alpha 0.25");
            context.waitTicks(30);
            Path restored = KavoraWorldGameTest.captureCompletedFrame(context, name(context, "particle-restored"));
            Probe terrain = spawn(context, true);
            context.waitTicks(30);
            Path cutout = KavoraWorldGameTest.captureCompletedFrame(context, name(context, "particle-terrain-quarter-opacity"));
            double terrainDelta = blueContrast(cutout) - blueContrast(restored);
            context.runOnClient(client -> terrain.remove());
            for (boolean useTerrain : new boolean[] {false, true}) {
                Probe faint = spawn(context, useTerrain);
                context.runOnClient(client -> faint.opacity(.08f));
                context.waitTicks(30);
                Path belowCutoff = KavoraWorldGameTest.captureCompletedFrame(context,
                        name(context, useTerrain ? "terrain-below-cutoff" : "translucent-below-cutoff"));
                context.runOnClient(client -> faint.remove());
                if (blueContrast(belowCutoff) - blueContrast(restored) > 4) {
                    throw new AssertionError("Particle alpha 0.08 passed vanilla's 0.1 cutoff: " + belowCutoff);
                }
            }
            if (terrainDelta < 8) throw new AssertionError(
                    "Opaque particle alpha 0.25 should pass vanilla's 0.1 cutoff; blue delta=" + terrainDelta);
            KavoraBlockDamageChecks.run(context);
            org.slf4j.LoggerFactory.getLogger(KavoraParticleGameTest.class).info(
                    "Particle native alpha policies: ordinary delta={}, terrain delta={}", ordinaryDelta, terrainDelta);
        } finally {
            context.runOnClient(client -> { if (client.gui.hud.isHidden() != hidden) client.gui.hud.toggle(); });
        }
    }

    private static Probe spawn(ClientGameTestContext context, boolean terrain) {
        return spawn(context, terrain, !terrain);
    }

    private static Probe spawn(ClientGameTestContext context, boolean terrain, boolean emissive) {
        return context.computeOnClient(client -> {
            Identifier atlasId = terrain ? org.edtp.kavora.texture.BlockTextureManager.BLOCK_ATLAS_ID
                    : TextureAtlas.LOCATION_PARTICLES;
            TextureAtlas atlas = (TextureAtlas) client.getTextureManager().getTexture(atlasId);
            TextureAtlasSprite sprite = atlas.getSprite(terrain ? Identifier.withDefaultNamespace("block/white_concrete")
                    : emissive ? Identifier.fromNamespaceAndPath("kavora-client-tests", "alpha_probe")
                    : Identifier.withDefaultNamespace("generic_0"));
            var image = ((org.edtp.kavora.mixin.SpriteContentsAccessor) sprite.contents()).getImage();
            int pixel = image.getPixel(sprite.contents().width() / 2, sprite.contents().height() / 2);
            if ((pixel >>> 24 & 255) < 240 || (pixel & 255) < 200) {
                throw new AssertionError("Particle probe needs an opaque bright-blue texel: " + sprite.contents().name());
            }
            var position = client.gameRenderer.mainCamera().position().add(client.player.getViewVector(1).scale(2));
            Probe particle = new Probe(client.level, position.x, position.y, position.z, sprite, terrain, emissive);
            client.particleEngine.add(particle);
            return particle;
        });
    }

    private static void diagnoseLighting(ClientGameTestContext context,
            net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext world) {
        String key = "rtx.volumetrics.enableAtmosphere";
        String previous = context.computeOnClient(client -> KavoraMod.renderer.getRemixApi().getConfigVariableResolved(key));
        String enabled = context.computeOnClient(client -> KavoraMod.renderer.getRemixApi().getConfigVariableResolved("rtx.volumetrics.enable"));
        String reference = context.computeOnClient(client -> KavoraMod.renderer.getRemixApi().getConfigVariableResolved("rtx.volumetrics.enableReferenceMode"));
        String scaling = context.computeOnClient(client -> KavoraMod.renderer.getRemixApi().getConfigVariableResolved("rtx.volumetrics.debugDisableRadianceScaling"));
        try {
            context.runOnClient(client -> KavoraMod.renderer.getRemixApi().setConfigVariable(key, "false"));
            context.waitTicks(60);
            double sky = captureDarkProbe(context, "particle-light-sky");
            world.getServer().runOnServer(server -> KavoraSceneFixtures.commands(server, "setblock 1 -57 2 minecraft:torch"));
            context.waitTicks(60);
            double torch = captureDarkProbe(context, "particle-light-torch");
            world.getServer().runOnServer(server -> KavoraSceneFixtures.commands(server, "setblock 1 -57 2 minecraft:air"));
            context.runOnClient(client -> KavoraMod.renderer.getRemixApi().setConfigVariable(key, "true"));
            context.waitTicks(100);
            double atmosphere = captureDarkProbe(context, "particle-light-atmosphere");
            context.runOnClient(client -> KavoraMod.renderer.getRemixApi().setConfigVariable("rtx.volumetrics.enable", "false"));
            context.waitTicks(60);
            double unattenuated = captureDarkProbe(context, "particle-light-unattenuated");
            context.runOnClient(client -> KavoraMod.renderer.getRemixApi().setConfigVariable("rtx.volumetrics.enableReferenceMode", "true"));
            context.waitTicks(60);
            double referenceDelta = captureDarkProbe(context, "particle-light-reference");
            context.runOnClient(client -> KavoraMod.renderer.getRemixApi().setConfigVariable("rtx.volumetrics.debugDisableRadianceScaling", "true"));
            context.waitTicks(60);
            double unscaled = captureDarkProbe(context, "particle-light-unscaled");
            context.runOnClient(client -> {
                var api = KavoraMod.renderer.getRemixApi();
                api.setConfigVariable("rtx.volumetrics.enableReferenceMode", "false");
                api.setConfigVariable("rtx.volumetrics.debugDisableRadianceScaling", scaling);
            });
            for (String option : new String[] {"enableInitialVisibility", "visibilityReuse", "enableTemporalResampling", "enableSpatialResampling"}) {
                String optionKey = "rtx.volumetrics." + option;
                String old = context.computeOnClient(client -> KavoraMod.renderer.getRemixApi().getConfigVariableResolved(optionKey));
                try {
                    context.runOnClient(client -> KavoraMod.renderer.getRemixApi().setConfigVariable(optionKey, "false"));
                    context.waitTicks(60);
                    double delta = captureDarkProbe(context, "particle-light-no-" + option);
                    org.slf4j.LoggerFactory.getLogger(KavoraParticleGameTest.class).info("Particle lighting without {}: {}", option, delta);
                } finally {
                    context.runOnClient(client -> KavoraMod.renderer.getRemixApi().setConfigVariable(optionKey, old));
                }
            }
            org.slf4j.LoggerFactory.getLogger(KavoraParticleGameTest.class).info(
                    "Particle lighting diagnostics: sky={}, torch={}, atmosphere={}, unattenuated={}, reference={}, unscaled={}",
                    sky, torch, atmosphere, unattenuated, referenceDelta, unscaled);
        } finally {
            context.runOnClient(client -> {
                var api = KavoraMod.renderer.getRemixApi();
                api.setConfigVariable(key, previous);
                api.setConfigVariable("rtx.volumetrics.enable", enabled);
                api.setConfigVariable("rtx.volumetrics.enableReferenceMode", reference);
                api.setConfigVariable("rtx.volumetrics.debugDisableRadianceScaling", scaling);
            });
        }
    }

    private static double captureDarkProbe(ClientGameTestContext context, String label) {
        Path baseline = KavoraWorldGameTest.captureCompletedFrame(context, name(context, label + "-baseline"));
        Probe particle = spawn(context, false, false);
        try {
            context.waitTicks(30);
            Path image = KavoraWorldGameTest.captureCompletedFrame(context, name(context, label));
            return blueContrast(image) - blueContrast(baseline);
        } finally {
            context.runOnClient(client -> particle.remove());
            context.waitTicks(30);
        }
    }

    private static String name(ClientGameTestContext context, String label) {
        return label + "-" + context.computeOnClient(client -> RenderBackend.current().name().toLowerCase(java.util.Locale.ROOT));
    }

    private static double blueContrast(Path image) {
        try {
            var pixels = ImageIO.read(image.toFile());
            long sum = 0, count = 0;
            for (int y = pixels.getHeight() * 45 / 100; y < pixels.getHeight() * 55 / 100; y++) {
                for (int x = pixels.getWidth() * 45 / 100; x < pixels.getWidth() * 55 / 100; x++) {
                    int rgb = pixels.getRGB(x, y);
                    sum += (rgb & 255) - (rgb >>> 16 & 255); count++;
                }
            }
            return (double) sum / count;
        } catch (java.io.IOException e) { throw new AssertionError("Cannot inspect particle capture", e); }
    }

    private static double meanRed(Path image) {
        try {
            var pixels = ImageIO.read(image.toFile());
            long sum = 0, count = 0;
            for (int y = pixels.getHeight() * 45 / 100; y < pixels.getHeight() * 55 / 100; y++) {
                for (int x = pixels.getWidth() * 45 / 100; x < pixels.getWidth() * 55 / 100; x++) {
                    sum += pixels.getRGB(x, y) >>> 16 & 255; count++;
                }
            }
            return (double) sum / count;
        } catch (java.io.IOException e) { throw new AssertionError("Cannot inspect particle capture", e); }
    }

    private static final class Probe extends SingleQuadParticle {
        private final boolean terrain;
        Probe(ClientLevel world, double x, double y, double z, TextureAtlasSprite sprite, boolean terrain, boolean emissive) {
            super(world, x, y, z, sprite);
            this.terrain = terrain;
            quadSize = .8f; alpha = .25f; rCol = emissive ? 1 : 0; gCol = emissive ? 1 : 0; bCol = 1;
        }
        void opacity(float value) { setAlpha(value); }
        @Override public void tick() { if (++age > 400) remove(); }
        @Override protected Layer getLayer() { return terrain ? Layer.OPAQUE_TERRAIN : Layer.TRANSLUCENT; }
        @Override protected float getU0() { return (sprite.getU0() + sprite.getU1()) * .5f; }
        @Override protected float getU1() { return getU0(); }
        @Override protected float getV0() { return (sprite.getV0() + sprite.getV1()) * .5f; }
        @Override protected float getV1() { return getV0(); }
    }
}
