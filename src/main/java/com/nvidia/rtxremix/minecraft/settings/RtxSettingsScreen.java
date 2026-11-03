/*
 * SPDX-FileCopyrightText: Iris contributors
 * SPDX-FileCopyrightText: Modifications Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES.
 * SPDX-License-Identifier: LGPL-3.0-only
 * Adapted from Iris ShaderPackOptionList and ShaderPackScreen.
 * See META-INF/licenses/iris/NOTICE.txt.
 */
package com.nvidia.rtxremix.minecraft.settings;

import com.mojang.blaze3d.platform.InputConstants;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Compact shader folders sharing one Apply/Cancel transaction and live scene preview. */
public final class RtxSettingsScreen extends Screen {
    private record Page(SettingCategory category, SettingGroup group) {}

    private static final int ROW_HEIGHT = 24;
    private final Screen parent;
    private final RtxSettings settings;
    private final RtxSettingsSession session;
    private final Runnable saveSettings;
    private final ArrayDeque<Page> history = new ArrayDeque<>();
    private final List<AbstractWidget> content = new ArrayList<>();
    private Page page = new Page(null, null);
    private ShaderMenuButton applyButton;
    private int left, right, top, bottom, columns, scrollRow, visibleRows;
    private boolean preview;
    private boolean scrolling;

    public RtxSettingsScreen(Screen parent, RtxSettings settings, Runnable saveSettings) {
        super(Component.translatable("rtxremix.settings.title"));
        this.parent = parent;
        this.settings = settings;
        this.session = new RtxSettingsSession(settings);
        this.saveSettings = saveSettings;
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(400, width - 28);
        left = (width - panelWidth) / 2;
        right = left + panelWidth;
        top = 62;
        bottom = height - 48;
        columns = panelWidth >= 300 ? 2 : 1;
        visibleRows = Math.max(1, (bottom - top) / ROW_HEIGHT);
        content.clear();
        if (minecraft.level != null) {
            var view = addRenderableWidget(new ShaderMenuButton(right - 62, 8, 62,
                    Component.translatable(preview ? "rtxremix.settings.show" : "rtxremix.settings.preview"), false, () -> {
                        preview = !preview;
                        rebuildWidgets();
                    }));
            view.setTooltip(Tooltip.create(Component.translatable("rtxremix.settings.preview.tip")));
        }
        if (preview) return;
        addRenderableWidget(new ShaderMenuButton(left, 34, 54, Component.translatable("rtxremix.settings.back"), false, this::back));
        var reset = addRenderableWidget(new ShaderMenuButton(right - 54, 34, 54,
                Component.translatable("rtxremix.settings.resetShort"), false, () -> session.reset(this::inScope)));
        reset.setTooltip(Tooltip.create(Component.translatable("rtxremix.settings.resetPending.tip")));
        buildContent();
        positionContent();
        int footer = width / 2 - 154;
        addRenderableWidget(new ShaderMenuButton(footer, height - 28, 100, CommonComponents.GUI_CANCEL, false, this::onClose));
        applyButton = addRenderableWidget(new ShaderMenuButton(footer + 104, height - 28, 100,
                Component.translatable("rtxremix.settings.apply"), false, this::applyChanges));
        addRenderableWidget(new ShaderMenuButton(footer + 208, height - 28, 100, CommonComponents.GUI_DONE, false, () -> {
            applyChanges();
            minecraft.gui.setScreen(parent);
        }));
    }

    private void buildContent() {
        if (page.group != null) {
            for (var setting : settings.group(page.group)) {
                content.add(addRenderableWidget(new ShaderOptionWidget(setting, session, this::editNumber)));
            }
        } else if (page.category != null) {
            for (var group : SettingGroup.values()) {
                if (group.category() == page.category && !settings.group(group).isEmpty()) {
                    content.add(addRenderableWidget(new ShaderMenuButton(0, 0, 0, Component.translatable(group.labelKey()),
                            true, () -> open(new Page(page.category, group)))));
                }
            }
        } else {
            for (var category : SettingCategory.values()) {
                if (category != SettingCategory.PERFORMANCE && !settings.category(category).isEmpty()) {
                    content.add(addRenderableWidget(new ShaderMenuButton(0, 0, 0, Component.translatable(category.labelKey()),
                            true, () -> open(new Page(category, null)))));
                }
            }
        }
    }

