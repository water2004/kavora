/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.edtp.kavora.KavoraRenderer;
import org.edtp.kavora.KavoraMod;
import org.edtp.kavora.UnfocusedFramerateLimit;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Applies {@link UnfocusedFramerateLimit} while the window is unfocused, so a
 * game left behind other windows does not hold the GPU at path-tracing load.
 * Without an initialized Remix renderer the game draws through vanilla, so
 * the limit is left to vanilla too.
 *
 */
@Mixin(FramerateLimitTracker.class)
public abstract class UnfocusedFramerateMixin {

    @ModifyReturnValue(method = "getFramerateLimit()I", at = @At("RETURN"))
    private int kavora$capWhileUnfocused(int limit) {
        if (Minecraft.getInstance().getWindow().isFocused()) {
            return limit;
        }
        // A benchmark measures renderer throughput and must not be throttled
        // because the user tabbed away while it ran.
        KavoraRenderer renderer = KavoraMod.renderer;
        if (renderer == null || !renderer.isInitialized()
                || renderer.getBenchmarkController().isRunning()) {
            return limit;
        }
        return UnfocusedFramerateLimit.apply(limit);
    }
}
