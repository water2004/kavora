/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.texture;

import java.util.Map;
import net.minecraft.resources.Identifier;

/**
 * Fallback emission masks for sprites whose resource pack authors no emission.
 *
 * <p>{@link #mask} supplies emission for a sprite
 * the pack authors none for, as a factor over the sprite's own UV space; an authored
 * {@code _s} with emission always wins over it, so a pack can replace these at any time.
 */
public final class BuiltinEmission {
    /** Emission factor at a point of a sprite, {@code u} and {@code v} in [0, 1) across the sprite. */
    @FunctionalInterface
    public interface Mask {
        float factor(float u, float v);
    }

    /**
     * Sea pickle, laid out as vanilla's {@code sea_pickle} model maps its 16x16 texture:
     * the sprout quads read columns 1 to 3 and 13 to 15 of rows 0 to 5, tip at row 0.
     * Only the waterlogged models have sprout quads, so a dry pickle shows no glow at all
     * and keeps its regular texture, as its luminance of zero says it should.
     *
     * <p>The tip burns at full emission and falls off quadratically over the top rows,
     * the way a lit candle's wax glows hottest at the wick and dims within a few
     * millimetres: row factors 0.77, 0.39, 0.14, 0.02, then nothing. The body stays dark.
     * A few bright texels per sprout read as a point of light on the sea floor around
     * coral without turning the pickle into a lamp.
     */
    static final float SEA_PICKLE_TIP_PEAK = 1.0f;
    static final float SEA_PICKLE_FADE_ROWS = 4.0f;

    static final Mask SEA_PICKLE = (u, v) -> {
        float col = u * 16f, row = v * 16f;
        boolean sprout = (col >= 1f && col < 3f) || (col >= 13f && col < 15f);
        if (!sprout || row >= SEA_PICKLE_FADE_ROWS) {
            return 0f;
        }
        float fromTip = 1f - row / SEA_PICKLE_FADE_ROWS;
        return SEA_PICKLE_TIP_PEAK * fromTip * fromTip;
    };

    /** The beacon sprite paints a bright circular core inside a reflective border. */
    static final Mask BEACON = (u, v) -> {
        float x = u - 0.5f, y = v - 0.5f;
        return 0.12f * Math.clamp(1.5f - 12f * (x * x + y * y), 0f, 1f);
    };

    private static final Map<Identifier, Mask> MASK = Map.of(
            Identifier.withDefaultNamespace("block/sea_pickle"), SEA_PICKLE,
            Identifier.withDefaultNamespace("block/beacon"), BEACON);

    private BuiltinEmission() {
    }

    /** Emission for a sprite the pack leaves unauthored, or null when the mod adds none. */
    public static Mask mask(Identifier spriteId) {
        return MASK.get(spriteId);
    }
}
