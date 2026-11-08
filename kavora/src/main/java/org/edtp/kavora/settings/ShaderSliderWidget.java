/*
 * SPDX-FileCopyrightText: Iris contributors
 * SPDX-FileCopyrightText: Modifications Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES.
 * SPDX-License-Identifier: LGPL-3.0-only
 * Adapted from Iris SliderElementWidget. See META-INF/licenses/iris/NOTICE.txt.
 */
package org.edtp.kavora.settings;

import com.mojang.blaze3d.platform.InputConstants;
import org.edtp.kavora.settings.iris.IrisWidgets;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

/** Iris numeric slider with keyboard adjustment and explicit exact entry. */
final class ShaderSliderWidget extends ShaderOptionWidget {
    private final Consumer<KavoraSetting<?>> exactEntry;
    private boolean dragging;

    ShaderSliderWidget(KavoraSetting<?> setting, KavoraSettingsSession session, Consumer<KavoraSetting<?>> exactEntry) {
        super(setting, session);
        this.exactEntry = exactEntry;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
        if (isHoveredOrFocused()) {
            IrisWidgets.drawSlider(gui, Minecraft.getInstance().font, valueLabel(), getX(), getY(), width, height,
                    isFocused(), dragging, progress());
        } else {
            drawOption(gui, progress());
        }
    }

    private double progress() {
        double value = ((Number) session.value(setting)).doubleValue();
        if (setting.group() == SettingGroup.BLOCK_LIGHTS) return BlockLightSliderScale.positionFromMultiplier(value);
        if (setting.kind() == KavoraSetting.Kind.INT) return (value - setting.min()) / (setting.max() - setting.min());
        return KavoraOptionFactory.sliderValueFromFloat(setting.id(), value, setting.min(), setting.max());
    }

    @SuppressWarnings("unchecked")
    private void setProgress(double progress) {
        progress = Math.clamp(progress, 0, 1);
        if (setting.kind() == KavoraSetting.Kind.INT) {
            int steps = (int) ((setting.max() - setting.min()) / setting.step());
            session.stage((KavoraSetting<Integer>) setting,
                    (int) setting.min() + (int) Math.round(progress * steps) * setting.step());
        } else {
            double value = setting.group() == SettingGroup.BLOCK_LIGHTS
                    ? BlockLightSliderScale.multiplierFromPosition(progress)
                    : KavoraOptionFactory.floatValueFromSlider(setting.id(), progress, setting.min(), setting.max());
            session.stage((KavoraSetting<Double>) setting, value);
        }
    }

    private void dragTo(double mouseX) {
        setProgress((mouseX - getX() - 4) / (width - 8));
    }

    @Override
    protected void changeWithMouse(MouseButtonEvent event) {
        dragging = false;
        if (event.hasControlDown() || event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            exactEntry.accept(setting);
        } else {
            dragging = true;
            dragTo(event.x());
        }
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dx, double dy) {
        if (dragging && event.button() == InputConstants.MOUSE_BUTTON_LEFT) dragTo(event.x());
    }

    @Override
    public void onRelease(MouseButtonEvent event) { dragging = false; }

    @Override
    protected boolean changeWithKeyboard(KeyEvent event) {
        if (event.isConfirmation()) {
            exactEntry.accept(setting);
            return true;
        }
        if (event.isLeft() || event.isRight()) {
            double step = setting.kind() == KavoraSetting.Kind.INT
                    ? setting.step() / (setting.max() - setting.min()) : 0.01;
            setProgress(progress() + (event.isRight() ? step : -step));
            return true;
        }
        return false;
    }
}
