/* SPDX-License-Identifier: Apache-2.0 */
package org.edtp.kavora.light;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

class AreaLightMergerTest {
    private static LightEntry face(int x, int z, float height, float radiance) {
        return LightEntry.rect(new BlockLightKey(new BlockPos(x, 0, z), Identifier.withDefaultNamespace("emission"), 0),
                15, null, x + .5f, height, z + .5f, 1, 0, 0, 1, 0, 0, 1, 1,
                0, 1, 0, LightEntry.ColorOverride.of(1, 1, 1), radiance, .5f);
    }

    @Test
    void aFullSectionSurfaceBecomesOneEmitterWithTheSamePower() {
        var lights = new ArrayList<LightEntry>();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) lights.add(face(x, z, .875f, 10));
        var merged = new AreaLightMerger().merge(lights, 0, 0, 0);
        assertEquals(1, merged.size());
        var light = merged.getFirst();
        assertEquals(8, light.x()); assertEquals(8, light.z()); assertEquals(.875f, light.y());
        assertEquals(16, light.xSize()); assertEquals(16, light.ySize());
        assertEquals(256 * LightPower.of(lights.getFirst(), 10, 10, 10), LightPower.of(light, 10, 10, 10), .001f);
    }

    @Test
    void aHoleIsNotFilledDuringCoalescing() {
        var lights = List.of(face(0, 0, 1, 10), face(1, 0, 1, 10), face(0, 1, 1, 10));
        var merged = new AreaLightMerger().merge(lights, 0, 0, 0);
        assertEquals(3, merged.stream().mapToDouble(l -> l.xSize() * l.ySize()).sum());
        for (var light : merged)
            assertFalse(Math.abs(light.x() - 1.5f) < light.xSize() * .5f
                    && Math.abs(light.z() - 1.5f) < light.ySize() * .5f);
    }

    @Test
    void differentRadianceAndPlanesRetainSeparateEmitters() {
        var lights = List.of(face(0, 0, 1, 10), face(1, 0, 1, 20), face(0, 0, 2, 10), face(1, 0, 2, 10));
        var merged = new AreaLightMerger().merge(lights, 0, 0, 0);
        assertEquals(3, merged.size());
        assertEquals(2, merged.stream().filter(l -> l.y() == 2).findFirst().orElseThrow().xSize());
    }
}
