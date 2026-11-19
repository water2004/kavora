/* SPDX-License-Identifier: Apache-2.0 */
package org.edtp.kavora.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;

/** Marks the draw boundary between camera overlays and HUD elements. */
public enum HudColorBoundary implements GuiElementRenderState {
    INSTANCE;

    private static final ScreenRectangle BOUNDS = new ScreenRectangle(0, 0, 1, 1);

    @Override public void buildVertices(VertexConsumer vertices) {}
    @Override public RenderPipeline pipeline() { return RenderPipelines.GUI; }
    @Override public TextureSetup textureSetup() { return TextureSetup.noTexture(); }
    @Override public ScreenRectangle scissorArea() { return null; }
    @Override public ScreenRectangle bounds() { return BOUNDS; }
}
