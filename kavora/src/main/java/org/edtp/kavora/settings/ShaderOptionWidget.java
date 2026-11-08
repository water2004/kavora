/*
 * SPDX-FileCopyrightText: Iris contributors
 * SPDX-FileCopyrightText: Modifications Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES.
 * SPDX-License-Identifier: LGPL-3.0-only
 * Adapted from Iris BaseOptionElementWidget.
 * See META-INF/licenses/iris/NOTICE.txt.
 */
package org.edtp.kavora.settings;

import com.mojang.blaze3d.platform.InputConstants;
import org.edtp.kavora.settings.iris.IrisWidgets;
import java.time.Duration;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;

/** Shared Iris option row, default reset and pending transaction. */
abstract class ShaderOptionWidget extends AbstractWidget {
    protected final KavoraSetting<?> setting;
    protected final KavoraSettingsSession session;

    static ShaderOptionWidget create(KavoraSetting<?> setting, KavoraSettingsSession session,
                                     Consumer<KavoraSetting<?>> exactEntry) {
        return setting.kind() == KavoraSetting.Kind.BOOL || setting.isChoice()
                ? new ShaderCycleWidget(setting, session)
                : new ShaderSliderWidget(setting, session, exactEntry);
    }

    ShaderOptionWidget(KavoraSetting<?> setting, KavoraSettingsSession session) {
        super(0, 0, 0, 20, Component.translatable(setting.labelKey()));
        this.setting = setting;
        this.session = session;
        String hint = setting.kind() == KavoraSetting.Kind.BOOL ? "toggleEditHint"
                : setting.isChoice() ? "choiceEditHint" : "editHint";
        setTooltip(Tooltip.create(Component.translatable(setting.labelKey()).copy().append("\n")
                .append(Component.translatable(setting.tooltipKey())).append("\n\n")
                .append(Component.translatable("kavora.settings." + hint))));
        setTooltipDelay(Duration.ofMillis(500));
    }

    String settingId() { return setting.id(); }

    protected final Component valueLabel() {
        return switch (setting.kind()) {
            case BOOL -> KavoraOptionFactory.booleanValueText(setting.id(), (Boolean) session.value(setting));
            case INT -> KavoraOptionFactory.intValueText(setting.id(), (Integer) session.value(setting));
            case FLOAT -> KavoraOptionFactory.floatValueText(setting.id(), (Double) session.value(setting));
        };
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
        drawOption(gui, -1);
    }

    protected final void drawOption(GuiGraphicsExtractor gui, double sliderPosition) {
        IrisWidgets.drawOption(gui, Minecraft.getInstance().font, getMessage(), valueLabel(),
                getX(), getY(), width, height, isHoveredOrFocused(), session.changed(setting), sliderPosition);
    }

    @Override
    protected final boolean isValidClickButton(MouseButtonInfo event) {
        return event.button() == InputConstants.MOUSE_BUTTON_LEFT
                || event.button() == InputConstants.MOUSE_BUTTON_RIGHT;
    }

    @Override
    public final void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (event.hasShiftDown()) {
            session.reset(value -> value == setting);
        } else {
            changeWithMouse(event);
        }
    }

    protected abstract void changeWithMouse(MouseButtonEvent event);

    @Override
    public final boolean keyPressed(KeyEvent event) {
        if (!active || !visible) return false;
        if (event.isConfirmation() && event.hasShiftDown()) {
            session.reset(value -> value == setting);
            return true;
        }
        return changeWithKeyboard(event);
    }

    protected abstract boolean changeWithKeyboard(KeyEvent event);

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narration) { defaultButtonNarrationText(narration); }
}
