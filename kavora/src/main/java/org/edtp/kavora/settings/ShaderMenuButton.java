/*
 * SPDX-FileCopyrightText: Iris contributors
 * SPDX-FileCopyrightText: Modifications Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES.
 * SPDX-License-Identifier: LGPL-3.0-only
 * Adapted from Iris LinkElementWidget. See META-INF/licenses/iris/NOTICE.txt.
 */
package org.edtp.kavora.settings;

import net.minecraft.client.Minecraft;
import org.edtp.kavora.settings.iris.IrisWidgets;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Compact folder and action buttons for the shader menu. */
final class ShaderMenuButton extends AbstractWidget {
    private final boolean folder;
    private final Runnable action;

    ShaderMenuButton(int x, int y, int width, Component label, boolean folder, Runnable action) {
        super(x, y, width, 20, label);
        this.folder = folder;
        this.action = action;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
        IrisWidgets.drawButton(gui, getX(), getY(), width, height, active && isHoveredOrFocused(), !active);
        var font = Minecraft.getInstance().font;
        String label = getMessage().getString();
        int available = width - (folder ? 24 : 12);
        if (font.width(label) > available) label = font.plainSubstrByWidth(label, available - font.width("…")) + "…";
        gui.centeredText(font, label, getX() + width / 2, getY() + 6, active ? 0xffededed : 0xff707780);
        if (folder) gui.text(font, ">", getRight() - 10, getY() + 6, 0xffb4c3d2);
    }

    @Override public void onClick(MouseButtonEvent event, boolean doubleClick) { action.run(); }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!active || !visible || !event.isConfirmation()) return false;
        action.run();
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narration) { defaultButtonNarrationText(narration); }
}
