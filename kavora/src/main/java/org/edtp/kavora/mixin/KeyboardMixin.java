/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import org.edtp.kavora.KavoraMod;
import org.edtp.kavora.input.KavoraInputGate;
import org.edtp.kavora.input.KavoraKeyboardAccess;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.CharacterEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Routes keyboard input through the Remix overlay gate and intercepts the mod's raw shortcuts:
 * Alt+X without Control toggles the overlay, and while its capture session is open every key
 * and character is withheld from Minecraft. Plain R toggles camera recording once an in-game
 * command has armed it, and = and - scrub time of day while recording. Injecting into
 * {@code KeyboardHandler.keyPress} runs this at SDL key-event time, so the modifier mask is read the
 * instant the key is pressed. Matched shortcuts cancel the event so vanilla does not also
 * handle it.
 */
@Mixin(KeyboardHandler.class)
public class KeyboardMixin implements KavoraKeyboardAccess {
    @Shadow
    private long debugCrashKeyTime;

    @Shadow
    private long debugCrashKeyReportedTime;

    @Shadow
    private long debugCrashKeyReportedCount;

    @Shadow
    private boolean usedDebugKeyAsModifier;

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void kavora$onKey(long window, int action, KeyEvent event, CallbackInfo ci) {
        int key = event.key();
        int mods = event.modifiers();
        Minecraft client = Minecraft.getInstance();
        boolean altDown = action == InputConstants.PRESS
                && key == InputConstants.KEY_X
                && ((mods & InputConstants.MOD_ALT) != 0 || event.hasAltDown());
        boolean controlDown = action == InputConstants.PRESS
                && key == InputConstants.KEY_X
                && ((mods & InputConstants.MOD_CONTROL) != 0 || event.hasControlDown());
        KavoraInputGate.KeyDecision decision = KavoraMod.getRemixInputGate().onKey(
                key, action, altDown, controlDown);

        if (decision == KavoraInputGate.KeyDecision.BLOCK) {
            ci.cancel();
            return;
        }
        if (decision == KavoraInputGate.KeyDecision.TOGGLE_MENU) {
            ci.cancel();
            KavoraMod.toggleRemixMenu();
            return;
        }
        if (client.gui.screen() != null
                || (action != InputConstants.PRESS && action != InputConstants.REPEAT)) {
            return;
        }

        if (action == InputConstants.PRESS
                && key == InputConstants.KEY_R
                && KavoraMod.shouldHandlePlainPathRecordingShortcut()) {
            ci.cancel();
            KavoraMod.togglePathRecording();
        } else if (KavoraMod.handleCameraTimeShortcut(key, action, mods)) {
            ci.cancel();
        }
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void kavora$onChar(long window, CharacterEvent event, CallbackInfo ci) {
        if (KavoraMod.getRemixInputGate().isCaptureSessionOpen()) {
            ci.cancel();
        }
    }

    @Inject(method = {"textInput", "textEditing"}, at = @At("HEAD"), cancellable = true)
    private void kavora$blockTextWhileMenuOpen(CallbackInfo ci) {
        if (KavoraMod.getRemixInputGate().isCaptureSessionOpen()) {
            ci.cancel();
        }
    }

    @Override
    public void kavora$resetDebugKeyState() {
        debugCrashKeyTime = -1L;
        debugCrashKeyReportedTime = -1L;
        debugCrashKeyReportedCount = -1L;
        usedDebugKeyAsModifier = false;
    }
}
