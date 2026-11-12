/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.texture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.resources.Identifier;

class BuiltinEmissionTest {

    /** Centre of texel (col, row) of a 16x16 layout, in sprite UV space. */
    private static float texel(int index) {
        return (index + 0.5f) / 16f;
    }

    @Test
    void seaPickleBurnsAtTheTipAndFadesOutWithinTheSprout() {
        BuiltinEmission.Mask m = BuiltinEmission.SEA_PICKLE;
        float tip = m.factor(texel(1), texel(0), -1);
        float second = m.factor(texel(2), texel(1), -1);
        float third = m.factor(texel(13), texel(2), -1);
        float fourth = m.factor(texel(14), texel(3), -1);

        assertTrue(tip > 0.7f * BuiltinEmission.SEA_PICKLE_TIP_PEAK, "the tip row is hot: " + tip);
        assertTrue(tip > second && second > third && third > fourth, "monotonic fade down the sprout");
        assertTrue(second > 0.25f * tip, "the fade is gradual, not a single bright texel: " + second);
        assertTrue(fourth < 0.05f, "nearly out by the fourth row: " + fourth);
        assertEquals(0f, m.factor(texel(1), texel(4), -1), "the sprout's bottom row is dark");
    }

    @Test
    void seaPickleBodyStaysDark() {
        BuiltinEmission.Mask m = BuiltinEmission.SEA_PICKLE;
        // Body side faces read rows 5 to 11 across the whole width; the top face, rim and
        // opening read columns 4 to 12 of rows 1 to 5. None of it glows.
        for (int col = 0; col < 16; col++) {
            assertEquals(0f, m.factor(texel(col), texel(8), -1), "body column " + col);
        }
        for (int col = 4; col < 12; col++) {
            assertEquals(0f, m.factor(texel(col), texel(2), -1), "top face column " + col);
        }
    }

    @Test
    void onlyListedSpritesGetAMask() {
        assertSame(BuiltinEmission.SEA_PICKLE, BuiltinEmission.mask(Identifier.withDefaultNamespace("block/sea_pickle")));
        assertNull(BuiltinEmission.mask(Identifier.withDefaultNamespace("block/lantern")));
    }

    @Test
    void livingCoralEmissionPreservesSpriteContrastAndCoverage() {
        var mask = BuiltinEmission.mask(Identifier.withDefaultNamespace("block/tube_coral_fan"));
        int brightBlue = 0xffe55c40;
        int darkBlue = 0xff88371c;
        float factor = mask.factor(.5f, .5f, brightBlue);
        assertTrue(factor > 0f);
        assertEquals(factor, mask.factor(.5f, .5f, darkBlue));
        assertEquals(0f, mask.factor(.5f, .5f, brightBlue & 0x00ffffff));
        int brightEmission = LabPBRConverter.emissivePixel(brightBlue, factor);
        int darkEmission = LabPBRConverter.emissivePixel(darkBlue, factor);
        assertTrue((brightEmission & 0xffffff) > (darkEmission & 0xffffff));
        assertNull(BuiltinEmission.mask(Identifier.withDefaultNamespace("block/dead_tube_coral_fan")));
        assertNull(BuiltinEmission.mask(Identifier.withDefaultNamespace("block/tube_coral_block")));
    }

}
