/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

/** Native runtime controls exposed by Sodium; ranges match the source runtime. */
enum RtxRuntimeOption {
    INDIRECT("rtx.integrateIndirectMode", "indirect", "lighting", 0, 3, 3),
    BOUNCES("rtx.pathMaxBounces", "bounces", "lighting", 0, 15, 4),
    RTXDI("rtx.useRTXDI", "rtxdi", "lighting", true),
    SPARSE("rtx.sparseRendering.enableSparseRendering", "sparse", "lighting", false),
    CACHE_QUALITY("rtx.spatiallyHashedRadianceCache.qualityPreset", "cacheQuality", "cache", 0, 2, 2),
    CACHE_COMBINE("rtx.spatiallyHashedRadianceCache.combineUpdateAndQuery", "cacheCombine", "cache", false),
    CACHE_PRIMARY("rtx.spatiallyHashedRadianceCache.queryForThinOpaquePrimaryView", "cachePrimary", "cache", true),
    CACHE_FILTER("rtx.spatiallyHashedRadianceCache.cellFilterMode", "cacheFilter", "cache", 0, 2, 2),
    CACHE_SPATIAL_JITTER("rtx.spatiallyHashedRadianceCache.primaryQuerySpatialJitter", "cacheSpatialJitter", "cache", true),
    CACHE_LOD_JITTER("rtx.spatiallyHashedRadianceCache.primaryQueryLodJitter", "cacheLodJitter", "cache", true),
    VOLUME_GRID("rtx.volumetrics.froxelGridResolutionScale", "volumeGrid", "volumetrics", 4, 16, 8),
    VOLUME_DEPTH("rtx.volumetrics.froxelDepthSlices", "volumeDepth", "volumetrics", 32, 256, 64),
    VOLUME_HISTORY("rtx.volumetrics.maxAccumulationFrames", "volumeHistory", "volumetrics", 1, 254, 128);

    final String key, label, group;
    final boolean bool;
    final int min, max, defaultValue;

    RtxRuntimeOption(String key, String label, String group, int min, int max, int defaultValue) {
        this.key = key; this.label = label; this.group = group;
        this.bool = false; this.min = min; this.max = max; this.defaultValue = defaultValue;
    }

    RtxRuntimeOption(String key, String label, String group, boolean defaultValue) {
        this.key = key; this.label = label; this.group = group;
        this.bool = true; this.min = 0; this.max = 1; this.defaultValue = defaultValue ? 1 : 0;
    }

    boolean valid(String value) {
        if (bool) return value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false");
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= min && parsed <= max;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static boolean valid(String key, String value) {
        for (RtxRuntimeOption option : values()) if (option.key.equals(key)) return option.valid(value);
        return false;
    }
}
