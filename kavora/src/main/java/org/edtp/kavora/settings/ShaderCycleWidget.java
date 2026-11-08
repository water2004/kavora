/*
 * SPDX-FileCopyrightText: Iris contributors
 * SPDX-FileCopyrightText: Modifications Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES.
 * SPDX-License-Identifier: LGPL-3.0-only
 * Adapted from Iris StringElementWidget and BooleanElementWidget.
 * See META-INF/licenses/iris/NOTICE.txt.
 */
package org.edtp.kavora.settings;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

/** Iris named choices cycle forwards or backwards without opening numeric entry. */
final class ShaderCycleWidget extends ShaderOptionWidget {
    ShaderCycleWidget(KavoraSetting<?> setting, KavoraSettingsSession session) {
        super(setting, session);
    }

    @SuppressWarnings("unchecked")
    private void cycle(int direction) {
        if (setting.kind() == KavoraSetting.Kind.BOOL) {
            session.stage((KavoraSetting<Boolean>) setting, !(Boolean) session.value(setting));
        } else {
            int first = (int) setting.min();
            int count = ((int) setting.max() - first) / setting.step() + 1;
            int index = ((Integer) session.value(setting) - first) / setting.step();
            session.stage((KavoraSetting<Integer>) setting,
                    first + Math.floorMod(index + direction, count) * setting.step());
        }
    }

    @Override
    protected void changeWithMouse(MouseButtonEvent event) {
        cycle(event.button() == InputConstants.MOUSE_BUTTON_RIGHT ? -1 : 1);
    }

    @Override
    protected boolean changeWithKeyboard(KeyEvent event) {
        if (event.isConfirmation() || event.isRight()) {
            cycle(1);
            return true;
        }
        if (event.isLeft()) {
            cycle(-1);
            return true;
        }
        return false;
    }
}
