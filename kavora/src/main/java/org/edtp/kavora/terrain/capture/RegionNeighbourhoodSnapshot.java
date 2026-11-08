/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.terrain.capture;

/** Column presence when an extracted region took its section copies. */
public interface RegionNeighbourhoodSnapshot {
    int kavora$neighbourMask();
}
