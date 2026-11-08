/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.texture;

import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TorchEmissionAssetsTest {
    @ParameterizedTest
    @ValueSource(strings = {"block/torch", "block/soul_torch", "block/copper_torch",
            "particle/flame", "particle/soul_fire_flame", "particle/copper_fire_flame"})
    void eachFlameHasEmissiveTexelsAndANonEmissiveBackground(String sprite) throws IOException {
        var resource = getClass().getResource("/assets/minecraft/textures/" + sprite + "_s.png");
        assertNotNull(resource, sprite);
        var image = ImageIO.read(resource);
        int emitting = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int alpha = image.getRGB(x, y) >>> 24;
                // LabPBR reserves 255 for non-emissive material.
                if (alpha > 0 && alpha < 255) emitting++;
            }
        }
        assertTrue(emitting > 0, sprite + " is dark");
        assertTrue(emitting < image.getWidth() * image.getHeight(), sprite + " emits outside the flame");
    }
}
