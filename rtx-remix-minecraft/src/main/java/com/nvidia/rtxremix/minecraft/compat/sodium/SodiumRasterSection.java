/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.compat.sodium;

/** Raster publication state of the last accepted mesh, retained for later sort results. */
public interface SodiumRasterSection {
    boolean rtxremix$isRasterUploadOmitted();
    void rtxremix$setRasterUploadOmitted(boolean omitted);
}
