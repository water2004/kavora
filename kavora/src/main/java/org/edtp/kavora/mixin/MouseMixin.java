/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import org.edtp.kavora.KavoraMod;
import org.edtp.kavora.input.KavoraMouseAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Applies the shared Remix overlay input policy to Minecraft's mouse callbacks.
 *
 * The gate blocks Minecraft only while the overlay capture session owns input.
 */
@Mixin(MouseHandler.class)
public abstract class MouseMixin implements KavoraMouseAccess {
    private static final double kavora$HIDDEN_CURSOR_COORDINATE = -Double.MAX_VALUE;

    @Shadow @Final
    private Minecraft minecraft;

    @Shadow
    private MouseButtonInfo activeButton;

    @Shadow
    public abstract void onButton(long window, MouseButtonInfo button, int action);

    private boolean kavora$releasingActiveButton;

    @Override
    public void kavora$releaseActiveButton() {
        if (activeButton == null) {
            return;
        }

        kavora$releasingActiveButton = true;
        try {
            onButton(minecraft.getWindow().handle(), activeButton, InputConstants.RELEASE);
        } finally {
            kavora$releasingActiveButton = false;
        }
    }

    @Shadow
    private double xpos;

    @Shadow
    private double ypos;

    @Shadow
    private boolean ignoreFirstMove;

    @Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
    private void kavora$blockMovementWhileMenuOpen(
            long window, double cursorX, double cursorY, double deltaX, double deltaY, CallbackInfo ci) {
        if (KavoraMod.getRemixInputGate().isCaptureSessionOpen()) {
            xpos = cursorX;
            ypos = cursorY;
            ignoreFirstMove = false;
            ci.cancel();
        }
    }

    /* Screen rendering derives hover state from these coordinates even without a movement event. */
    @Inject(method = "xpos", at = @At("HEAD"), cancellable = true)
    private void kavora$hideXWhileMenuOpen(CallbackInfoReturnable<Double> cir) {
        if (KavoraMod.getRemixInputGate().isCaptureSessionOpen()) {
            cir.setReturnValue(kavora$HIDDEN_CURSOR_COORDINATE);
        }
    }

    @Inject(method = "ypos", at = @At("HEAD"), cancellable = true)
    private void kavora$hideYWhileMenuOpen(CallbackInfoReturnable<Double> cir) {
        if (KavoraMod.getRemixInputGate().isCaptureSessionOpen()) {
            cir.setReturnValue(kavora$HIDDEN_CURSOR_COORDINATE);
        }
    }

    @Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
    private void kavora$blockLockWhileMenuOpen(CallbackInfo ci) {
        if (KavoraMod.getRemixInputGate().isCaptureSessionOpen()) {
            ci.cancel();
        }
    }

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void kavora$blockClickWhileMenuOpen(long window, MouseButtonInfo button, int action, CallbackInfo ci) {
        boolean syntheticRelease = kavora$releasingActiveButton
                && action == InputConstants.RELEASE
                && button.equals(activeButton);
        if (KavoraMod.getRemixInputGate().isCaptureSessionOpen() && !syntheticRelease) {
            ci.cancel();
        }
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void kavora$blockScrollWhileMenuOpen(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (KavoraMod.getRemixInputGate().isCaptureSessionOpen()) {
            ci.cancel();
        }
    }
}
