/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import net.minecraft.client.gui.screens.BackupConfirmScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BackupConfirmScreen.class)
public interface BackupConfirmScreenAccessor {
    @Accessor("onProceed")
    BackupConfirmScreen.Listener rtxremix$getProceedListener();
}
