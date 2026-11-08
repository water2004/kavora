/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Exact numeric input uses the same pending transaction as the drag control. */
final class ShaderNumberScreen extends Screen {
    private final Screen parent;
    private final RtxSetting<?> setting;
    private final RtxSettingsSession session;
    private EditBox value;
    private ShaderMenuButton confirm;

    ShaderNumberScreen(Screen parent, RtxSetting<?> setting, RtxSettingsSession session) {
        super(Component.translatable(setting.labelKey()));
        this.parent = parent;
        this.setting = setting;
        this.session = session;
    }

    @Override
    protected void init() {
        int x = width / 2 - 130;
        int y = height / 2;
        value = addRenderableWidget(new EditBox(font, x + 8, y - 8, 244, 20, title));
        value.setBordered(false);
        value.setMaxLength(32);
        value.setValue(session.value(setting).toString());
        confirm = addRenderableWidget(new ShaderMenuButton(x + 134, y + 38, 126, CommonComponents.GUI_DONE, false, this::accept));
        addRenderableWidget(new ShaderMenuButton(x, y + 38, 126, CommonComponents.GUI_CANCEL, false, this::onClose));
        value.setResponder(text -> confirm.active = valid());
        setInitialFocus(value);
        value.setHighlightPos(0);
    }

    private boolean valid() {
        try {
            double parsed = Double.parseDouble(value.getValue());
            return Double.isFinite(parsed) && setting.inRange(parsed)
                    && (setting.kind() != RtxSetting.Kind.INT || parsed == Math.rint(parsed) && parsed % setting.step() == 0);
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private void accept() {
        if (!valid()) return;
        if (setting.kind() == RtxSetting.Kind.INT) session.stage((RtxSetting<Integer>) setting, (int) Double.parseDouble(value.getValue()));
        else session.stage((RtxSetting<Double>) setting, Double.parseDouble(value.getValue()));
        onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
        gui.fill(0, 0, width, height, 0xc0000000);
        int x = width / 2 - 142;
        int y = height / 2;
        gui.fill(x, y - 55, x + 284, y + 70, 0xff171c23);
        gui.centeredText(font, title, width / 2, y - 40, 0xffeeeeee);
        gui.fill(x + 12, y - 14, x + 272, y + 16, 0xff080b10);
        gui.centeredText(font, setting.min() + " – " + setting.max(), width / 2, y + 23, valid() ? 0xff929eac : 0xffff9f8a);
        super.extractRenderState(gui, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isConfirmation() && valid()) { accept(); return true; }
        return super.keyPressed(event);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
}
