/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.texture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.edtp.kavora.logic.AtmosphereDimension;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.imageio.ImageIO;
import net.minecraft.resources.Identifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtlasAnimationForwarderTest {

    @AfterEach
    void clearForwarder() {
        AtlasAnimationForwarder.clear();
    }

    @Test
    void lavaMaterialStripsMatchTheBaseAnimationFrames() throws IOException {
        String directory = "/assets/minecraft/textures/block/";
        for (String sprite : new String[] {"lava_still", "lava_flow"}) {
            try (var baseStream = Objects.requireNonNull(getClass().getResourceAsStream(directory + sprite + ".png"));
                 var materialStream = Objects.requireNonNull(getClass().getResourceAsStream(directory + sprite + "_s.png"))) {
                var base = ImageIO.read(baseStream);
                var material = ImageIO.read(materialStream);
                assertEquals(base.getWidth(), material.getWidth(), sprite + " material frame width");
                assertEquals(base.getHeight(), material.getHeight(), sprite + " material frame count");
            }
        }
    }

    @Test
    void lavaFilterKeepsTheMaskAndLuminance() {
        var nether = new LavaEmission.Grade(LavaEmission.defaults(AtmosphereDimension.NETHER));
        int source = 70 | 24 << 8 | 8 << 16;
        int graded = nether.rgb(70, 24, 8);
        assertEquals(0, nether.rgb(0, 0, 0));
        assertTrue((graded & 255) < (source & 255));
        assertTrue((graded >>> 8 & 255) > (source >>> 8 & 255));
        assertEquals(luminance(source), luminance(graded), 0.0005);
        assertTrue(luminance(nether.rgb(95, 39, 12)) > luminance(graded));
        int sameLuminance = nether.rgb(45, 43, 8);
        assertTrue((graded & 255) - (graded >>> 8 & 255)
                > (sameLuminance & 255) - (sameLuminance >>> 8 & 255));

        for (var dimension : List.of(AtmosphereDimension.OVERWORLD, AtmosphereDimension.END)) {
            var material = new LavaEmission.Grade(LavaEmission.defaults(dimension));
            assertTrue(material.identity);
            assertEquals(source, material.rgb(70, 24, 8));
        }
    }

    @Test
    void lavaSaturationAndBrightnessAreIndependentOfHueAndWarmth() {
        var gray = new LavaEmission.Grade(new LavaEmission.Profile(0.8f, 120f, 0f, 1f));
        int rgb = gray.rgb(70, 24, 8);
        int channel = rgb & 255;
        assertEquals(channel | channel << 8 | channel << 16, rgb);
        assertEquals(luminance(70 | 24 << 8 | 8 << 16), luminance(rgb), 0.0005);

        var shifted = new LavaEmission.Grade(new LavaEmission.Profile(0f, 120f, 1f, 1f));
        var dimmed = new LavaEmission.Grade(new LavaEmission.Profile(0f, 120f, 1f, 0.5f));
        int shiftedRgb = shifted.rgb(70, 24, 8);
        assertTrue((shiftedRgb >>> 8 & 255) > (shiftedRgb & 255));
        assertEquals(luminance(shiftedRgb) * 0.5, luminance(dimmed.rgb(70, 24, 8)), 0.0005);
    }

    private static double luminance(int rgb) {
        return 0.2126 * Math.pow((rgb & 255) / 255.0, 2.2)
                + 0.7152 * Math.pow((rgb >>> 8 & 255) / 255.0, 2.2)
                + 0.0722 * Math.pow((rgb >>> 16) / 255.0, 2.2);
    }

    @Test
    void acceptsOnlySpritesFromTheCapturedBlockAtlas() {
        Identifier block = Identifier.withDefaultNamespace("block/lava_still");
        Identifier gui = Identifier.withDefaultNamespace("gui/sprites/icon");
        AtlasAnimationForwarder.init(Map.of(), Set.of(block));
        org.junit.jupiter.api.Assertions.assertTrue(AtlasAnimationForwarder.acceptsAnimation(BlockTextureManager.BLOCK_ATLAS_ID, block));
        org.junit.jupiter.api.Assertions.assertFalse(AtlasAnimationForwarder.acceptsAnimation(BlockTextureManager.BLOCK_ATLAS_ID, gui));
        org.junit.jupiter.api.Assertions.assertFalse(AtlasAnimationForwarder.acceptsAnimation(
                Identifier.withDefaultNamespace("textures/atlas/particles.png"), block));
    }
    @Test
    void aPausedOrClearedForwarderAcceptsNoUpdates() {
        Identifier sprite = Identifier.withDefaultNamespace("block/lava_still");
        AtlasAnimationForwarder.init(Map.of(), Set.of(sprite));
        AtlasAnimationForwarder.pause();
        org.junit.jupiter.api.Assertions.assertFalse(AtlasAnimationForwarder.acceptsAnimation(BlockTextureManager.BLOCK_ATLAS_ID, sprite));
        AtlasAnimationForwarder.resume();
        org.junit.jupiter.api.Assertions.assertTrue(AtlasAnimationForwarder.acceptsAnimation(BlockTextureManager.BLOCK_ATLAS_ID, sprite));
        AtlasAnimationForwarder.clear();
        org.junit.jupiter.api.Assertions.assertFalse(AtlasAnimationForwarder.acceptsAnimation(BlockTextureManager.BLOCK_ATLAS_ID, sprite));
    }
}
