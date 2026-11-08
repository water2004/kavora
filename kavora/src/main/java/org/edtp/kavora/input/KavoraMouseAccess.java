/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.input;

/** Mouse state the Remix overlay's input capture needs beyond vanilla's public surface. */
public interface KavoraMouseAccess {
    /** Finishes Minecraft's current pointer interaction when Remix takes input ownership. */
    void kavora$releaseActiveButton();
}
