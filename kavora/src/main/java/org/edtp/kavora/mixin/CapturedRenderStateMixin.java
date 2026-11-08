/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import org.edtp.kavora.entity.KavoraRenderStateOwner;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin({EntityRenderState.class, BlockEntityRenderState.class})
public abstract class CapturedRenderStateMixin implements KavoraRenderStateOwner {
    @Unique private Object kavora$owner;
    @Unique private float kavora$partialTick;

    @Override public void kavora$setOwner(Object owner, float partialTick) {
        kavora$owner = owner;
        kavora$partialTick = partialTick;
    }
    @Override public Object kavora$owner() { return kavora$owner; }
    @Override public float kavora$partialTick() { return kavora$partialTick; }
}
