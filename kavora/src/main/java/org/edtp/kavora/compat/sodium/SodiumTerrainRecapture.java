/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.compat.sodium;

/** Adds previously built sections to the current manager's budgeted task submissions. */
public interface SodiumTerrainRecapture {
    boolean kavora$queueRecapture(int x, int y, int z);
}
