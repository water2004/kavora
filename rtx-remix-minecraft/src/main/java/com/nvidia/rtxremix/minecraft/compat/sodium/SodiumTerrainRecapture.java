/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.compat.sodium;

/** Adds previously built sections to the current manager's budgeted task submissions. */
public interface SodiumTerrainRecapture {
    boolean rtxremix$queueRecapture(int x, int y, int z);
}