    private boolean inScope(RtxSetting<?> setting) {
        return page.group != null ? setting.group() == page.group
                : page.category != null ? setting.group().category() == page.category
                : setting.group().category() != SettingCategory.PERFORMANCE;
    }

    private void open(Page next) {
        history.push(page);
        page = next;
        scrollRow = 0;
        rebuildWidgets();
    }

    private void back() {
        if (history.isEmpty()) onClose();
        else {
            page = history.pop();
            scrollRow = 0;
            rebuildWidgets();
        }
    }

    private void positionContent() {
        scrollRow = Math.clamp(scrollRow, 0, Math.max(0, (content.size() + columns - 1) / columns - visibleRows));
        int cellWidth = (right - left - (columns - 1) * 4) / columns;
        for (int i = 0; i < content.size(); i++) {
            var widget = content.get(i);
            int row = i / columns - scrollRow;
            widget.visible = row >= 0 && row < visibleRows;
            widget.setX(left + i % columns * (cellWidth + 4));
            widget.setY(top + row * ROW_HEIGHT);
            widget.setWidth(cellWidth);
            widget.setHeight(20);
        }
    }

    private Component pageTitle() {
        return page.group != null ? Component.translatable(page.group.labelKey())
                : page.category != null ? Component.translatable(page.category.labelKey())
                : Component.translatable("rtxremix.settings.effects");
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
        if (!preview) {
            if (minecraft.level == null) extractMenuBackground(gui);
            else extractTransparentBackground(gui);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
        if (!preview) {
            gui.centeredText(font, pageTitle(), width / 2, 40, 0xffe8edf2);
            gui.centeredText(font, title, width / 2, 12, 0xfff1f3f5);
            applyButton.active = session.hasChanges();
            drawScrollbar(gui);
        }
        super.extractRenderState(gui, mouseX, mouseY, partialTick);
    }

    private void drawScrollbar(GuiGraphicsExtractor gui) {
        int rows = (content.size() + columns - 1) / columns;
        if (rows <= visibleRows) return;
        int track = visibleRows * ROW_HEIGHT - 4;
        int handle = Math.max(16, track * visibleRows / rows);
        int y = top + (track - handle) * scrollRow / (rows - visibleRows);
        gui.fill(right + 4, top, right + 6, top + track, 0xff2c3139);
        gui.fill(right + 3, y, right + 7, y + handle, 0xff8b97a5);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (!preview && x >= left - 8 && x <= right + 8 && y >= top && y <= bottom) {
            scrollRow -= (int) Math.signum(vertical) * 3;
            positionContent();
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!preview && event.x() >= right && event.x() <= right + 9 && event.y() >= top && event.y() <= bottom) {
            scrolling = true;
            scrollAt(event.y());
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (scrolling) { scrollAt(event.y()); return true; }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        scrolling = false;
        return super.mouseReleased(event);
    }

    private void scrollAt(double y) {
        int rows = (content.size() + columns - 1) / columns;
        scrollRow = (int) Math.round(Math.clamp((y - top) / (visibleRows * ROW_HEIGHT - 4), 0, 1) * Math.max(0, rows - visibleRows));
        positionContent();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isEscape()) {
            if (preview) { preview = false; rebuildWidgets(); }
            else back();
            return true;
        }
        if (event.key() == InputConstants.KEY_PAGEDOWN || event.key() == InputConstants.KEY_PAGEUP) {
            scrollRow += event.key() == InputConstants.KEY_PAGEDOWN ? visibleRows : -visibleRows;
            positionContent();
            return true;
        }
        return super.keyPressed(event);
    }

    private void editNumber(RtxSetting<?> setting) { minecraft.gui.setScreen(new ShaderNumberScreen(this, setting, session)); }

    private void applyChanges() {
        var groups = session.apply();
        if (groups.isEmpty()) return;
        if (saveSettings != null) saveSettings.run();
        var textures = BlockTextureManager.get();
        if (groups.contains(SettingGroup.MATERIALS) && textures != null && textures.commitTerrainEmissionMultiplier()) {
            RtxRemixMod.rebuildTerrainForMaterialSettings();
        } else if (groups.contains(SettingGroup.BLOCK_LIGHTS)) {
            RtxRemixMod.rebuildLoadedTerrain();
        }
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    public void onClose() {
        session.discard();
        minecraft.gui.setScreen(parent);
    }
}
