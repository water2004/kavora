/* SPDX-License-Identifier: Apache-2.0 */
package org.edtp.kavora.texture;

import java.util.Arrays;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoPbrTest {
    private static AutoPbr.Surface surface(String name) {
        return AutoPbr.surface(Identifier.withDefaultNamespace("block/" + name), false);
    }

    @Test
    void naturalSurfacesAndPolishedMetalsRetainDifferentResponses() {
        assertTrue(surface("dirt").roughness() > surface("polished_andesite").roughness());
        assertTrue(surface("grass_block_top").roughness() > surface("iron_block").roughness());
        assertFalse(surface("mud").metal());
        assertTrue(surface("copper_block").metal());
        assertFalse(surface("oxidized_copper").metal());
    }

    @Test
    void animatedSpritesAndLiquidsRequireAuthoredMaterialMaps() {
        assertNull(surface("water_still"));
        assertNull(surface("lava_flow"));
        assertNull(AutoPbr.surface(Identifier.withDefaultNamespace("block/grass_block_top"), true));
    }

    @Test
    void generatedNormalsUseDirectxOrientationAndStayInsideTheSprite() {
        byte[] albedo = new byte[5 * 5 * 4];
        byte[] normals = new byte[albedo.length];
        Arrays.fill(normals, (byte) 17);
        for (int y = 1; y <= 3; y++) for (int x = 1; x <= 3; x++) {
            int pixel = (y * 5 + x) * 4;
            Arrays.fill(albedo, pixel, pixel + 3, (byte) (30 * (x + y)));
            albedo[pixel + 3] = (byte) 255;
        }
        AutoPbr.writeNormal(albedo, normals, 5, 1, 1, 3, 3, 1);
        int center = (2 * 5 + 2) * 4;
        assertTrue((normals[center] & 255) < 128);
        assertTrue((normals[center + 1] & 255) < 128);
        assertTrue((normals[center + 2] & 255) > 230);
        assertEquals(17, normals[0]);
        assertTrue((normals[(2 * 5 + 1) * 4] & 255) > 128, "neighbours wrap within the sprite");
    }

    @Test
    void transparentNormalsAndAlbedoRemainUnchanged() {
        byte[] albedo = {20, 20, 20, 0, (byte) 200, (byte) 200, (byte) 200, (byte) 255};
        byte[] original = albedo.clone();
        byte[] normals = new byte[8];
        Arrays.fill(normals, (byte) 31);
        AutoPbr.writeNormal(albedo, normals, 2, 0, 0, 2, 1, 1);
        assertEquals(31, normals[0]);
        assertEquals(31, normals[1]);
        AutoPbr.writeMaterial(albedo, new byte[2], new byte[2], 2, 0, 0, 2, 1, surface("dirt"));
        assertArrayEquals(original, albedo);
    }
}
