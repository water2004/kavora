/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora;

import org.edtp.kavora.camera.CameraController;
import org.junit.jupiter.api.Test;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.sdl.SDLScancode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CameraTimeShortcutTest {

    @Test
    void cameraTimeShortcutRecognizesEqualMinusAndFastModifier() {
        assertEquals(1, KavoraMod.cameraTimeAdjustmentDirection(InputConstants.KEY_EQUALS, 0));
        assertEquals(1, KavoraMod.cameraTimeAdjustmentDirection(
                InputConstants.KEY_EQUALS, InputConstants.MOD_SHIFT));
        assertEquals(-1, KavoraMod.cameraTimeAdjustmentDirection(InputConstants.KEY_MINUS, 0));
        assertEquals(0, KavoraMod.cameraTimeAdjustmentDirection(InputConstants.KEY_ADD, 0));
        assertEquals(0, KavoraMod.cameraTimeAdjustmentDirection(SDLScancode.SDL_SCANCODE_KP_MINUS, 0));
        assertEquals(10, KavoraMod.cameraTimeAdjustmentStep(0));
        assertEquals(250, KavoraMod.cameraTimeAdjustmentStep(InputConstants.MOD_CONTROL));
    }

    @Test
    void cameraTimeShortcutInactiveWithoutRecording() {
        assertFalse(KavoraMod.isCameraTimeShortcutActive(null));
        assertFalse(KavoraMod.isCameraTimeShortcutActive(new CameraController()));
    }
}
