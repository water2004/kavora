/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

/** Column presence when an extracted region took its section copies. */
public interface RegionNeighbourhoodSnapshot {
    int rtxremix$neighbourMask();
}
