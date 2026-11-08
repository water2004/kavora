/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import java.util.Set;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PbrSpriteSourcesTest {
    @Test
    void sidecarsRequireAnAlbedoInTheSameNamespace() {
        var albedos = Set.of(Identifier.parse("pack:textures/block/stone.png"));
        for (String suffix : new String[] {"_n", "_s", "_sss"}) {
            assertTrue(PbrSpriteSources.isAuxiliary(
                    Identifier.parse("pack:textures/block/stone" + suffix + ".png"), albedos::contains));
            assertFalse(PbrSpriteSources.isAuxiliary(
                    Identifier.parse("other:textures/block/stone" + suffix + ".png"), albedos::contains));
        }
    }

    @Test
    void ordinaryAndUnpairedTexturesRemainSprites() {
        for (String path : new String[] {"stone.png", "direction_s.png", "normal_n.png", "stone_n.png.mcmeta"}) {
            assertFalse(PbrSpriteSources.isAuxiliary(Identifier.parse("pack:textures/" + path), id -> false));
        }
        assertFalse(PbrSpriteSources.isAuxiliary(Identifier.parse("pack:textures/stone.png"), id -> true));
    }
}
