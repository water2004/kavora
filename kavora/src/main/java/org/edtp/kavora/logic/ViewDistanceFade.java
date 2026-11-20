/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.logic;

/**
 * Maps Minecraft's render distance onto a conf-layer strength: the shipped
 * 2-chunk endpoint profile blends over the 32-chunk endpoints in
 * {@code rtx.conf}. Manual mode ({@code /kavora viewdistance auto off}) holds the
 * strength at zero, which leaves the 32-chunk baseline showing.
 */
public final class ViewDistanceFade {

    static final int MIN_RENDER_DISTANCE_CHUNKS = 2;
    static final int MAX_RENDER_DISTANCE_CHUNKS = 32;
    static final int MAX_FAR_DISTANCE_CHUNKS = 2048;

    private static final float SHORT_VIEW_FADE_START = 16.0f;
    private static final float FAR_FADE_START_RATIO = 0.7f;
    private static final float FADE_MAX_RATIO = 0.8f;

    // Keep these endpoints aligned with packaging/rtx.conf and
    // confs/view_distance.conf respectively.
    static final float RESTORE_FADE_MIN = computeFadeMin(MAX_RENDER_DISTANCE_CHUNKS);
    static final float RESTORE_FADE_MAX = computeFadeMax(MAX_RENDER_DISTANCE_CHUNKS);
    static final float LAYER_FADE_MIN = computeFadeMin(MIN_RENDER_DISTANCE_CHUNKS);
    static final float LAYER_FADE_MAX = computeFadeMax(MIN_RENDER_DISTANCE_CHUNKS);
    static final float FAR_FADE_MIN = computeFadeMin(MAX_FAR_DISTANCE_CHUNKS);
    static final float FAR_FADE_MAX = computeFadeMax(MAX_FAR_DISTANCE_CHUNKS);

    private boolean auto = true;
    private float farLayerStrength;
    private float displayedFadeMin = RESTORE_FADE_MIN;
    private float displayedFadeMax = RESTORE_FADE_MAX;

    static float computeFadeMin(int renderDistanceChunks) {
        if (renderDistanceChunks >= MAX_RENDER_DISTANCE_CHUNKS) {
            return renderDistanceChunks * 16 * FAR_FADE_START_RATIO;
        }
        float fraction = (float) (renderDistanceChunks - MIN_RENDER_DISTANCE_CHUNKS)
                / (MAX_RENDER_DISTANCE_CHUNKS - MIN_RENDER_DISTANCE_CHUNKS);
        return SHORT_VIEW_FADE_START + fraction
                * (MAX_RENDER_DISTANCE_CHUNKS * 16 * FAR_FADE_START_RATIO - SHORT_VIEW_FADE_START);
    }

    static float computeFadeMax(int renderDistanceChunks) {
        return renderDistanceChunks * 16 * FADE_MAX_RATIO;
    }

    /**
     * Strength of the 2-chunk endpoint over the 32-chunk baseline. Both
     * endpoint values are linear in chunk distance. Short views blend over
     * more of the available terrain while retaining their culling distance.
     */
    public float layerStrength(int renderDistanceChunks) {
        return layerStrength(renderDistanceChunks, 0);
    }

    /** Extends the automatic band when a separate far scene owns additional coverage. */
    public float layerStrength(int renderDistanceChunks, int farDistanceChunks) {
        farLayerStrength = 0;
        if (!auto) {
            displayedFadeMin = RESTORE_FADE_MIN;
            displayedFadeMax = RESTORE_FADE_MAX;
            return 0.0f;
        }
        int far = Math.clamp(farDistanceChunks, 0, MAX_FAR_DISTANCE_CHUNKS);
        if (far > MAX_RENDER_DISTANCE_CHUNKS) {
            displayedFadeMin = computeFadeMin(far);
            displayedFadeMax = computeFadeMax(far);
            farLayerStrength = (float) (far - MAX_RENDER_DISTANCE_CHUNKS)
                    / (MAX_FAR_DISTANCE_CHUNKS - MAX_RENDER_DISTANCE_CHUNKS);
            return 0;
        }
        renderDistanceChunks = Math.max(renderDistanceChunks, far);
        int clamped = Math.clamp(renderDistanceChunks,
                MIN_RENDER_DISTANCE_CHUNKS, MAX_RENDER_DISTANCE_CHUNKS);
        float strength = (float) (MAX_RENDER_DISTANCE_CHUNKS - clamped)
                / (MAX_RENDER_DISTANCE_CHUNKS - MIN_RENDER_DISTANCE_CHUNKS);
        displayedFadeMin = RESTORE_FADE_MIN + strength * (LAYER_FADE_MIN - RESTORE_FADE_MIN);
        displayedFadeMax = computeFadeMax(clamped);
        return strength;
    }

    public boolean isAuto() {
        return auto;
    }
    public float farLayerStrength() { return farLayerStrength; }

    public void setAuto(boolean auto) {
        this.auto = auto;
    }

    public String describe() {
        return (auto ? "auto (from render distance)" : "manual (32-chunk defaults)")
            + ", fade " + Math.round(displayedFadeMin) + " to " + Math.round(displayedFadeMax) + " blocks";
    }
}
