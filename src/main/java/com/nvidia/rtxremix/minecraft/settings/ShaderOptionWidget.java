/*
 * SPDX-FileCopyrightText: Iris contributors
 * SPDX-FileCopyrightText: Modifications Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES.
 * SPDX-License-Identifier: LGPL-3.0-only
 * Adapted from Iris BaseOptionElementWidget and SliderElementWidget.
 * See META-INF/licenses/iris/NOTICE.txt.
 */
package com.nvidia.rtxremix.minecraft.settings;

import java.time.Duration;
import java.util.function.Consumer;
import com.nvidia.rtxremix.minecraft.settings.iris.IrisWidgets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;

/** Shader option with a compact value, drag slider, keyboard adjustment, and exact entry. */
final class ShaderOptionWidget extends AbstractWidget {
    private final RtxSetting<?> setting;
    private final RtxSettingsSession session;
    private final Consumer<RtxSetting<?>> exactEntry;
    private boolean dragging;

    ShaderOptionWidget(RtxSetting<?> setting, RtxSettingsSession session, Consumer<RtxSetting<?>> exactEntry) {
        super(0, 0, 0, 20, Component.translatable(setting.labelKey()));
        this.setting = setting;
        this.session = session;
        this.exactEntry = exactEntry;
        setTooltip(Tooltip.create(Component.translatable(setting.labelKey()).copy().append("\n")
                .append(Component.translatable(setting.tooltipKey())).append("\n\n")
                .append(Component.translatable("rtxremix.settings.editHint"))));
        setTooltipDelay(Duration.ofMillis(500));
    }

    String settingId() { return setting.id(); }

    private Component valueLabel() {
        return switch (setting.kind()) {
            case BOOL -> RtxOptionFactory.booleanValueText(setting.id(), (Boolean) session.value(setting));
            case INT -> RtxOptionFactory.intValueText(setting.id(), (Integer) session.value(setting));
            case FLOAT -> RtxOptionFactory.floatValueText(setting.id(), (Double) session.value(setting));
        };
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
        var font = Minecraft.getInstance().font;
        Component value = valueLabel();
        if (setting.kind() != RtxSetting.Kind.BOOL && isHoveredOrFocused()) {
            IrisWidgets.drawSlider(gui, font, value, getX(), getY(), width, height, isFocused(), dragging, progress());
        } else {
            IrisWidgets.drawOption(gui, font, getMessage(), value, getX(), getY(), width, height, isHoveredOrFocused(),
                    session.changed(setting), setting.kind() == RtxSetting.Kind.BOOL ? -1 : progress());
        }
    }

    private double progress() {
        double value = ((Number) session.value(setting)).doubleValue();
        if (setting.group() == SettingGroup.BLOCK_LIGHTS) return BlockLightSliderScale.positionFromMultiplier(value);
        if (setting.kind() == RtxSetting.Kind.INT) return (value - setting.min()) / (setting.max() - setting.min());
        return RtxOptionFactory.sliderValueFromFloat(setting.id(), value, setting.min(), setting.max());
    }

    @SuppressWarnings("unchecked")
    private void setProgress(double progress) {
        progress = Math.clamp(progress, 0, 1);
        if (setting.kind() == RtxSetting.Kind.INT) {
            int steps = (int) ((setting.max() - setting.min()) / setting.step());
            session.stage((RtxSetting<Integer>) setting, (int) setting.min() + (int) Math.round(progress * steps) * setting.step());
        } else {
            double value = setting.group() == SettingGroup.BLOCK_LIGHTS
                    ? BlockLightSliderScale.multiplierFromPosition(progress)
                    : RtxOptionFactory.floatValueFromSlider(setting.id(), progress, setting.min(), setting.max());
            session.stage((RtxSetting<Double>) setting, value);
        }
    }

    @SuppressWarnings("unchecked")
    private void toggle() {
        session.stage((RtxSetting<Boolean>) setting, !(Boolean) session.value(setting));
    }

    @Override
    protected boolean isValidClickButton(MouseButtonInfo event) { return event.input() == 0 || event.input() == 1; }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (event.hasShiftDown()) {
            session.reset(value -> value == setting);
        } else if (setting.kind() == RtxSetting.Kind.BOOL) {
            toggle();
        } else if (event.hasControlDown() || event.button() == 1 || doubleClick) {
            exactEntry.accept(setting);
        } else {
            dragging = true;
            setProgress((event.x() - getX() - 3) / (width - 6));
        }
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dx, double dy) {
        if (dragging) setProgress((event.x() - getX() - 3) / (width - 6));
    }

    @Override
    public void onRelease(MouseButtonEvent event) { dragging = false; }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isConfirmation()) {
            if (event.hasShiftDown()) session.reset(value -> value == setting);
            else if (setting.kind() == RtxSetting.Kind.BOOL) toggle();
            else exactEntry.accept(setting);
            return true;
        }
        if (event.isLeft() || event.isRight()) {
            if (setting.kind() == RtxSetting.Kind.BOOL) toggle();
            else setProgress(progress() + (event.isRight() ? 1 : -1)
                    * (setting.kind() == RtxSetting.Kind.INT ? setting.step() / (setting.max() - setting.min()) : 0.01));
            return true;
        }
        return false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narration) { defaultButtonNarrationText(narration); }
}
