/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.entity;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** Item identity retained beside vanilla's extracted render layers. */
public interface KavoraItemRenderState {
    void kavora$setItem(ItemStack stack, LivingEntity holder);
    ItemStack kavora$item();
    boolean kavora$claimEffects();
}
