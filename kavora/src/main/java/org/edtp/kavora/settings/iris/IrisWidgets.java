/*
 * SPDX-FileCopyrightText: Iris contributors
 * SPDX-FileCopyrightText: Modifications Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES.
 * SPDX-License-Identifier: LGPL-3.0-only
 *
 * Adapted from Iris GuiUtil, BaseOptionElementWidget, and SliderElementWidget.
 * See META-INF/licenses/iris/NOTICE.txt for the source revision and modifications.
 */
package org.edtp.kavora.settings.iris;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class IrisWidgets {
    private static final Identifier IRIS_WIDGETS_TEX = Identifier.fromNamespaceAndPath("kavora", "textures/gui/iris_widgets.png");

    private IrisWidgets() {}

    public static void drawButton(GuiGraphicsExtractor guiGraphics, int x, int y, int width, int height, boolean hovered, boolean disabled) {
        // Create variables for half of the width and height.
        // Will not be exact when width and height are odd, but
        // that case is handled within the draw calls.
        int halfWidth = width / 2;
        int halfHeight = height / 2;

        // V offset for which button texture to use
        int vOffset = disabled ? 46 : hovered ? 86 : 66;

        // Top left section
        guiGraphics.blit(RenderPipelines.GUI_TEXTURED, IRIS_WIDGETS_TEX, x, y, 0, vOffset, halfWidth, halfHeight, 256, 256);
        // Top right section
        guiGraphics.blit(RenderPipelines.GUI_TEXTURED, IRIS_WIDGETS_TEX, x + halfWidth, y, 200 - (width - halfWidth), vOffset, width - halfWidth, halfHeight, 256, 256);
        // Bottom left section
        guiGraphics.blit(RenderPipelines.GUI_TEXTURED, IRIS_WIDGETS_TEX, x, y + halfHeight, 0, vOffset + (20 - (height - halfHeight)), halfWidth, height - halfHeight, 256, 256);
        // Bottom right section
        guiGraphics.blit(RenderPipelines.GUI_TEXTURED, IRIS_WIDGETS_TEX, x + halfWidth, y + halfHeight, 200 - (width - halfWidth), vOffset + (20 - (height - halfHeight)), width - halfWidth, height - halfHeight, 256, 256);
    }

    public static void drawOption(GuiGraphicsExtractor gui, Font font, Component label, Component value,
                                  int x, int y, int width, int height, boolean hovered, boolean modified,
                                  double sliderPosition) {
        int valueSectionWidth = Math.max(28, font.width(value) + 8);
        int maxLabelWidth = width - 8 - valueSectionWidth;
        String trimmed = label.getString() + ": ";
        if (font.width(trimmed) > maxLabelWidth) {
            trimmed = font.plainSubstrByWidth(trimmed, Math.max(0, maxLabelWidth - font.width("..."))) + "...";
        }
        drawButton(gui, x, y, width, height, hovered, false);
        drawButton(gui, x + width - valueSectionWidth - 2, y + 2, valueSectionWidth, height - 4, false, true);
        if (sliderPosition >= 0) {
            int sliderSpace = valueSectionWidth - 8;
            int sliderPos = x + width - valueSectionWidth + (int) (sliderPosition * sliderSpace);
            drawButton(gui, sliderPos, y + 4, 4, height - 8, false, false);
        }
        gui.text(font, trimmed, x + 6, y + 7, modified ? 0xffffc94a : 0xffffffff);
        gui.text(font, value, x + width - 2 - valueSectionWidth / 2 - font.width(value) / 2, y + 7, 0xffffffff);
    }

    public static void drawSlider(GuiGraphicsExtractor gui, Font font, Component value,
                                  int x, int y, int width, int height, boolean focused, boolean pressed, double position) {
        drawButton(gui, x, y, width, height, focused, false);
        drawButton(gui, x + 2, y + 2, width - 4, height - 4, false, true);
        int sliderSpace = width - 14;
        int sliderPos = x + 4 + (int) (position * sliderSpace);
        drawButton(gui, sliderPos, y + 4, 6, height - 8, pressed, false);
        gui.text(font, value, x + width / 2 - font.width(value) / 2, y + 7, 0xffffffff);
    }
}
