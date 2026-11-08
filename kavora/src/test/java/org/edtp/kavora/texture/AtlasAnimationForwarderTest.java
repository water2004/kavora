/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.texture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.Identifier;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AtlasAnimationForwarderTest {

    @AfterEach
    void clearForwarder() {
        AtlasAnimationForwarder.clear();
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
