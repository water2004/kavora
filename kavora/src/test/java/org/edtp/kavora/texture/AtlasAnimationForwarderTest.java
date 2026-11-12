/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.texture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.imageio.ImageIO;
import net.minecraft.resources.Identifier;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
