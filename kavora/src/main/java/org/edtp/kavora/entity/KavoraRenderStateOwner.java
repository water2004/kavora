/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.entity;

/** Associates an extracted vanilla render state with its retained Remix owner. */
public interface KavoraRenderStateOwner {
    void kavora$setOwner(Object owner, float partialTick);
    Object kavora$owner();
    float kavora$partialTick();
}
