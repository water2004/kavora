/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.input;

/** Clears Minecraft's F3 key-combination state when Remix takes input ownership. */
public interface KavoraKeyboardAccess {
    void kavora$resetDebugKeyState();
}
