/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.entity.RemixRenderStateOwner;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin({EntityRenderState.class, BlockEntityRenderState.class})
public abstract class CapturedRenderStateMixin implements RemixRenderStateOwner {
    @Unique private Object rtxremix$owner;
    @Unique private float rtxremix$partialTick;

    @Override public void rtxremix$setOwner(Object owner, float partialTick) {
        rtxremix$owner = owner;
        rtxremix$partialTick = partialTick;
    }
    @Override public Object rtxremix$owner() { return rtxremix$owner; }
    @Override public float rtxremix$partialTick() { return rtxremix$partialTick; }
}
