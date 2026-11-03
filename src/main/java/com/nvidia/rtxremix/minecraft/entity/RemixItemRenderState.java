/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** Item identity retained beside vanilla's extracted render layers. */
public interface RemixItemRenderState {
    void rtxremix$setItem(ItemStack stack, LivingEntity holder);
    ItemStack rtxremix$item();
    boolean rtxremix$claimEffects();
}
